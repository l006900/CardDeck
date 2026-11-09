package com.arcana.gfx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 新增特效 / 混合 / 转场 的契约测试（纯 JVM，不依赖 GLES 上下文）。
 *
 * 真正上屏渲染由真机验证（见 CompositeDemo 的「电影感/赛博朋克/混合/转场」入口）；
 * 这里只保证工厂产出正确的 fragment 引用、参数映射，以及 secondarySource 的契约约束。
 */
class ShaderPassesTest {

    @Test
    fun newFiltersReferenceCorrectFragmentAndHaveFourParams() {
        val cases = listOf(
            ShaderPasses.invert() to GfxShaders.INVERT,
            ShaderPasses.sepia() to GfxShaders.SEPIA,
            ShaderPasses.posterize() to GfxShaders.POSTERIZE,
            ShaderPasses.pixelate() to GfxShaders.PIXELATE,
            ShaderPasses.sharpen() to GfxShaders.SHARPEN,
            ShaderPasses.emboss() to GfxShaders.EMBOSS,
            ShaderPasses.duotone() to GfxShaders.DUOTONE,
            ShaderPasses.hueRotate() to GfxShaders.HUE_ROTATE,
            ShaderPasses.gamma() to GfxShaders.GAMMA,
            ShaderPasses.nightVision() to GfxShaders.NIGHTVISION,
            ShaderPasses.oldFilm() to GfxShaders.OLD_FILM,
            ShaderPasses.kaleidoscope() to GfxShaders.KALEIDOSCOPE,
            ShaderPasses.lensDistort() to GfxShaders.LENS_DISTORT,
        )
        for ((pass, frag) in cases) {
            assertEquals(frag, pass.fragment)
            assertEquals(4, pass.params.size)
            assertTrue(pass.secondarySource == null)
        }
    }

    @Test
    fun blendWiresSecondarySourceAndMode() {
        val p = ShaderPasses.blend("proc", amount = 0.5f, mode = 2)
        assertEquals("proc", p.secondarySource)
        assertEquals(GfxShaders.BLEND, p.fragment)
        assertEquals(0.5f, p.params[0])
        assertEquals(2f, p.params[2]) // mode 经 uParams.z 传入
        assertTrue(p.fragment.contains("uTexture2"))
    }

    @Test
    fun crossfadeWiresSecondarySourceAndDuration() {
        val p = ShaderPasses.crossfade("proc", durationSec = 4f)
        assertEquals("proc", p.secondarySource)
        assertEquals(GfxShaders.TRANSITION, p.fragment)
        assertEquals(4f, p.params[1]) // duration 经 uParams.y 传入
        assertTrue(p.fragment.contains("uTexture2"))
    }

    @Test
    fun presetsReturnNonEmptyChains() {
        assertTrue(ShaderPasses.cinematic().isNotEmpty())
        assertTrue(ShaderPasses.retro().isNotEmpty())
        assertTrue(ShaderPasses.cyberpunk().isNotEmpty())
        assertTrue(ShaderPasses.dream().isNotEmpty())
        assertTrue(ShaderPasses.beauty().isNotEmpty())
        assertTrue(ShaderPasses.glitch().isNotEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun secondarySourceWithoutUTexture2Throws() {
        // 声明了 secondarySource 但 fragment 没写 uTexture2：构造时必须报错。
        ShaderPass(
            "bad", GfxShaders.GRAYSCALE,
            secondarySource = "other",
        )
    }

    @Test
    fun customWithSecondarySourceAndUTexture2IsAllowed() {
        val p = ShaderPass(
            "ok", GfxShaders.BLEND,
            secondarySource = "other",
        )
        assertNotNull(p)
        assertEquals("other", p.secondarySource)
    }
}
