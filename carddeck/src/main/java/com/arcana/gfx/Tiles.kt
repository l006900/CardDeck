package com.arcana.gfx

import kotlin.math.ceil

/**
 * 一块画面在合成目标上的位置，**归一化 [0,1]、左上原点**。
 *
 * 与 GL viewport 的「左下原点 + 像素」换算集中在 [toViewport] 一处，避免每个 pass
 * 各写一遍 y 翻转导致某一路上下颠倒。
 */
data class Tile(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
) {
    init {
        require(width > 0f && height > 0f) { "Tile 宽高必须为正" }
        require(x >= -0.0001f && y >= -0.0001f) { "Tile 原点不能为负" }
        require(x + width <= 1.0001f && y + height <= 1.0001f) { "Tile 超出合成目标" }
    }

    /** GL viewport：原点在左下，所以 y 要翻一次。返回 (px, py, pw, ph)。 */
    fun toViewport(viewW: Int, viewH: Int): IntArray {
        val px = (x * viewW + 0.5f).toInt()
        val pw = (width * viewW + 0.5f).toInt().coerceAtLeast(1)
        val ph = (height * viewH + 0.5f).toInt().coerceAtLeast(1)
        val py = ((1f - y - height) * viewH + 0.5f).toInt().coerceAtLeast(0)
        return intArrayOf(px, py, pw.coerceAtMost(viewW), ph.coerceAtMost(viewH))
    }
}

/**
 * 一路源画到哪、取它的哪一块。
 *
 * [crop] 是 uv 子区域 `(u0, v0, u1, v1)`，用来做「同一路视频的不同区域拼成多画面」
 * 或画中画的放大局部；[flipY] 用于纠正不同解码器输出的 y 方向差异。
 */
data class TileSpec(
    val source: String,
    val tile: Tile,
    val crop: FloatArray = floatArrayOf(0f, 0f, 1f, 1f),
    val flipY: Boolean = false,
    /**
     * 这一路画面**独有**的后处理链，在拼进合成目标之前先跑。
     *
     * 全局 [GpuCompositor.passes] 作用于最终画面；这里的链只作用于本路，
     * 所以「4 路监控画面，一路灰度、一路边缘、一路美颜、一路原画」是原生能力，
     * 不需要调用方拆成 4 个 GLSurfaceView。
     */
    val passes: List<ShaderPass> = emptyList(),
) {
    init {
        require(crop.size == 4) { "crop 必须是 (u0, v0, u1, v1)" }
        require(crop[2] > crop[0] && crop[3] > crop[1]) { "crop 的 u1/v1 必须大于 u0/v0" }
    }
}

/** 常见多画面布局，返回的 Tile 列表与 [GpuCompositor.tiles] 一一对应。 */
object Tiles {

    fun full(gap: Float = 0f): Tile = Tile(gap, gap, 1f - 2 * gap, 1f - 2 * gap)

    /** 规则宫格：count 不足一整行时最后一行保持同样的列宽，右侧留白。 */
    fun grid(count: Int, columns: Int = 2, gap: Float = 0.012f): List<Tile> {
        require(count > 0 && columns > 0)
        val rows = ceil(count.toFloat() / columns).toInt()
        val cw = (1f - gap * (columns + 1)) / columns
        val ch = (1f - gap * (rows + 1)) / rows
        return (0 until count).map { i ->
            val c = i % columns
            val r = i / columns
            Tile(gap + c * (cw + gap), gap + r * (ch + gap), cw, ch)
        }
    }

    /** 横向等分（竖屏拼接常用）。 */
    fun columns(count: Int, gap: Float = 0.01f): List<Tile> {
        require(count > 0)
        val cw = (1f - gap * (count + 1)) / count
        return (0 until count).map { i -> Tile(gap + i * (cw + gap), gap, cw, 1f - 2 * gap) }
    }

    /** 画中画：第一个是全屏底图，第二个是右下角小窗。 */
    fun pictureInPicture(
        gap: Float = 0.012f,
        smallW: Float = 0.28f,
        smallH: Float = 0.28f,
    ): List<Tile> = listOf(
        full(gap),
        Tile(1f - smallW - gap * 2, 1f - smallH - gap * 2, smallW, smallH),
    )

    /**
     * 焦点布局：第 0 路占左侧大画面，其余各路在右侧竖向堆叠成缩略图条。
     * 适合「主画面 + 多路预览」的监控 / 导播场景。
     */
    fun focus(count: Int, bigFrac: Float = 0.66f, gap: Float = 0.012f): List<Tile> {
        require(count > 0)
        if (count == 1) return listOf(full(gap))
        val g = gap
        val leftW = (1f - 3f * g) * bigFrac
        val leftH = 1f - 2f * g
        val tiles = ArrayList<Tile>(count)
        tiles.add(Tile(g, g, leftW, leftH))
        val rightX = g + leftW + g
        val rightW = 1f - 2f * g - leftW - g
        val n = count - 1
        val rh = (leftH - (n - 1) * g) / n
        for (i in 0 until n) {
            val y = g + i * (rh + g)
            tiles.add(Tile(rightX, y, rightW, rh))
        }
        return tiles
    }

    /**
     * 侧栏布局：第 0 路占右侧大画面，其余各路在左侧竖向堆叠成缩略图栏。
     * 与 [focus] 左右镜像，适合把控制/列表放左侧、主视图放右侧的界面。
     */
    fun sidebar(count: Int, sideFrac: Float = 0.28f, gap: Float = 0.012f): List<Tile> {
        require(count > 0)
        if (count == 1) return listOf(full(gap))
        val g = gap
        val sideW = (1f - 3f * g) * sideFrac
        val mainX = g + sideW + g
        val mainW = 1f - 2f * g - sideW - g
        val mainH = 1f - 2f * g
        val tiles = ArrayList<Tile>(count)
        tiles.add(Tile(mainX, g, mainW, mainH))
        val n = count - 1
        val th = (1f - 2f * g - (n - 1) * g) / n
        for (i in 0 until n) {
            val y = g + i * (th + g)
            tiles.add(Tile(g, y, sideW, th))
        }
        return tiles
    }

    /** 直接用调用方给定的瓦片列表（自定义拼接形状）。 */
    fun custom(tiles: List<Tile>): List<Tile> = tiles
}
