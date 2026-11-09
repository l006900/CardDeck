package com.arcana.carddeck

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arcana.gfx.CameraFrameSource
import com.arcana.gfx.CompositeStage
import com.arcana.gfx.DynamicTexture2DSource
import com.arcana.gfx.ExternalTextureSource
import com.arcana.gfx.GpuCompositor
import com.arcana.gfx.GpuStats
import com.arcana.gfx.RenderPolicy
import com.arcana.gfx.ShaderPasses
import com.arcana.gfx.TileSpec
import com.arcana.gfx.Tiles
import com.arcana.gfx.VideoRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Demo 5 —— **采集 → 合成 → 编码 → 回放**的完整闭环。
 *
 * Camera2 实时采集写进 SurfaceTexture(OES)，与一路程序化纹理拼成两格、各套不同特效；
 * 点「录制」后最终合成纹理被送进 MediaCodec 的 input surface 编成 H.264/MP4；
 * 停止后用 MediaPlayer.prepare() 真解码校验产物，并可把该 MP4 作为新视频源回灌进合成器。
 *
 * 这条链覆盖了「采集 / 画面合成 / 编解码 / 播放流程 / 纹理 shader」，
 * 全程 GPU 侧零 CPU 回读；HUD 里的 GPU 时间、进程 CPU、PSS、功率都是实测值。
 */
