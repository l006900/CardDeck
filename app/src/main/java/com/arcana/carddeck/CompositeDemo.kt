package com.arcana.carddeck

import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arcana.gfx.CompositeStage
import com.arcana.gfx.DynamicTexture2DSource
import com.arcana.gfx.ExternalTextureSource
import com.arcana.gfx.GpuCompositor
import com.arcana.gfx.GpuStats
import com.arcana.gfx.RenderMode
import com.arcana.gfx.RenderPolicy
import com.arcana.gfx.ShaderPasses
import com.arcana.gfx.TileSpec
import com.arcana.gfx.Tiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.sin
import kotlin.math.sqrt

private val LAYOUT_NAMES = listOf("2×2 宫格", "画中画", "三列", "全屏")
private val CHAIN_NAMES = listOf("全局:无", "调色", "故障", "自定义 GLSL")

/** 四路各取源画面的不同 uv 区域 —— 拼接 / 局部放大的直观演示。 */
private val CROPS = listOf(
    floatArrayOf(0f, 0f, 1f, 1f),
    floatArrayOf(0f, 0f, 0.5f, 0.5f),
    floatArrayOf(0.5f, 0f, 1f, 0.5f),
    floatArrayOf(0.25f, 0.25f, 0.75f, 0.75f),
)

/** 调用方自己写的 GLSL：只要遵守 uTexture/uTexel/uParams/uTime 约定就能进链。 */
private const val CUSTOM_GLSL = """
precision mediump float;
uniform sampler2D uTexture;
uniform vec2 uTexel;
uniform vec4 uParams;
uniform float uTime;
varying vec2 vUv;
void main() {
    vec2 p = vUv - vec2(0.5);
    float r = length(p);
    vec2 dir = normalize(p + vec2(0.0001));
    float ripple = sin(r * 55.0 - uTime * 4.0) * 0.010;
    vec4 c = texture2D(uTexture, vUv + dir * ripple);
    c.rgb += vec3(0.10, 0.06, 0.0) * (0.5 + 0.5 * sin(uTime + r * 12.0));
    gl_FragColor = c;
}
"""

private const val PROC_SIZE = 256

/**
 * Demo 4 —— **GPU 合成管线**：真实的视频渲染 / 多画面拼接 / 特效链 / 帧调度。
 *
 * 链路：MediaCodec 现场编码 H.264 → MediaPlayer 解码 → SurfaceTexture(GL_TEXTURE_EXTERNAL_OES)
 * → 4 路各自独立的 shader 链 → 各自 FBO → 拼进 scene FBO → 全局后处理 → 屏幕。
 *
 * 四条线程各管一段：解码线程投帧、HandlerThread 收帧、GL 线程渲染、UI 线程只改参数。
 * 顶部 HUD 的 FPS / CPU 帧耗时 / draw call / 纹理显存 / p50-p95 延迟 / 丢帧都是实测值。
 */
