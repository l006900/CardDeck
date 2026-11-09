package com.arcana.carddeck

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.sin

private const val PERF_COLUMNS = 16

/**
 * Demo 3 —— **同一个场景，两种渲染路径，同屏对跑**。
 *
 * 上半：Compose 原生，`N` 个 Image + graphicsLayer + 圆角金边，每帧靠
 * `withFrameNanos` 重组全部元素（这就是「活动页特效卡」的典型写法）。
 * 下半：GL，`N` 个 [Node]，位姿由 [Scene.motion] 在渲染线程求值，主线程零参与。
 *
 * 两侧同时跑、各自统计真实 FPS：数量 × 每帧全量变换 × 特效同时上去时，差距才出现。
 */
@Composable
fun PerfDemo() {
    var count by remember { mutableIntStateOf(200) }
    var composeFps by remember { mutableFloatStateOf(0f) }
    var glFps by remember { mutableFloatStateOf(0f) }
    var composeTime by remember { mutableFloatStateOf(0f) }

    val painters = remember { (0 until 6).map { BitmapPainter(Art.tile(it).asImageBitmap()) } }
    val scene = remember { Scene() }
    val textures = remember {
        TextureBank().apply { repeat(6) { v -> register("t$v", Art.tile(v)) } }
    }

    // Compose 侧：每帧改一个 state，逼着 N 个 composable 全部重组 + 重绘。
    LaunchedEffect(Unit) {
        var frames = 0
        var windowStart = 0L
        var clockStart = 0L
        while (true) {
            withFrameNanos { now ->
                if (windowStart == 0L) windowStart = now
                if (clockStart == 0L) clockStart = now
                // 用「页面首帧」起的相对时钟，与 SceneRenderer 的 t 同源 ——
                // 两侧动画相位一致，才是「同一个场景、两种渲染路径」的公平对比。
                composeTime = (now - clockStart) / 1_000_000_000f
                frames++
                val dt = now - windowStart
                if (dt >= 500_000_000L) {
                    composeFps = frames * 1_000_000_000f / dt
                    frames = 0
                    windowStart = now
                }
            }
        }
    }

    // GL 侧：一次 submit，之后每帧的位姿全在渲染线程算，主线程什么都不做。
    LaunchedEffect(count) {
        val poses = Layouts.grid(
            count = count,
            columns = PERF_COLUMNS,
            centerY = 0.5f,
            spanX = 0.94f,
            spanY = 0.88f,
            cellW = 0.052f,
            cellH = 0.062f,
        )
        scene.submit(
            poses.mapIndexed { i, p ->
                Node(
                    id = "n$i",
                    pose = p,
                    front = "t${i % 6}",
                    effects = Effects(foil = 1f, shadow = 0.7f),
                )
            },
        )
        scene.motion = Motion { t, i, base ->
            val phase = t * 2f + i * 0.17f
            base.copyWith(
                rotation = t * 38f + i * 3f,
                scale = 0.72f + 0.26f * sin(phase),
                alpha = 0.72f + 0.28f * sin(phase * 0.7f),
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF0B1030), Color(0xFF141026), Color(0xFF070A14)))),
    ) {
        Column(modifier = Modifier.padding(top = 20.dp, start = 18.dp, end = 18.dp)) {
            Text("200 张卡 · 同屏对跑", color = Color(0xFFE9D7A6), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(
                "每帧全量变换 + 金边特效 · 两侧同时运行",
                color = Color(0xFFB9BED1), fontSize = 12.sp,
            )
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                FpsBadge("Compose 原生", composeFps, Color(0xFF9AA2C0))
                Spacer(Modifier.size(22.dp))
                FpsBadge("OpenGL 场景图", glFps, Color(0xFFD6B260))
            }
            Spacer(Modifier.height(8.dp))
            Row {
                listOf(100, 200, 400).forEach { n ->
                    Button(
                        onClick = { count = n },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (count == n) Color(0xFFD6B260) else Color(0xFF2A2F52),
                        ),
                        modifier = Modifier.padding(end = 8.dp),
                    ) { Text("$n 张", fontSize = 11.sp) }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "上：Compose 每帧重组 $count 个 Image+graphicsLayer   下：GL 位姿在渲染线程求值，主线程 0 参与",
                color = Color(0xFF7E85A0), fontSize = 10.sp,
            )
        }

        // 上半：Compose 原生。文字标签一律放顶部说明区 —— 卡片会铺满整块区域，叠上去会糊。
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            ComposeTiles(count, composeTime, painters)
        }

        // 分区线（纯 Compose，位于两块之间，不会被下半的 surface 盖住）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Color(0x66D6B260)),
        )

        // 下半：GL（透明叠层，只有这一块区域被 surface 占据）。
        // 这里不能再放 Compose 文字：setZOrderOnTop 的 surface 在窗口层，会盖住同区域所有 View。
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            SpriteStage(
                scene = scene,
                textures = textures,
                modifier = Modifier.fillMaxSize(),
                gesturesEnabled = false,
                onFps = { glFps = it },
            )
        }
    }
}

@Composable
private fun FpsBadge(label: String, fps: Float, tint: Color) {
    Column {
        Text(label, color = tint, fontSize = 11.sp)
        Text(
            "%.1f fps".format(fps),
            color = tint, fontSize = 22.sp, fontWeight = FontWeight.Bold,
        )
    }
}

/** Compose 原生对照组：N 个图片元素，每帧全量变换 + 圆角金边。 */
@Composable
private fun ComposeTiles(count: Int, timeSec: Float, painters: List<BitmapPainter>) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val cols = PERF_COLUMNS
        val rows = (count + cols - 1) / cols
        val cellW = maxWidth / cols.toFloat()
        val cellH = maxHeight / rows.toFloat()
        repeat(count) { i ->
            val col = i % cols
            val row = i / cols
            val phase = timeSec * 2f + i * 0.17f
            // 让 timeSec 参与 offset（布局参数）而不只是 graphicsLayer：
            // 这才是「数据驱动 N 个元素每帧变换」的真实写法，会走完整 measure/layout 重组。
            val wobble = (sin(phase) * 2f).dp
            androidx.compose.foundation.Image(
                painter = painters[i % painters.size],
                contentDescription = null,
                modifier = Modifier
                    .offset(
                        x = cellW * col.toFloat() + cellW * 0.12f + wobble,
                        y = cellH * row.toFloat() + cellH * 0.12f,
                    )
                    .size(width = cellW * 0.76f, height = cellH * 0.76f)
                    .graphicsLayer {
                        rotationZ = timeSec * 38f + i * 3f
                        scaleX = 0.72f + 0.26f * sin(phase)
                        scaleY = scaleX
                        alpha = 0.72f + 0.28f * sin(phase * 0.7f)
                    }
                    .clip(RoundedCornerShape(4.dp))
                    .border(1.dp, Color(0xFFD6B260), RoundedCornerShape(4.dp)),
            )
        }
    }
}
