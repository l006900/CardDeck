package com.arcana.carddeck

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.sin

private val LAYOUT_NAMES = listOf("扇形 Fan", "网格 Grid", "牌叠 Stack", "散落 Scatter", "环形 Arc")
private const val NODE_COUNT = 24

/**
 * Demo 2 —— 通用场景图（低层 API [SpriteStage]）。
 *
 * 同一批 [Node] 换 5 种布局；点一张走 3D 弯曲翻面 + 体积光晕 + 粒子爆发，
 * 且翻面插值跑在 GL 线程（[FlipMotion]），主线程一次重组都不发生。
 * 所有文字都在 Compose 层，库一个字都不画。
 */
@Composable
fun SceneDemo() {
    val scene = remember { Scene() }
    val textures = remember {
        TextureBank().apply {
            register("back", Art.cardBack())
            register("face", Art.cardFace())
        }
    }
    val motion = remember { FlipMotion(NODE_COUNT) }
    var layoutIndex by remember { mutableIntStateOf(0) }
    var starfield by remember { mutableStateOf(true) }
    var picked by remember { mutableStateOf("—") }
    var stageSize by remember { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(layoutIndex) {
        val poses = when (layoutIndex) {
            0 -> Layouts.fan(NODE_COUNT, centerY = 0.52f, width = 0.115f, height = 0.25f, spacing = 0.038f, spreadDeg = 6f, arcDrop = 0.14f)
            1 -> Layouts.grid(NODE_COUNT, columns = 6, cellW = 0.115f, cellH = 0.115f, centerY = 0.50f, spanY = 0.66f)
            2 -> Layouts.stack(NODE_COUNT, centerY = 0.52f, width = 0.13f, height = 0.26f)
            3 -> Layouts.scatter(NODE_COUNT, seed = 7L, width = 0.10f, height = 0.15f)
            else -> Layouts.arc(NODE_COUNT, width = 0.085f, height = 0.13f)
        }
        scene.submit(
            poses.mapIndexed { i, p ->
                Node(
                    id = "node-$i",
                    pose = p,
                    front = "face",
                    back = "back",
                    effects = Effects(halo = 0f, foil = 1f, shadow = 1f, bend = 0.55f),
                )
            },
        )
        scene.motion = motion
        scene.starfield = starfield
    }
    LaunchedEffect(starfield) { scene.starfield = starfield }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF0B1030), Color(0xFF1B1430), Color(0xFF070A14)))),
    ) {
        Column(modifier = Modifier.padding(top = 20.dp, start = 18.dp, end = 18.dp)) {
            Text("SpriteStage · 通用场景图", color = Color(0xFFE9D7A6), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(
                "24 个 Node / 5 种布局 · 点击翻面+光晕+粒子 · 可拖动",
                color = Color(0xFFB9BED1), fontSize = 13.sp,
            )
            Text("命中：$picked", color = Color(0xFF8A8FA3), fontSize = 12.sp)
            Spacer(Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                LAYOUT_NAMES.forEachIndexed { i, name ->
                    Button(
                        onClick = { layoutIndex = i; picked = "—" },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (i == layoutIndex) Color(0xFFD6B260) else Color(0xFF2A2F52),
                        ),
                        modifier = Modifier.padding(end = 8.dp),
                    ) { Text(name, fontSize = 11.sp) }
                }
                Button(
                    onClick = { starfield = !starfield },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (starfield) Color(0xFF3B4C86) else Color(0xFF2A2F52),
                    ),
                ) { Text(if (starfield) "星辉 ON" else "星辉 OFF", fontSize = 11.sp) }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .weight(1f)
                .onGloballyPositioned { stageSize = it.size },
        ) {
            SpriteStage(
                scene = scene,
                textures = textures,
                modifier = Modifier.fillMaxSize(),
                starfield = starfield,
                onPick = { pick ->
                    picked = "${pick.id} · 四角点 ${pick.corners.size / 2} 个"
                    motion.flip(pick.index)
                    // 四角点求中心 → 归一化 → 粒子爆发落在这张图上
                    val c = pick.corners
                    val cxPx = (c[0] + c[2] + c[4] + c[6]) / 4f
                    val cyPx = (c[1] + c[3] + c[5] + c[7]) / 4f
                    scene.burst(
                        (cxPx / stageSize.width.coerceAtLeast(1)).coerceIn(0f, 1f),
                        (cyPx / stageSize.height.coerceAtLeast(1)).coerceIn(0f, 1f),
                    )
                    scene.update { list ->
                        for (i in list.indices) {
                            list[i] = list[i].copy(
                                effects = list[i].effects.copy(halo = if (i == pick.index) 0.9f else 0f),
                            )
                        }
                    }
                },
                onDrag = { pick, nx, ny ->
                    scene.update { list ->
                        if (pick.index in list.indices) {
                            list[pick.index] = list[pick.index].copy(
                                pose = list[pick.index].pose.copy(x = nx, y = ny),
                            )
                        }
                    }
                },
            )
        }
    }
}

/**
 * 翻面动画：全部在 GL 线程求值，UI 线程只写一个 volatile 引用（copy-on-write 目标数组）。
 * 这正是「每帧都在动的元素不经过 Compose 重组」的最小示范。
 */
private class FlipMotion(private val count: Int) : Motion {
    @Volatile
    private var targets = FloatArray(count)
    private val values = FloatArray(count)

    fun flip(index: Int) {
        if (index !in 0 until count) return
        val copy = targets.copyOf()
        copy[index] = if (copy[index] > 0.5f) 0f else 1f
        targets = copy
    }

    override fun poseAt(timeSec: Float, index: Int, base: Pose): Pose {
        if (index !in 0 until count) return base
        val target = targets[index]
        val next = values[index] + (target - values[index]) * 0.10f
        values[index] = next
        val bend = sin(next * PI.toFloat())
        return base.copyWith(
            flip = next,
            lift = bend * 0.26f,
            scale = 1f + bend * 0.12f,
        )
    }
}