@Composable
fun CompositeDemo() {
    val context = LocalContext.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val compositor = remember { GpuCompositor() }

    var videoPath by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf("正在生成测试视频…") }
    var stats by remember { mutableStateOf(GpuStats()) }
    var lastStatsAt by remember { mutableLongStateOf(0L) }
    var idle by remember { mutableStateOf(false) }
    var layoutIndex by remember { mutableIntStateOf(0) }
    var chainIndex by remember { mutableIntStateOf(1) }
    var fpsCap by remember { mutableIntStateOf(30) }
    var onDemand by remember { mutableStateOf(false) }
    var useVideo by remember { mutableStateOf(true) }
    var scale by remember { mutableFloatStateOf(1f) }
    var budgetMb by remember { mutableIntStateOf(0) }

    val playerRef = remember { mutableStateOf<MediaPlayer?>(null) }
    val videoSourceRef = remember { mutableStateOf<ExternalTextureSource?>(null) }
    val procedural = remember {
        DynamicTexture2DSource(PROC_SIZE, PROC_SIZE) { pixels, frame ->
            fillProcedural(pixels, frame)
        }
    }

    LaunchedEffect(Unit) { compositor.register("proc", procedural) }

    // 一次性在 IO 线程编码测试视频；失败也能靠程序化纹理源继续验证整条 GL 链。
    LaunchedEffect(Unit) {
        val path = withContext(Dispatchers.IO) {
            runCatching { TestVideo.ensure(context).absolutePath }.getOrNull()
        }
        videoPath = path
        notice = if (path == null) "H.264 编码不可用，已切到程序化纹理源" else ""
    }

    // 视频源就绪后再注册：register 会把 GL 初始化投到 GL 线程，时序不依赖 View 是否已创建。
    DisposableEffect(videoPath) {
        val path = videoPath
        if (path != null) {
            val source = ExternalTextureSource(
                name = "video",
                onSurfaceReady = { surface ->
                // onSurfaceReady 在 GL 线程，MediaPlayer 必须回主线程操作。
                mainHandler.post {
                    runCatching {
                        val existing = playerRef.value
                        if (existing == null) {
                            MediaPlayer().apply {
                                setSurface(surface)
                                setDataSource(path)
                                isLooping = true
                                setOnPreparedListener { it.start() }
                                setOnErrorListener { _, what, _ ->
                                    notice = "MediaPlayer error=$what"
                                    true
                                }
                                prepareAsync()
                            }.also { playerRef.value = it }
                        } else {
                            // GL 上下文重建后 SurfaceTexture 换了，把新 Surface 交给同一个播放器。
                            existing.setSurface(surface)
                        }
                    }.onFailure { notice = "播放器启动失败: ${it.message}" }
                }
            },
            )
            videoSourceRef.value = source
            compositor.register("video", source)
        }
        onDispose {
            playerRef.value?.release()
            playerRef.value = null
            videoSourceRef.value?.close()
            videoSourceRef.value = null
            compositor.unregister("video")
        }
    }

    val sourceKey = if (useVideo && videoPath != null) "video" else "proc"
    // 自定义 GLSL 依赖 uTime，需要连续出帧；按需渲染与它互斥，避免看起来"卡住"。
    val effectiveOnDemand = onDemand && chainIndex != 3

    // GL 线程空闲时根本不会回调 onStats，只显示最后一次快照会把「0 帧」粉饰成高 fps。
    // 事件驱动：每次指标到达就重新挂一个一次性延迟判定，新指标会取消旧的；
    // 没有轮询、没有常驻重组，空闲时只重组一次就停下。
    LaunchedEffect(effectiveOnDemand, lastStatsAt) {
        if (!effectiveOnDemand || lastStatsAt == 0L) {
            idle = false
            return@LaunchedEffect
        }
        delay(1_200L)
        if (SystemClock.elapsedRealtime() - lastStatsAt >= 1_000L) idle = true
    }

    LaunchedEffect(sourceKey, layoutIndex, effectiveOnDemand) {
        procedural.active = !effectiveOnDemand
        val layout = when (layoutIndex) {
            0 -> Tiles.grid(4, columns = 2)
            1 -> Tiles.pictureInPicture()
            2 -> Tiles.columns(3)
            else -> listOf(Tiles.full(0.02f))
        }
        // 每一路画面套**不同**的 shader 链：这就是「画面合成 / 特效模块」的真实形态。
        val perTileChains = listOf(
            emptyList(),
            listOf(ShaderPasses.grayscale(), ShaderPasses.sobel(1.2f)),
            ShaderPasses.blur(1.2f) + ShaderPasses.colorGrade(warmth = 1f),
            listOf(ShaderPasses.chromaShift(3f), ShaderPasses.vignette(0.5f)),
        )
        compositor.tiles = layout.mapIndexed { i, tile ->
            TileSpec(
                source = sourceKey,
                tile = tile,
                crop = if (layoutIndex == 0) CROPS[i % CROPS.size] else floatArrayOf(0f, 0f, 1f, 1f),
                passes = perTileChains[i % perTileChains.size],
            )
        }
    }

    LaunchedEffect(chainIndex) {
        compositor.passes = when (chainIndex) {
            0 -> emptyList()
            1 -> listOf(ShaderPasses.colorGrade())
            2 -> listOf(ShaderPasses.chromaShift(5f), ShaderPasses.vignette(0.45f, 0.3f))
            else -> listOf(ShaderPasses.custom("ripple", CUSTOM_GLSL))
        }
        compositor.animating = chainIndex >= 2
    }

    val policy = RenderPolicy(
        mode = if (effectiveOnDemand) RenderMode.ON_DEMAND else RenderMode.CONTINUOUS,
        maxFps = fpsCap,
        resolutionScale = scale,
        maxTextureBytes = budgetMb.toLong() * 1_048_576L,
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF0B1030), Color(0xFF141026), Color(0xFF070A14)))),
    ) {
        Column(modifier = Modifier.padding(top = 20.dp, start = 16.dp, end = 16.dp)) {
            Text(
                "GPU 合成管线 · 多画面", color = Color(0xFFE9D7A6),
                fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "MediaCodec 编码 → MediaPlayer 解码 → OES 纹理 → 每路独立 shader → FBO 拼接 → 全局后处理",
                color = Color(0xFFB9BED1), fontSize = 11.sp,
            )
            Spacer(Modifier.height(6.dp))
            // HUD 必须固定占两行：文本换行会让顶部 Column 高度变化，进而改变下方
            // GLSurfaceView 的高度 → onSurfaceChanged → 重画 → 指标刷新 → 又换行，
            // 形成 9px 级别的布局自激（按需渲染会被它永久唤醒）。
            HudLine(
                if (idle) "上次快照（非实时）· ${stats.summary()}" else stats.summary(),
                color = if (idle) Color(0xFF727A98) else Color(0xFFD6B260),
                baseSize = 11.sp, lineHeight = 16.dp,
            )
            HudLine(
                if (idle) "空闲 · 0 fps（无渲染回调）" else "",
                color = Color(0xFF8FE3B0),
            )
            // 这两行始终固定高度：HUD 一旦换行就会带动 GLSurfaceView 高度抖动，
            // 进而 onSurfaceChanged → 重画 → 指标刷新 → 又换行，把按需渲染打成永久空转。
            HudLine(stats.systemLine(), color = if (idle) Color(0xFF727A98) else Color(0xFF9BA6C8))
            HudLine(stats.flowLine(), color = if (idle) Color(0xFF727A98) else Color(0xFF9BA6C8))
            // 提示也常驻固定行：条件出现/消失同样会改变 Column 高度。
            HudLine(notice, color = Color(0xFFFF9E80), baseSize = 11.sp, lineHeight = 16.dp)
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                LAYOUT_NAMES.forEachIndexed { i, name ->
                    SmallButton(name, layoutIndex == i) { layoutIndex = i }
                }
                SmallButton("源:${if (useVideo) "视频" else "CPU"}", useVideo) { useVideo = !useVideo }
            }
            Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                CHAIN_NAMES.forEachIndexed { i, name ->
                    SmallButton(name, chainIndex == i) { chainIndex = i }
                }
            }
            Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                SmallButton("$fpsCap fps", true) { fpsCap = if (fpsCap == 30) 60 else 30 }
                SmallButton("按需渲染 ${if (onDemand) "ON" else "OFF"}", onDemand) { onDemand = !onDemand }
                SmallButton("强制重画", false) { compositor.invalidate() }
            }
            Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                SmallButton("scale %.2f".format(scale), scale < 1f) {
                    scale = when {
                        scale > 0.99f -> 0.75f
                        scale > 0.74f -> 0.5f
                        scale > 0.49f -> 0.35f
                        else -> 1f
                    }
                }
                SmallButton(
                    if (budgetMb == 0) "显存预算 OFF" else "预算 ${budgetMb}MB(自动降档)",
                    budgetMb > 0,
                ) { budgetMb = if (budgetMb == 0) 8 else 0 }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "4 格分别套 原画 / 灰度+边缘 / 模糊+调色 / 色差+暗角；「自定义 GLSL」是调用方直接传的源码。" +
                    " 按需渲染下只有新帧或参数变化才出帧：静态源会显示「空闲 · 0 fps」，视频源贴合解码帧率。",
                color = Color(0xFF7E85A0), fontSize = 10.sp,
            )
        }

        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            CompositeStage(
                compositor = compositor,
                policy = policy,
                modifier = Modifier.fillMaxSize(),
                onStats = {
                    stats = it
                    idle = false
                    lastStatsAt = SystemClock.elapsedRealtime()
                },
                onError = { notice = it },
            )
        }
    }
}

