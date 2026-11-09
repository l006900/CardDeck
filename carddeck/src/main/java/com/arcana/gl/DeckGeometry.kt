package com.arcana.gl

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 扇形牌堆的**几何真相** —— 纯 Kotlin、无 Android / GL 依赖，可在 JVM 单测里跑。
 *
 * 两端控件（Compose 的 CardWheel、OpenGL 的 GlCardWheel）必须给出同一个 `RevealRect`：
 * 翻牌过场是从用户点中那张牌的位置起飞的，几何差一点，过场就会从「旁边那张」起手。
 * 所以环心、步长、画序、命中、选中态全部集中在这里算，渲染层只负责把结果画出来。
 *
 * 坐标单位统一为 **px**（调用方已把 dp 乘过 density）；y 向下，与 Android 视图一致。
 * 公式与 Web `CardWheelDeck.vue` / Compose `CardWheel.kt` 逐条对齐，改动需两端同步。
 */
object DeckGeometry {

    /** 牌宽高比 3:5，与 720×1200 牌图同规格。 */
    const val CARD_ASPECT = 3f / 5f

    /** 环上单张牌宽（dp）。Web 手机端 `card-w = 68`。 */
    const val CARD_WIDTH_DP = 68f

    /** 半径上限（dp）。Web `radius-cap = 460`：再大可视弧会更平、更像一排。 */
    const val RADIUS_CAP_DP = 460f

    /** 停在正上方那张的放大倍数。略微放大（1.25f）：仍是牌堆里的一张，
     *  按扇面顺序正常层叠（被右侧邻牌盖住），只是比邻牌大一点 —— 「被选」≠「浮起」。
     *  选中由 [GlShaders.DECK_FRAGMENT] 的金边略提亮 + 这一档放大共同标识。 */
    const val SELECTED_SCALE = 1.25f

    /** 拖动阈值（dp），与 Compose 控件一致。 */
    const val DRAG_SLOP_DP = 4f

    /** 松手吸附时长（ms）。 */
    const val SNAP_MS = 350L

    /**
     * 一次布局的结果。所有值都是 px，`centerX/centerY` 是**环心**（在可视区下方）。
     */
    data class Layout(
        val width: Float,
        val height: Float,
        val cardW: Float,
        val cardH: Float,
        val radius: Float,
        val centerX: Float,
        /** 环心 y（px）。圆心落在控件**下方**，牌在圆心上方排成 ∩ 形拱。 */
        val centerY: Float,
        val stepAngle: Float,
        val count: Int,
    )

    /** 牌心坐标（px，相对控件自身）。 */
    data class Point(val x: Float, val y: Float)

    /** 视口（根坐标系）里的一张牌：牌心、尺寸、自身旋转角度。与 `RevealRect` 一一对应。 */
    data class Rect(
        val cx: Float,
        val cy: Float,
        val w: Float,
        val h: Float,
        val rot: Float,
    )

    /**
     * 环心在可视区坐标里的 y。**圆心落在控件下方**，牌排成圆心上方的那段弧 ——
     * 于是中间那张在控件中上部、两侧牌向下垂、向外散开成 ∩ 形拱（参考图的扇形手牌）。
     */
    fun stageCenterY(stageHeight: Float, radius: Float, cardWidth: Float): Float {
        val cardHeight = cardWidth / CARD_ASPECT
        return radius + cardHeight / 2f + stageHeight / 6f
    }

    fun layout(
        widthPx: Float,
        heightPx: Float,
        cardWidthPx: Float,
        radiusCapPx: Float,
        count: Int,
    ): Layout {
        val radius = min(widthPx * 1.2f, radiusCapPx)
        return Layout(
            width = widthPx,
            height = heightPx,
            cardW = cardWidthPx,
            cardH = cardWidthPx / CARD_ASPECT,
            radius = radius,
            centerX = widthPx / 2f,
            centerY = stageCenterY(heightPx, radius, cardWidthPx),
            stepAngle = if (count > 0) 360f / count else 360f,
            count = count,
        )
    }

    /** 第 [index] 张牌此刻的角度（度）。0° = 正上方，顺时针为正。 */
    fun angleOf(layout: Layout, rot: Float, index: Int): Float = rot + index * layout.stepAngle

