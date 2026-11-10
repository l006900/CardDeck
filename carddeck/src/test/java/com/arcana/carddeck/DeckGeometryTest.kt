package com.arcana.carddeck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 钉住牌堆环的**排布规则**（初版：78 张绕整圈，半径 min(宽×1.2, 460dp)，圆心在控件下方）。
 *
 * 每一条不变量对应一次「画面难看」的返工，必须永远成立：
 *  - 半径公式与圆心位置（顶部露出一段弧，不被切边）；
 *  - 选中下标含负角 / 环绕；
 *  - 拖动跟手、吸附落点对齐牌位；
 *  - 命中测试只在环带上生效、点空白处不抽牌；
 *  - `revealRect` 与 `centerOf` 完全一致（过场从点中的那张起飞）。
 */
class DeckGeometryTest {

    private val density = 3f
    private val width = 1080f
    private val height = 570f
    private val cardW = DeckGeometry.CARD_WIDTH_DP * density
    private val radiusCap = DeckGeometry.RADIUS_CAP_DP * density

    private fun layout(w: Float = width, h: Float = height, count: Int = 78) =
        DeckGeometry.layout(w, h, cardW, radiusCap, count)

    @Test
    fun `半径取 min 宽乘12 与 半径上限 且 步长等于 360 除以牌数`() {
        val l = layout()
        assertEquals(min(width * 1.2f, radiusCap), l.radius, 0.001f)
        assertEquals(360f / 78f, l.stepAngle, 0.0001f)
        val few = layout(count = 12)
        assertEquals(360f / 12f, few.stepAngle, 0.0001f)
    }

    @Test
    fun `0 张牌不崩 且 selectedIndex 返回 -1`() {
        val empty = layout(count = 0)
        assertEquals(-1, DeckGeometry.selectedIndex(30f, empty.stepAngle, 0))
        assertTrue(DeckGeometry.visibleIndices(empty, 0f).isEmpty())
        assertNull(DeckGeometry.hitTestIndex(empty, 0f, 0f, 0f))
    }

    @Test
    fun `圆心落在控件下方 居中牌落在中上部 两侧向下散开`() {
        val l = layout()
        // 圆心 y = radius + cardH/2 + height/6，应在控件下方（远大于 height）。
        assertTrue("centerY=$l.centerY 应在控件下方", l.centerY > l.height * 0.6f)
        // 居中那张牌（rot=0, index=0）应当可见、在控件中上部（∩ 拱顶）。
        val mid = DeckGeometry.centerOf(l, 0f, 0)
        assertTrue("中牌上沿出界 ${mid.y}", mid.y - l.cardH / 2f > 0f)
        assertTrue("中牌下沿出界", mid.y + l.cardH / 2f < l.height)
        assertTrue("中牌应偏上（拱顶）", mid.y < l.height * 0.5f)
    }

    @Test
    fun `选中下标含负角与环绕`() {
        val step = 360f / 78f
        for (rot in listOf(0f, step, -step, step * 2.4f, -step * 19.7f, 1080f, -1080f)) {
            val idx = DeckGeometry.selectedIndex(rot, step, 78)
            assertTrue("rot=$rot idx=$idx", idx in 0 until 78)
        }
        assertEquals(0, DeckGeometry.selectedIndex(0f, step, 78))
        assertEquals(1, DeckGeometry.selectedIndex(-step, step, 78))
        assertEquals(77, DeckGeometry.selectedIndex(step, step, 78))
    }

    @Test
    fun `吸附目标永远是步长的整数倍 且离当前角最近`() {
        val step = 360f / 78f
        for (rot in listOf(0f, 1.2f, 2.3f, -1.9f, 17.77f, -33.3f)) {
            val target = DeckGeometry.snapTarget(rot, step)
            assertEquals(target / step, (target / step).roundToInt().toFloat(), 0.0001f)
            assertTrue(abs(target - rot) <= step / 2f + 0.0001f)
        }
    }

    @Test
    fun `拖动跟手 手指划过一个牌距就转过一张牌`() {
        val l = layout()
        val oneCardDx = l.radius * (l.stepAngle * PI.toFloat() / 180f)
        assertEquals(l.stepAngle, DeckGeometry.rotationFromDrag(0f, oneCardDx, l.radius), 0.01f)
        assertEquals(-l.stepAngle, DeckGeometry.rotationFromDrag(0f, -oneCardDx, l.radius), 0.01f)
        assertEquals(12.5f, DeckGeometry.rotationFromDrag(12.5f, 0f, l.radius), 0.0001f)
    }

    @Test
    fun `点每张可见牌的牌心都命中它自己`() {
        val l = layout()
        for (rot in listOf(0f, 5.5f, -22.2f)) {
            for (i in DeckGeometry.visibleIndices(l, rot)) {
                val p = DeckGeometry.centerOf(l, rot, i)
                assertEquals("rot=$rot i=$i", i, DeckGeometry.hitTestIndex(l, rot, p.x, p.y))
            }
        }
    }

    @Test
    fun `环带之外的点命中为空`() {
        val l = layout()
        // 圆心正下方一张牌高以外：完全在环带外。
        val farY = l.centerY + l.radius + l.cardH * 1.2f
        assertNull(DeckGeometry.hitTestIndex(l, 0f, l.centerX, farY))
        // 圆心附近（环带内侧）点不到牌。
        assertNull(DeckGeometry.hitTestIndex(l, 0f, l.centerX, l.centerY))
    }

    @Test
    fun `revealRect 与 centerOf 一致 且 选中那张放大`() {
        val l = layout()
        val originX = 100f
        val originY = 250f
        val idx = DeckGeometry.selectedIndex(0f, l.stepAngle, 78)
        val p = DeckGeometry.centerOf(l, 0f, idx)
        val r = DeckGeometry.revealRect(l, 0f, idx, originX, originY)!!
        assertEquals(originX + p.x, r.cx, 0.01f)
        assertEquals(originY + p.y, r.cy, 0.01f)
        assertEquals(l.cardW * DeckGeometry.SELECTED_SCALE, r.w, 0.01f)
        assertEquals(l.cardH * DeckGeometry.SELECTED_SCALE, r.h, 0.01f)
        assertEquals(DeckGeometry.angleOf(l, 0f, idx), r.rot, 0.0001f)
    }

    @Test
    fun `角度归一化`() {
        assertEquals(0f, DeckGeometry.norm360(360f), 0.0001f)
        assertEquals(350f, DeckGeometry.norm360(-10f), 0.0001f)
        assertEquals(-10f, DeckGeometry.norm180(350f), 0.0001f)
        assertEquals(170f, DeckGeometry.norm180(170f), 0.0001f)
        assertEquals(-170f, DeckGeometry.norm180(190f), 0.0001f)
    }
}
