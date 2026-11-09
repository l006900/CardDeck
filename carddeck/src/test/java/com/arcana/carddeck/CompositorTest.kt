package com.arcana.carddeck

import com.arcana.gfx.GpuStats
import com.arcana.gfx.RenderPolicy
import com.arcana.gfx.ShaderPass
import com.arcana.gfx.ShaderPasses
import com.arcana.gfx.Tile
import com.arcana.gfx.TileSpec
import com.arcana.gfx.Tiles
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val MINIMAL_GLSL = """
precision mediump float;
uniform sampler2D uTexture;
uniform vec2 uTexel;
uniform vec4 uParams;
uniform float uTime;
varying vec2 vUv;
void main() { gl_FragColor = texture2D(uTexture, vUv); }
"""

/** 合成管线的纯 JVM 部分：坐标换算、布局、shader 契约、帧策略、指标口径。 */
class CompositorTest {

    @Test
    fun tileViewportFlipsY() {
        // Tile 是左上原点，GL viewport 是左下原点：顶部一格应落在 py = H/2。
        assertArrayEquals(intArrayOf(0, 400, 500, 400), Tile(0f, 0f, 0.5f, 0.5f).toViewport(1000, 800))
        assertArrayEquals(intArrayOf(500, 0, 500, 400), Tile(0.5f, 0.5f, 0.5f, 0.5f).toViewport(1000, 800))
    }

    @Test
    fun gridTilesStayInsideAndDoNotOverlap() {
        val tiles = Tiles.grid(7, columns = 3)
        assertEquals(7, tiles.size)
        for (t in tiles) {
            assertTrue(t.x >= 0f && t.y >= 0f)
            assertTrue(t.x + t.width <= 1.0001f)
            assertTrue(t.y + t.height <= 1.0001f)
        }
        for (i in 0 until tiles.size - 1) {
            if (tiles[i].y == tiles[i + 1].y) {
                assertTrue(
                    "同行不能重叠: $i",
                    tiles[i].x + tiles[i].width <= tiles[i + 1].x + 0.0001f,
                )
            }
        }
    }

    @Test
    fun columnsAndPipLayoutsAreValid() {
        val columns = Tiles.columns(3)
        assertEquals(3, columns.size)
        assertEquals(columns[0].height, columns[2].height, 0.0001f)
        val pip = Tiles.pictureInPicture()
        assertEquals(2, pip.size)
        assertTrue("小窗应压在主画面右下", pip[1].x > 0.5f && pip[1].y > 0.5f)
    }

    @Test
    fun invalidTileAndCropAreRejected() {
        expectIllegalArgument { Tile(0.9f, 0f, 0.5f, 0.5f) }
        expectIllegalArgument { TileSpec("a", Tiles.full(), floatArrayOf(1f, 0f, 0f, 1f)) }
        expectIllegalArgument { TileSpec("a", Tiles.full(), floatArrayOf(0f, 0f, 1f)) }
    }

    @Test
    fun shaderPassRequiresFourParamsAndGlslContract() {
        expectIllegalArgument { ShaderPass("bad", MINIMAL_GLSL, floatArrayOf(1f)) }
        expectIllegalArgument { ShaderPass("bad", "precision mediump float; void main(){}") }
        val custom = ShaderPasses.custom("mine", MINIMAL_GLSL, 1f, 2f, 3f, 4f)
        assertArrayEquals(floatArrayOf(1f, 2f, 3f, 4f), custom.params, 0f)
    }

    @Test
    fun blurPresetIsSeparable() {
        val passes = ShaderPasses.blur(3f)
        assertEquals(2, passes.size)
        assertEquals(3f, passes[0].params[0], 0f)
        assertEquals(0f, passes[0].params[1], 0f)  // 横向
        assertEquals(1f, passes[1].params[1], 0f)  // 纵向
        assertEquals(passes[0].fragment, passes[1].fragment) // 同一 program 会被编译缓存复用
    }

    @Test
    fun renderPolicyFrameIntervals() {
        assertEquals(33_333_333L, RenderPolicy(maxFps = 30).frameIntervalNs())
        assertEquals(16_666_666L, RenderPolicy(maxFps = 60).frameIntervalNs())
        expectIllegalArgument { RenderPolicy(maxFps = 0) }
        expectIllegalArgument { RenderPolicy(maxFps = 121) }
    }

    @Test
    fun statsSummaryReportsRealUnits() {
        val stats = GpuStats(
            fps = 30f, cpuFrameMs = 1.5f, drawCalls = 12, gpuPasses = 5,
            textureBytes = 2L * 1024 * 1024, latencyP50Ms = 2f, latencyP95Ms = 6f,
        )
        val text = stats.summary()
        assertTrue(text.contains("30.0 fps"))
        assertTrue(text.contains("12 draws"))
        assertTrue(text.contains("5 passes"))
        assertTrue(text.contains("2.0 MB"))
        assertTrue(text.contains("p50 2.0"))
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