    /** 第 [index] 张牌此刻的牌心（px，相对控件）。圆心在下方，牌落在圆心上方弧段（∩ 拱）。 */
    fun centerOf(layout: Layout, rot: Float, index: Int): Point {
        val rad = angleOf(layout, rot, index) * PI.toFloat() / 180f
        return Point(
            x = layout.centerX + layout.radius * sin(rad),
            y = layout.centerY - layout.radius * cos(rad),
        )
    }

    /**
     * 只画与可视区相交的牌 —— 78 张里有 60+ 张永远在裁剪区外。
     * 返回值已按画序排好（选中牌最后画、压在最上），与 Web `zIndexFor()` 同一套算法，
     * 否则相邻两张牌在交叠处会跳变。
     */
    fun visibleIndices(layout: Layout, rot: Float): List<Int> =
        (0 until layout.count)
            .map { i -> i to centerOf(layout, rot, i) }
            .filter { (_, p) ->
                p.y + layout.cardH > 0f && p.y - layout.cardH < layout.height &&
                    p.x + layout.cardW > 0f && p.x - layout.cardW < layout.width
            }
            .sortedBy { (i, _) -> norm360(i * layout.stepAngle + rot + 180f) }
            .map { (i, _) -> i }

    /** 当前停在正上方的那张（Web 的 `is-sel`）。 */
    fun selectedIndex(rot: Float, stepAngle: Float, count: Int): Int =
        if (count <= 0) -1 else (((-rot / stepAngle).roundToInt() % count) + count) % count

    /**
     * 点在哪张牌上（返回下标，null = 点在缝里）。
     *
     * 不做旋转矩形的精确命中：用环的几何反解，拿触点相对圆心的角度找最接近的那张，
     * 再要求触点落在环带内，避免点空白也抽牌。
     */
    fun hitTestIndex(layout: Layout, rot: Float, x: Float, y: Float): Int? {
        if (layout.count <= 0) return null
        val dx = x - layout.centerX
        val dy = y - layout.centerY
        val dist = sqrt(dx * dx + dy * dy)
        if (dist > layout.radius + layout.cardH || dist < layout.radius - layout.cardH) return null

        // 牌在圆心上方（∩ 拱）：0° = 正上方，顺时针为正 —— 与 centerOf 的 sin/cos 取法一致。
        val touchAngle = atan2(dx, -dy) * 180f / PI.toFloat()
        var best = -1
        var bestDiff = Float.MAX_VALUE
        for (i in 0 until layout.count) {
            val diff = abs(norm180(angleOf(layout, rot, i) - touchAngle))
            if (diff < bestDiff) {
                bestDiff = diff
                best = i
            }
        }
        return if (best >= 0 && bestDiff <= layout.stepAngle * 0.6f) best else null
    }

    /**
     * 命中那张牌此刻的几何（**根坐标系** px）。过场从这里起飞。
     * 与渲染用同一套 sin/cos 取法，否则过场会从旁边那张起手。
     */
    fun revealRect(
        layout: Layout,
        rot: Float,
        index: Int,
        originX: Float = 0f,
        originY: Float = 0f,
    ): Rect {
        val angle = angleOf(layout, rot, index)
        val rad = angle * PI.toFloat() / 180f
        val scale = if (index == selectedIndex(rot, layout.stepAngle, layout.count)) SELECTED_SCALE else 1f
        return Rect(
            cx = originX + layout.centerX + layout.radius * sin(rad),
            cy = originY + layout.centerY - layout.radius * cos(rad),
            w = layout.cardW * scale,
            h = layout.cardH * scale,
            rot = angle,
        )
    }

    /** 拖动距离（px）换成旋转角度：跟手，转过多少等于手指在半径上划过的弧长。 */
    fun rotationFromDrag(startRot: Float, totalDx: Float, radius: Float): Float =
        startRot + totalDx / radius * (180f / PI.toFloat())

    /** 松手吸附目标：最近的牌位。 */
    fun snapTarget(rot: Float, stepAngle: Float): Float =
        (rot / stepAngle).roundToInt() * stepAngle

    fun norm360(deg: Float): Float = ((deg % 360f) + 360f) % 360f

    fun norm180(deg: Float): Float {
        val v = norm360(deg)
        return if (v > 180f) v - 360f else v
    }
}
