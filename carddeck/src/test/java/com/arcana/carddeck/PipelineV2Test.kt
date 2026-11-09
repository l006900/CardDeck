package com.arcana.carddeck

import com.arcana.gfx.GpuStats
import com.arcana.gfx.RenderMode
import com.arcana.gfx.RenderPolicy
import com.arcana.gfx.ShaderPasses
import com.arcana.gfx.TileSpec
import com.arcana.gfx.Tiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 采集 / 编码 / 系统指标这一轮的纯 JVM 逻辑：预算选档、参数校验、HUD 口径。 */
class PipelineV2Test {

    @Test
    fun noBudgetKeepsRequestedScale() {
        assertEquals(1f, RenderPolicy.chooseScale(1080, 1400, 1f, 0L), 0f)
        assertEquals(0.5f, RenderPolicy.chooseScale(1080, 1400, 0.5f, 0L), 0f)
    }

    @Test
    fun generousBudgetStaysAtFullResolution() {
        assertEquals(
            1f,
            RenderPolicy.chooseScale(1080, 1400, 1f, 64L * 1024 * 1024),
            0f,
        )
    }

    @Test
    fun eightMegabyteBudgetDownscalesLikeOnDevice() {
        // 真机 HUD：预算 8MB 时 1080×1400 的 GL View 自动降到 scale 0.50 / 3.0 MB。
        assertEquals(
            0.5f,
            RenderPolicy.chooseScale(1080, 1400, 1f, 8L * 1024 * 1024),
            0f,
        )
    }

    @Test
    fun budgetNeverRaisesScaleAboveManualLimit() {
        assertEquals(
            0.35f,
            RenderPolicy.chooseScale(1080, 1400, 0.35f, 64L * 1024 * 1024),
            0f,
        )
        assertEquals(
            0.5f,
            RenderPolicy.chooseScale(1080, 1400, 0.5f, 8L * 1024 * 1024),
            0f,
        )
    }

    @Test
    fun tinyBudgetFallsBackToLowestStep() {
        assertEquals(
            RenderPolicy.SCALE_STEPS.last(),
            RenderPolicy.chooseScale(1080, 1400, 1f, 1L),
            0f,
        )
    }

    @Test
    fun policyValidatesNewFields() {
        expectIllegalArgument { RenderPolicy(resolutionScale = 0.2f) }
        expectIllegalArgument { RenderPolicy(resolutionScale = 1.1f) }
        expectIllegalArgument { RenderPolicy(maxTextureBytes = -1L) }
        assertEquals(0.5f, RenderPolicy(resolutionScale = 0.5f).resolutionScale, 0f)
    }

    @Test
    fun systemLineReportsUnavailableMetricsHonestly() {
        val stats = GpuStats(gpuFrameMs = -1f, powerMw = -1f, pssKb = 0)
        val line = stats.systemLine()
        assertTrue(line, line.contains("gpu n/a"))
        assertTrue(line, line.contains("power n/a"))
        assertTrue(line, line.contains("proc cpu 0%"))
    }

    @Test
    fun systemAndFlowLinesReportRealUnits() {
        val stats = GpuStats(
            gpuFrameMs = 3.61f, cpuPercent = 42f, pssKb = 83_149,
            graphicsPssKb = 4_096, nativeHeapKb = 24_000L, powerMw = 78f,
            jitterP95Ms = 6.5f, resolutionScale = 0.5f, encodedFrames = 135,
        )
        val system = stats.systemLine()
        assertTrue(system, system.contains("gpu 3.61 ms"))
        assertTrue(system, system.contains("proc cpu 42%"))
        assertTrue(system, system.contains("power 78 mW"))
        val flow = stats.flowLine()
        assertTrue(flow, flow.contains("jitter 6.5 ms"))
        assertTrue(flow, flow.contains("scale 0.50"))
        assertTrue(flow, flow.contains("enc 135"))
    }

    @Test
    fun perTilePassChainIsPreservedOnSpec() {
        val chain = listOf(ShaderPasses.grayscale(), ShaderPasses.sobel(1.2f))
        val spec = TileSpec("cam", Tiles.full(), passes = chain)
        assertEquals(2, spec.passes.size)
        assertEquals("cam", spec.source)
        assertEquals(emptyList<com.arcana.gfx.ShaderPass>(), TileSpec("cam", Tiles.full()).passes)
    }

    /* ---- 帧驱动门控：旧实现「先消费帧再门控」会吞帧 + 重复帧，这里锁住行为 ---- */

    private fun draw(
        pending: Boolean = false,
        dirty: Boolean = false,
        animating: Boolean = false,
        intervalOk: Boolean = true,
        mode: RenderMode = RenderMode.CONTINUOUS,
        frameDriven: Boolean = true,
    ) = RenderPolicy.shouldDraw(pending, dirty, animating, intervalOk, mode, frameDriven)

    @Test
    fun arrivingFrameIsDrawnEvenBeforeFpsInterval() {
        // 帧到达本身就是节拍，不能被 maxFps 拖到下一个回调（否则新帧被覆盖）。
        assertTrue(draw(pending = true, intervalOk = false))
        assertTrue(draw(pending = true, intervalOk = false, mode = RenderMode.ON_DEMAND))
    }

    @Test
    fun frameDrivenLayoutNeverRepeatsAFrame() {
        // 修复前 142/297 次绘制没有新帧，重复贴上一帧 = 肉眼可见的顿挫。
        assertFalse(draw(pending = false, intervalOk = true, frameDriven = true))
        assertFalse(draw(pending = false, intervalOk = true, mode = RenderMode.ON_DEMAND))
    }

    @Test
    fun proceduralLayoutStillDrawsAtFpsCap() {
        // 没有外部帧源时仍由 maxFps 匀速驱动，别把 CPU 动态纹理也停掉。
        assertTrue(draw(pending = false, intervalOk = true, frameDriven = false))
        assertFalse(draw(pending = false, intervalOk = false, frameDriven = false))
    }

    @Test
    fun dirtyAppliesImmediatelyButAnimationStillObeysFpsCap() {
        assertTrue(draw(dirty = true, intervalOk = false))
        assertFalse(draw(animating = true, intervalOk = false))
        assertTrue(draw(animating = true, intervalOk = true))
    }

    private inline fun expectIllegalArgument(block: () -> Unit) {
        var thrown = false
        try {
            block()
        } catch (e: IllegalArgumentException) {
            thrown = true
        }
        assertTrue("应抛 IllegalArgumentException", thrown)
    }
}