@Composable
fun CaptureDemo() {
    val context = LocalContext.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val compositor = remember { GpuCompositor() }
    val output = remember { File(context.cacheDir, "capture_composite.mp4") }

    fun hasPermission() = context.checkSelfPermission(Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED

    var granted by remember { mutableStateOf(hasPermission()) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted = it }

    var stats by remember { mutableStateOf(GpuStats()) }
    var notice by remember { mutableStateOf("") }
    var recording by remember { mutableStateOf(false) }
    var recordInfo by remember { mutableStateOf("未录制") }
    var playback by remember { mutableStateOf(false) }
    var encodeW by remember { mutableIntStateOf(480) }
    var encodeH by remember { mutableIntStateOf(800) }
    var camFrames by remember { mutableLongStateOf(0L) }

    val recorderRef = remember { mutableStateOf<VideoRecorder?>(null) }
    val playbackPlayer = remember { mutableStateOf<MediaPlayer?>(null) }

    val camera = remember {
        CameraFrameSource(context, width = 640, height = 480, onFailure = { msg ->
            mainHandler.post { notice = msg }
        })
    }
    val procedural = remember {
        DynamicTexture2DSource(192, 192) { pixels, frame -> fillCapturePattern(pixels, frame) }
    }
    // 回放源必须每次**新建**：复用同一个 ExternalTextureSource 时 onGlCreated 会因
    // created=true 直接返回，MediaPlayer 就一直播着上一次打开的旧文件 fd。
    val playbackSourceRef = remember { mutableStateOf<ExternalTextureSource?>(null) }

    DisposableEffect(Unit) {
        compositor.register("proc", procedural)
        onDispose {
            playbackPlayer.value?.release()
            playbackPlayer.value = null
            recorderRef.value?.close()
            recorderRef.value = null
            playbackSourceRef.value?.close()
            playbackSourceRef.value = null
            camera.close()
            compositor.release()
        }
    }

    // 权限到手后才注册摄像头源：register 会把 GL 初始化投到 GL 线程，
    // 时序上不依赖 View 是否已创建，也不会在缺权限时白跑一次 openCamera。
    LaunchedEffect(granted) {
        if (granted) compositor.register("cam", camera)
        else compositor.unregister("cam")
    }
    LaunchedEffect(playback) {
        if (!playback) {
            compositor.unregister("playback")
            playbackPlayer.value?.release()
            playbackPlayer.value = null
            playbackSourceRef.value?.close()
            playbackSourceRef.value = null
            return@LaunchedEffect
        }
        val src = ExternalTextureSource(
            name = "playback",
            onSurfaceReady = { surface ->
                mainHandler.post {
                    runCatching {
                        MediaPlayer().apply {
                            setSurface(surface)
                            setDataSource(output.absolutePath)
                            isLooping = true
                            setOnPreparedListener { it.start() }
                            setOnErrorListener { _, what, _ ->
                                mainHandler.post { notice = "回放 MediaPlayer error=$what" }
                                true
                            }
                            prepareAsync()
                        }.also { playbackPlayer.value = it }
                    }.onFailure { mainHandler.post { notice = "回放启动失败: ${it.message}" } }
                }
            },
        )
        playbackSourceRef.value = src
        compositor.register("playback", src)
    }

    LaunchedEffect(granted, playback) {
        compositor.tiles = when {
            playback -> listOf(
                TileSpec("playback", Tiles.full(0.012f)),
                TileSpec("cam", Tiles.pictureInPicture()[1], passes = listOf(ShaderPasses.sobel(1f))),
            )
            granted -> listOf(
                TileSpec("cam", Tiles.columns(2)[0], flipY = false),
                TileSpec("proc", Tiles.columns(2)[1], passes = listOf(ShaderPasses.grayscale())),
            )
            else -> listOf(TileSpec("proc", Tiles.full(0.02f)))
        }
        // 这里**不能**置 animating=true：它会把「内容代次」判成每帧都变，
        // 于是源没来新帧时也每 33ms 重画旧纹理并送进编码器 = 「渲染一帧，然后一直插它」。
        // proc 格是 DynamicTexture2DSource，它自己的 selfAnimating 已经会报「在产帧」；
        // cam / playback 是外部 OES 源，由 hasPendingFrame 驱动。
        compositor.animating = false
        compositor.invalidate()
    }

    fun stopRecord() {
        val r = recorderRef.value ?: return
        r.stop()
        recording = false
        compositor.postGl {
            r.releaseGl()
            compositor.attachFrameSink(null)
        }
    }

    // 录制 6 秒自动收尾，避免 sample 一直写文件；也可手动点停止。
    LaunchedEffect(recording) {
        if (recording) {
            delay(6_000L)
            stopRecord()
        }
    }

    // 停止后等 muxer 落盘，再用 MediaPlayer 真解码校验（prepare 会解析 moov 并初始化解码器）。
    LaunchedEffect(recording) {
        if (recording) return@LaunchedEffect
        delay(1_500L)
        val r = recorderRef.value ?: return@LaunchedEffect
        val result = withContext(Dispatchers.IO) {
            if (!output.exists() || output.length() < 8_000L) {
                "未产出有效文件 (${output.length()} B)"
            } else {
                runCatching {
                    val mp = MediaPlayer()
                    try {
                        mp.setDataSource(output.absolutePath)
                        mp.prepare()
                        "MP4 可解码 ${mp.videoWidth}×${mp.videoHeight} · " +
                            "%.1fs · %dKB · 编码 %d 帧".format(
                                mp.duration / 1000f, output.length() / 1024, r.framesWritten(),
                            )
                    } finally {
                        mp.release()
                    }
                }.getOrElse { "回放校验失败: ${it.message}" }
            }
        }
        recordInfo = result
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF0B1030), Color(0xFF121026), Color(0xFF070A14)))),
    ) {
        Column(modifier = Modifier.padding(top = 20.dp, start = 16.dp, end = 16.dp)) {
            Text(
                "采集 · 编码 · 回放", color = Color(0xFFE9D7A6),
                fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Camera2 → OES → 双画面合成 → MediaCodec(H.264/MP4) → MediaPlayer 回放",
                color = Color(0xFFB9BED1), fontSize = 11.sp,
            )
            Spacer(Modifier.height(6.dp))
            // 五行全部固定高度、固定单行，避免 HUD 换行带动 GLSurfaceView 高度抖动；
            // HudLine 会按可用宽度自适应字号，指标变长也不会在右侧被裁掉。
            HudLine(stats.summary(), color = Color(0xFFD6B260), baseSize = 11.sp, lineHeight = 16.dp)
            HudLine(stats.systemLine(), color = Color(0xFF9BA6C8))
            HudLine(stats.flowLine(), color = Color(0xFF9BA6C8))
            HudLine(
                "Camera2 到达 $camFrames 帧" + if (granted) " · 已注册采集源" else " · 待授权",
                color = Color(0xFF9BA6C8),
            )
            HudLine(recordInfo, color = Color(0xFF8FE3B0))
            HudLine(notice, color = Color(0xFFFF9E80))
            Spacer(Modifier.height(6.dp))
            Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                CaptureButton(if (granted) "摄像头 已授权" else "授权摄像头", granted) {
                    if (!granted) permissionLauncher.launch(Manifest.permission.CAMERA)
                }
                CaptureButton(if (recording) "停止录制" else "录制 6s", recording) {
                    if (recording) {
                        stopRecord()
                    } else {
                        if (output.exists()) output.delete()
                        val r = VideoRecorder(
                            output, encodeW, encodeH, fps = 30,
                            onError = { msg -> mainHandler.post { notice = msg } },
                        )
                        if (r.start()) {
                            recorderRef.value = r
                            compositor.attachFrameSink(r)
                            recordInfo = "录制中…"
                            recording = true
                        } else {
                            r.close()
                            recordInfo = "编码器不可用"
                        }
                    }
                }
                CaptureButton("回放录制", playback) { playback = !playback }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "GPU 时间来自 C++ 侧的 GL_EXT_disjoint_timer_query；CPU / PSS / 功率取自进程与系统采样。" +
                    " 录制走 MediaCodec input surface 的 EGL 二次渲染，零 CPU 回读。",
                color = Color(0xFF7E85A0), fontSize = 10.sp,
            )
        }

        BoxWithConstraints(modifier = Modifier.fillMaxWidth().weight(1f)) {
            // 编码尺寸取 GL View 的一半并对齐 16，避免编码器静默失败。
            val w = (constraints.maxWidth * 0.5f).toInt() / 16 * 16
            val h = (constraints.maxHeight * 0.5f).toInt() / 16 * 16
            LaunchedEffect(w, h) {
                if (w >= 16 && h >= 16) {
                    encodeW = w
                    encodeH = h
                }
            }
            CompositeStage(
                compositor = compositor,
                policy = RenderPolicy(maxFps = 30),
                modifier = Modifier.fillMaxSize(),
                onStats = {
                    stats = it
                    camFrames = camera.framesArrived()
                },
                onError = { notice = it },
            )
        }
    }
}

@Composable
private fun CaptureButton(label: String, active: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (active) Color(0xFFD6B260) else Color(0xFF2A2F52),
        ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 2.dp),
        modifier = Modifier.padding(end = 6.dp, bottom = 4.dp),
    ) {
        Text(label, fontSize = 10.sp, color = if (active) Color(0xFF1B1430) else Color(0xFFE9D7A6))
    }
}

/** 一路纯 CPU 动态纹理，用来在没有摄像头权限时也能验证合成链在出帧。 */
private fun fillCapturePattern(pixels: ByteArray, frame: Int) {
    var p = 0
    for (y in 0 until 192) {
        for (x in 0 until 192) {
            val band = ((x + frame * 2) / 24) % 2
            val ring = if ((x - 96) * (x - 96) + (y - 96) * (y - 96) < 900) 1 else 0
            pixels[p++] = ((0.10f + band * 0.35f + ring * 0.5f) * 255f).toInt().toByte()
            pixels[p++] = ((0.12f + y / 192f * 0.5f) * 255f).toInt().toByte()
            pixels[p++] = ((0.62f - x / 192f * 0.35f) * 255f).toInt().toByte()
            pixels[p++] = 0xFF.toByte()
        }
    }
}
