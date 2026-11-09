package com.arcana.carddeck

import com.arcana.gfx.GpuStats
import com.arcana.gfx.PresentMode
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

    /* ---- 帧驱动门控：出场节拍与内容代次解耦，锁住四次踩坑后的正确行为 ---- */

    private fun draw(
        pending: Boolean = false,
        dirty: Boolean = false,
        animating: Boolean = false,
        selfProducing: Boolean = false,
        externalPresent: Boolean = false,
        intervalOk: Boolean = true,
    ) = RenderPolicy.shouldDraw(
        pending, dirty, animating, selfProducing, externalPresent, intervalOk,
    )

    @Test
    fun externalSourcePresentsEveryVsync() {
        // 视频 / 摄像头布局：出场恒为屏幕刷新率。
        // 让「新帧到达」等 vsync 槽位会因源周期与槽位周期相位漂移，
        // 退化成 33ms / 50ms 交替（就是「视频也卡」），所以有外部源时一律放行。
        assertTrue(draw(externalPresent = true, intervalOk = true))
        assertTrue(draw(externalPresent = true, intervalOk = false))
        assertTrue(draw(pending = true, externalPresent = true, intervalOk = false))
    }

    @Test
    fun unchangedContentIsNeverRedrawn() {
        // 「渲染出一帧，然后一直插入」的根因：内容没变还在重画旧纹理。
        // 纯程序化布局里无新帧、无变化、无动画、无自产源 → 不画（静态页零空转）。
        assertFalse(draw(intervalOk = true))
        assertFalse(draw(intervalOk = false))
    }

    @Test
    fun animatedContentObeysFpsCap() {
        // uTime shader / CPU 自产纹理：内容每帧都变，但必须按 vsync 槽位节流。
        assertTrue(draw(animating = true, intervalOk = true))
        assertFalse(draw(animating = true, intervalOk = false))
        assertTrue(draw(selfProducing = true, intervalOk = true))
        assertFalse(draw(selfProducing = true, intervalOk = false))
    }

    @Test
    fun structureChangeAppliesImmediately() {
        // 布局 / 策略变化要立刻生效，不受 fps 上限拖到下一轮。
        assertTrue(draw(dirty = true, intervalOk = false))
    }

    /* ---- 上屏模式：不合成也必须重贴一次，否则 swap 会把旧缓冲翻上来 ---- */

    private fun mode(
        pending: Boolean = false,
        dirty: Boolean = false,
        animating: Boolean = false,
        selfProducing: Boolean = false,
        externalPresent: Boolean = false,
        intervalOk: Boolean = true,
        needsRepaint: Boolean = false,
    ) = RenderPolicy.presentMode(
        pending, dirty, animating, selfProducing, externalPresent, intervalOk, needsRepaint,
    )

    @Test
    fun throttledFrameStillRepaintsInsteadOfSkipping() {
        // 30fps 卡帧的真凶：节流跳过的回调里 GLSurfaceView 照样 swap，
        // 双缓冲把「两帧以前」的旧画面翻上来 → 录屏看到「动 动 停 停」。
        // 所以「不合成 + 后备缓冲是旧的」必须是 REPEAT，不能是 IDLE。
        assertEquals(PresentMode.REPEAT, mode(selfProducing = true, intervalOk = false, needsRepaint = true))
        assertEquals(PresentMode.DRAW, mode(selfProducing = true, intervalOk = true, needsRepaint = true))
        // 重贴一次后两个缓冲一致，再跳过就安全了（省电、静态页仍然 0 帧）。
        assertEquals(PresentMode.IDLE, mode(selfProducing = true, intervalOk = false, needsRepaint = false))
    }

    @Test
    fun staticPageSettlesToIdleAfterOneRepaint() {
        // 内容不再变化：先重贴一次把两块缓冲抹平，之后彻底不画。
        assertEquals(PresentMode.DRAW, mode(dirty = true, needsRepaint = true))
        assertEquals(PresentMode.REPEAT, mode(needsRepaint = true))
        assertEquals(PresentMode.IDLE, mode(needsRepaint = false))
    }

    @Test
    fun externalSourceNeverFallsBackToRepeat() {
        // 视频 / 摄像头每个回调都 DRAW，压根走不到 REPEAT。
        assertEquals(PresentMode.DRAW, mode(externalPresent = true, intervalOk = false, needsRepaint = true))
        assertEquals(PresentMode.DRAW, mode(externalPresent = true, intervalOk = true, needsRepaint = false))
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
