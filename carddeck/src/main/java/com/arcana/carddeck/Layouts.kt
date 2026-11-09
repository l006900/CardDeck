package com.arcana.carddeck

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 纯位姿生成器：**只产 [Pose]，不碰渲染、不认识贴图、不认识业务**。
 *
 * 布局是「一次性算出静态位姿」的函数；要每帧动，给 [Scene.motion] 一个纯函数，
 * 对基准位姿做变换（例如整组旋转、波浪、呼吸），主线程不参与。
 * 这类函数刻意保持无状态、无依赖，方便外部自己写第五种布局。
 */
object Layouts {

    /**
     * ∩ 形扇形手牌：中间那张最高、两侧向下向外散开，牌顶朝外（[Pose.rotation] 递增）。
     *
     * @param spacing 相邻两张牌心的水平间距（归一化视宽）。
     * @param spreadDeg 相邻两张的张角（度），越大扇面越开。
     * @param arcDrop 两端相对中间的下垂量（归一化视高）。
     */
    fun fan(
        count: Int,
        centerX: Float = 0.5f,
        centerY: Float = 0.55f,
        width: Float = 0.12f,
        height: Float = 0.24f,
        spacing: Float = 0.055f,
        spreadDeg: Float = 7.5f,
        arcDrop: Float = 0.10f,
    ): List<Pose> = List(count) { i ->
        val k = i - (count - 1) / 2f
        val deg = k * spreadDeg
        val rad = (deg * PI / 180f).toFloat()
        Pose(
            x = centerX + k * spacing,
            y = centerY + arcDrop * (1f - cos(rad)),
            width = width,
            height = height,
            rotation = deg,
        )
    }

    /** 网格（九宫格抽奖 / 图片墙）。cellW/cellH 是归一化视宽 / 视高。 */
    fun grid(
        count: Int,
        columns: Int = 6,
        centerX: Float = 0.5f,
        centerY: Float = 0.5f,
        spanX: Float = 0.86f,
        spanY: Float = 0.72f,
        cellW: Float = 0.11f,
        cellH: Float = 0.13f,
    ): List<Pose> {
        val rows = ((count + columns - 1) / columns).coerceAtLeast(1)
        return List(count) { i ->
            val col = i % columns
            val row = i / columns
            Pose(
                x = centerX - spanX / 2f + spanX * (col + 0.5f) / columns,
                y = centerY - spanY / 2f + spanY * (row + 0.5f) / rows,
                width = cellW,
                height = cellH,
            )
        }
    }

    /** 一摞牌：每张微偏移 + 微旋转，用于「牌叠 → 展环」的起点。 */
    fun stack(
        count: Int,
        centerX: Float = 0.5f,
        centerY: Float = 0.55f,
        width: Float = 0.13f,
        height: Float = 0.23f,
        offsetX: Float = 0.0018f,
        offsetY: Float = -0.0014f,
        rotationStep: Float = 0.35f,
    ): List<Pose> = List(count) { i ->
        val k = i - (count - 1) / 2f
        Pose(
            x = centerX + k * offsetX,
            y = centerY + k * offsetY,
            width = width,
            height = height,
            rotation = k * rotationStep,
        )
    }

    /** 伪随机散落（贴纸雨 / 碎片）。同一 seed 结果稳定。 */
    fun scatter(
        count: Int,
        seed: Long = 1L,
        centerX: Float = 0.5f,
        centerY: Float = 0.5f,
        spanX: Float = 0.88f,
        spanY: Float = 0.78f,
        width: Float = 0.08f,
        height: Float = 0.11f,
    ): List<Pose> {
        val rnd = java.util.Random(seed)
        return List(count) {
            Pose(
                x = centerX + (rnd.nextFloat() - 0.5f) * spanX,
                y = centerY + (rnd.nextFloat() - 0.5f) * spanY,
                width = width,
                height = height,
                rotation = (rnd.nextFloat() - 0.5f) * 60f,
                scale = 0.7f + rnd.nextFloat() * 0.6f,
            )
        }
    }

    /** 环形（转盘 / 抽奖轮）。 */
    fun arc(
        count: Int,
        centerX: Float = 0.5f,
        centerY: Float = 0.5f,
        radiusX: Float = 0.34f,
        radiusY: Float = 0.30f,
        width: Float = 0.09f,
        height: Float = 0.14f,
    ): List<Pose> = List(count) { i ->
        val a = (i.toFloat() / count) * 2f * PI.toFloat() - PI.toFloat() / 2f
        Pose(
            x = centerX + radiusX * cos(a),
            y = centerY + radiusY * sin(a),
            width = width,
            height = height,
            rotation = (a * 180f / PI.toFloat()) + 90f,
        )
    }
}