@Composable
private fun SmallButton(label: String, active: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (active) Color(0xFFD6B260) else Color(0xFF2A2F52),
        ),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
        modifier = Modifier.padding(end = 6.dp, bottom = 4.dp),
    ) {
        Text(
            label, fontSize = 10.sp,
            color = if (active) Color(0xFF1B1430) else Color(0xFFE9D7A6),
        )
    }
}

/**
 * HUD 单行：固定行高 + 按可用宽度自适应字号。
 *
 * 「固定高度 + maxLines=1 + softWrap=false」只挡住了纵向换行引起的布局自激，
 * 挡不住**横向**溢出：指标数字一变长（pss 128.4 MB、power 264 mW），
 * 尾部会被硬裁成 `p95 53.2 r` / `power 25 m`，验收数字直接不可信。
 *
 * 这里用 TextMeasurer 量一次单行真实宽度，按线性比例收敛字号（最多 3 次补偿
 * 字距/字形的非线性，下限 7sp），让文本始终完整放进一行；行高依旧恒定，
 * 所以 GLSurfaceView 的高度不会因为 HUD 内容变化而抖动。
 */
@Composable
internal fun HudLine(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    baseSize: TextUnit = 10.sp,
    lineHeight: Dp = 14.dp,
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth().height(lineHeight)) {
        val measurer = rememberTextMeasurer()
        val baseStyle = LocalTextStyle.current
        val availablePx = with(LocalDensity.current) { maxWidth.toPx() }
        var sizeSp = baseSize.value
        var textWidth = measurer.measure(
            AnnotatedString(text),
            style = baseStyle.copy(fontSize = sizeSp.sp),
            softWrap = false,
            maxLines = 1,
        ).size.width
        repeat(3) {
            if (textWidth <= availablePx || sizeSp <= 7f) return@repeat
            sizeSp = (sizeSp * availablePx / textWidth).coerceAtLeast(7f)
            textWidth = measurer.measure(
                AnnotatedString(text),
                style = baseStyle.copy(fontSize = sizeSp.sp),
                softWrap = false,
                maxLines = 1,
            ).size.width
        }
        Text(
            text = text, color = color, fontSize = sizeSp.sp,
            maxLines = 1, softWrap = false,
        )
    }
}

/** 程序化动态纹理：移动竖条 + 跟随正弦运动的圆环，便于判断画面方向与帧是否在推进。 */
private fun fillProcedural(pixels: ByteArray, frame: Int) {
    var p = 0
    val cx = 0.5f + 0.24f * sin(frame * 0.045).toFloat()
    for (y in 0 until PROC_SIZE) {
        val v = y.toFloat() / PROC_SIZE
        for (x in 0 until PROC_SIZE) {
            val u = x.toFloat() / PROC_SIZE
            val bar = ((x + frame * 3) / 32) % 2
            val dx = u - cx
            val dy = v - 0.5f
            val ring = if (sqrt(dx * dx + dy * dy) in 0.17f..0.21f) 1f else 0f
            pixels[p++] = ((0.12f + 0.55f * v + 0.22f * bar + ring * 0.6f).coerceIn(0f, 1f) * 255f).toInt().toByte()
            pixels[p++] = ((0.10f + 0.50f * u + ring * 0.25f).coerceIn(0f, 1f) * 255f).toInt().toByte()
            pixels[p++] = ((0.72f - 0.35f * v + bar * 0.1f).coerceIn(0f, 1f) * 255f).toInt().toByte()
            pixels[p++] = 0xFF.toByte()
        }
    }
}
