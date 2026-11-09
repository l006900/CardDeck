package com.arcana.carddeck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 抽象层的纯 JVM 单测：布局生成器、命中（含旋转）、四角点、脏标记、快照。
 * 不碰 Android / GLES，所以能在本地 JVM 上跑，不需要真机或 Robolectric。
 */
class SceneTest {

    private fun sceneOf(vararg poses: Pose) = Scene().apply {
        submit(poses.mapIndexed { i, p -> Node("n$i", p, "tex") })
    }

    @Test
    fun `fan 中间居中 两侧对称 角度递增`() {
        val poses = Layouts.fan(5, centerX = 0.5f, centerY = 0.5f, spacing = 0.1f, spreadDeg = 10f)
        assertEquals(5, poses.size)
        assertEquals(0.5f, poses[2].x, 1e-5f)
        assertEquals(0.5f, poses[2].y, 1e-5f)
        assertEquals(0f, poses[2].rotation, 1e-5f)
        assertTrue("中间应高于两侧", poses[0].y > poses[2].y)
        assertTrue("左牌顶朝左（负角）", poses[0].rotation < 0f)
        assertTrue("右牌顶朝右（正角）", poses[4].rotation > 0f)
        assertEquals(poses[0].x, 1f - poses[4].x, 1e-5f)
    }

    @Test
    fun `grid scatter arc 全部落在归一化视口内且数量正确`() {
        val count = 200
        for (poses in listOf(
            Layouts.grid(count, columns = 16),
            Layouts.scatter(count, seed = 3L),
            Layouts.arc(count),
            Layouts.stack(count),
        )) {
            assertEquals(count, poses.size)
            for (p in poses) {
                assertTrue("x 应归一化: ${p.x}", p.x in -0.2f..1.2f)
                assertTrue("y 应归一化: ${p.y}", p.y in -0.2f..1.2f)
                assertTrue("width 应为正", p.width > 0f)
                assertTrue("height 应为正", p.height > 0f)
            }
        }
    }

    @Test
    fun `hitTest 命中中心 返回四角点`() {
        val scene = sceneOf(Pose(x = 0.5f, y = 0.5f, width = 0.2f, height = 0.2f))
        val pick = scene.hitTest(500f, 500f, 1000f, 1000f)
        assertNotNull(pick)
        assertEquals("n0", pick!!.id)
        assertEquals(0, pick.index)
        assertEquals(8, pick.corners.size)
        // 未旋转的 200×200 牌，四角点应精确落在 (±100, ±100) 偏移上
        assertEquals(400f, pick.corners[0], 1e-3f)
        assertEquals(400f, pick.corners[1], 1e-3f)
        assertEquals(600f, pick.corners[2], 1e-3f)
        assertEquals(400f, pick.corners[6], 1e-3f) // 左下角 x
        assertEquals(600f, pick.corners[7], 1e-3f) // 左下角 y
        assertNull(scene.hitTest(50f, 50f, 1000f, 1000f))
    }

    @Test
    fun `hitTest 考虑旋转 旋转后角点不再是轴对齐`() {
        val scene = sceneOf(Pose(x = 0.5f, y = 0.5f, width = 0.2f, height = 0.2f, rotation = 45f))
        val pick = scene.hitTest(500f, 500f, 1000f, 1000f)
        assertNotNull(pick)
        val c = pick!!.corners
        // 45° 旋转后，左上角的 x 应偏离 400（不再是轴对齐矩形）
        assertTrue("四角点应体现旋转: ${c[0]}", abs(c[0] - 400f) > 20f)
    }

    @Test
    fun `绘制顺序即 z 序 后加入的先被命中`() {
        val p = Pose(x = 0.5f, y = 0.5f, width = 0.3f, height = 0.3f)
        val scene = sceneOf(p, p)
        assertEquals("n1", scene.hitTest(500f, 500f, 1000f, 1000f)!!.id)
    }

    @Test
    fun `submit update motion 都会置脏 consumeDirty 只返回一次`() {
        val scene = Scene()
        assertTrue(scene.dirty)
        assertTrue(scene.consumeDirty())
        assertFalse(scene.consumeDirty())

        scene.submit(listOf(Node("a", Pose(0.5f, 0.5f), "t")))
        assertTrue(scene.consumeDirty())
        assertFalse(scene.consumeDirty())

        scene.update { it.add(Node("b", Pose(0.2f, 0.2f), "t")) }
        assertEquals(2, scene.size)
        assertTrue(scene.consumeDirty())

        scene.motion = Motion { _, _, base -> base }
        assertTrue(scene.consumeDirty())
        assertFalse(scene.consumeDirty())
    }

    @Test
    fun `snapshot 返回当前节点且 burst 只消费一次`() {
        val scene = Scene()
        scene.submit(listOf(Node("a", Pose(0.5f, 0.5f), "t")))
        val snap = scene.snapshot()
        assertEquals(1, snap.size)
        assertEquals("a", snap[0].id)

        scene.burst(0.3f, 0.7f)
        assertNotNull(scene.consumeBurst())
        assertNull(scene.consumeBurst())
    }

    @Test
    fun `Effects 与 Pose 默认值自洽`() {
        val e = Effects()
        assertEquals(1f, e.foil, 1e-6f)
        assertEquals(0f, e.halo, 1e-6f)
        assertEquals(0f, Effects.None.foil, 1e-6f)
        val p = Pose(0.3f, 0.4f)
        val q = p.copyWith(rotation = 20f)
        assertEquals(20f, q.rotation, 1e-6f)
        assertEquals(p.x, q.x, 1e-6f)
        assertEquals(p.width, q.width, 1e-6f)
    }
}
