package com.arcana.carddeck.gl.composite

import android.opengl.GLES20
import android.opengl.GLES30
import com.arcana.carddeck.gl.GlEngine
import com.arcana.gfx.FrameSource

/**
 * 把一个 [GlEngine] 包成库合成管线里的一路画面源。
 *
 * **引擎一行都不用改**，依据是 `core/gl` 里所有引擎的渲染代码都只调 `glViewport`、
 * 从不调 `glBindFramebuffer` —— 它们画进「当前绑定的帧缓冲」，从不假设那是窗口。
 * 所以这里把帧缓冲换成自己的 FBO，引擎的输出自然落在纹理上。
 *
 * ## 为什么要自己做 MSAA
 *
 * 原来引擎跑在 `GLSurfaceHost` 上，抗锯齿由 EGL 配置的 4× 多重采样提供（牌边在
 * 翻转/拖动时是斜线，没有它就是一把锯齿）。**FBO 默认没有多重采样**，直接搬进
 * 合成管线会立刻退化成锯齿边。所以这里自己建一套：
 * ```
 * 引擎 → MSAA FBO（renderbuffer, 4×）→ glBlitFramebuffer resolve → 普通纹理 → 合成器采样
 * ```
 * 只有 ES 3.0 上下文支持多采样 renderbuffer；ES 2.0 设备自动退化为「直接渲染到纹理」
 * （不崩、只是没抗锯齿），并在日志里说明，避免把「老机器画面糙」误判成 bug。
 *
 * ## 线程与状态恢复
 *
 * [update] 在合成器的 GL 线程里被调用，**调用时当前绑定的 FBO / viewport / 深度测试
 * 状态属于合成器**。引擎的 `onSurfaceCreated` 会 `glEnable(GL_DEPTH_TEST)`、设自己的
 * `glClearColor`，这些都会污染合成器的状态（表现为「后面的画面整片黑」或「某路被深度
 * 测试裁掉」）。所以进去前存、出来时逐个恢复，一个都不省。
 */
class GlEngineSource(
    private val engine: GlEngine,
    /** 是否启用多采样（调用方按 EGL 上下文版本决定）。 */
    private val useMsaa: Boolean = true,
    private val samples: Int = 4,
) : FrameSource {

    override val external: Boolean = false
    override val textureTarget: Int = GLES20.GL_TEXTURE_2D

    /**
     * 引擎每帧都在产新内容（星空粒子缓慢上升、翻面插值、拖动跟手），
     * 所以恒为 true：合成器据此按 [com.arcana.gfx.RenderPolicy.maxFps] 稳定出帧，
     * 而不是等外部推帧。这与原来 `RENDERMODE_CONTINUOUSLY` 的行为一致。
     */
    override val selfAnimating: Boolean = true

    /** 目标像素尺寸（由宿主在 GL 线程写入）。0 = 还没确定，update 直接跳过。 */
    @Volatile
    var requestedW: Int = 0
        private set

    @Volatile
    var requestedH: Int = 0
        private set

    private var resolveFbo = 0
    private var resolveTex = 0
    private var msaaFbo = 0
    private var msaaColorRb = 0
    private var msaaDepthRb = 0

    private var width = 0
    private var height = 0
    private var created = false
    private var engineSurfaceReady = false
    private var msaaActive = false

    /** 宿主在 GL 线程调用：记录新尺寸，真正的重建推迟到下一次 [update]。 */
    fun resize(w: Int, h: Int) {
        requestedW = w.coerceAtLeast(1)
        requestedH = h.coerceAtLeast(1)
    }

    override fun onGlCreated() {
        // **不能短路**：合成器在每次 surface 创建时都会调这里（页面回来、窗口尺寸变化、
        // 系统回收后重建都会），而且**不会先调 [onGlLost]**。所以「已创建就返回」是错的：
        // 新上下文里旧 FBO / 纹理 id 全部失效，继续往它们上面画，采样出来就是纯黑
        // （实测现象：fps 60、draws 2 都正常，屏幕全黑，且没有任何 GL 错误码）。
        //
        // 这里只把 id 置 0、**不调 glDelete***：那些 id 在新上下文里可能已被别的对象占用，
        // 删掉就是误删别人的纹理。真正的删除只在同一上下文内的 [buildTargets] / [releaseGl] 做。
        resolveFbo = 0
        resolveTex = 0
        msaaFbo = 0
        msaaColorRb = 0
        msaaDepthRb = 0
        width = 0
        height = 0
        msaaActive = false
        engineSurfaceReady = false
        // 引擎只建 program / 上传贴图，**不读 EGLConfig 参数**（已核对三个渲染器实现），
        // 所以这里传 null 是安全的；把 config 传进来反而要额外持有 EGLConfig 引用。
        engine.onGlCreated()
        created = true
    }

    override fun onGlLost() {
        // 上下文没了，GL 对象由驱动回收，这里只丢引用 —— 绝不碰 GLES。
        created = false
        engineSurfaceReady = false
        resolveFbo = 0
        resolveTex = 0
        msaaFbo = 0
        msaaColorRb = 0
        msaaDepthRb = 0
        width = 0
        height = 0
        msaaActive = false
    }

    override fun update(): Long {
        if (!created) return 0L
        val w = requestedW
        val h = requestedH
        if (w <= 0 || h <= 0) return 0L

        if (w != width || h != height || resolveTex == 0) {
            buildTargets(w, h)
            if (resolveTex == 0) return 0L
        }

        // ---- 存合成器的状态 ----
        val prevFbo = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, prevFbo, 0)
        val prevViewport = IntArray(4)
        GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, prevViewport, 0)
        val blendWasEnabled = GLES20.glIsEnabled(GLES20.GL_BLEND)

        // 引擎用自己的宽高建投影矩阵；只有尺寸变了才重建，避免每帧算一遍 frustum。
        if (!engineSurfaceReady) {
            engine.onGlResized(width, height)
            engineSurfaceReady = true
        }

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, if (msaaActive) msaaFbo else resolveFbo)
        GLES20.glViewport(0, 0, width, height)
        engine.onGlFrame()

        if (msaaActive) {
            // 多采样缓冲不能直接采样，必须 resolve 成普通纹理才能当合成源。
            GLES20.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, msaaFbo)
            GLES20.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, resolveFbo)
            GLES30.glBlitFramebuffer(
                0, 0, width, height,
                0, 0, width, height,
                GLES20.GL_COLOR_BUFFER_BIT, GLES20.GL_NEAREST,
            )
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, prevFbo[0])
        } else {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, prevFbo[0])
        }

        // ---- 还回合成器的状态 ----
        GLES20.glViewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3])
        // **深度测试必须关掉，不能"恢复原值"**：引擎的 onSurfaceCreated 会 glEnable(GL_DEPTH_TEST)，
        // 而合成器只在自己的 onSurfaceCreated 里 disable 一次、之后从不重设。若按进入时的值还原
        // （那时已经是 true），引擎留下的 enable 就会带回合成的后续所有 blit —— 表现为
        // 「fps 正常、draws 正常、GL 无错误，但屏幕全黑」，因为片元全被深度测试丢了。
        // 实测踩过这个坑，日志里 `depth=true` 就是它的指纹。
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        if (blendWasEnabled) GLES20.glEnable(GLES20.GL_BLEND)
        else GLES20.glDisable(GLES20.GL_BLEND)
        // 引擎的混合函数与合成器一致（SRC_ALPHA / ONE_MINUS_SRC_ALPHA），不必还原。

        return System.nanoTime()
    }

    override fun textureId(): Int = resolveTex

    override fun transform(out: FloatArray): FloatArray {
        android.opengl.Matrix.setIdentityM(out, 0)
        return out
    }

    override val contentWidth: Int get() = width
    override val contentHeight: Int get() = height
    override val contentRotationDeg: Int get() = 0

    override fun byteCount(): Long = width.toLong() * height * 4L

    /* ------------------------------------------------------------- 离屏目标 */

    private fun buildTargets(w: Int, h: Int) {
        releaseTargets()
        width = w
        height = h
        engineSurfaceReady = false

        // resolve 目标：一张普通 RGBA 纹理，合成器就是采样它。
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null,
        )
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)

        val fbo = IntArray(1)
        GLES20.glGenFramebuffers(1, fbo, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0])
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D, tex[0], 0,
        )
        val resolveOk = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) ==
            GLES20.GL_FRAMEBUFFER_COMPLETE
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        if (!resolveOk) {
            GLES20.glDeleteTextures(1, tex, 0)
            GLES20.glDeleteFramebuffers(1, fbo, 0)
            android.util.Log.e("GlEngineSource", "resolve FBO 不完整，本路画面将缺失")
            return
        }
        resolveTex = tex[0]
        resolveFbo = fbo[0]

        if (useMsaa && buildMsaa(w, h)) return

        // 退化路径：直接把内容渲染进 resolve 纹理。没有抗锯齿，但不崩。
        msaaActive = false
        android.util.Log.i("GlEngineSource", "按无 MSAA 渲染（${w}x$h）")
    }

    /** 建多采样缓冲区。失败返回 false，由调用方退化（老设备 / 驱动不支持）。 */
    private fun buildMsaa(w: Int, h: Int): Boolean {
        val rbs = IntArray(2)
        GLES20.glGenRenderbuffers(2, rbs, 0)
        GLES20.glBindRenderbuffer(GLES20.GL_RENDERBUFFER, rbs[0])
        GLES30.glRenderbufferStorageMultisample(
            GLES20.GL_RENDERBUFFER, samples, GLES30.GL_RGBA8, w, h,
        )
        GLES20.glBindRenderbuffer(GLES20.GL_RENDERBUFFER, rbs[1])
        // 引擎开了 GL_DEPTH_TEST（牌的前后遮挡靠它），深度缓冲必须一起多采样。
        GLES30.glRenderbufferStorageMultisample(
            GLES20.GL_RENDERBUFFER, samples, GLES20.GL_DEPTH_COMPONENT16, w, h,
        )
        GLES20.glBindRenderbuffer(GLES20.GL_RENDERBUFFER, 0)

        val fbo = IntArray(1)
        GLES20.glGenFramebuffers(1, fbo, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0])
        GLES20.glFramebufferRenderbuffer(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_RENDERBUFFER, rbs[0],
        )
        GLES20.glFramebufferRenderbuffer(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_DEPTH_ATTACHMENT, GLES20.GL_RENDERBUFFER, rbs[1],
        )
        val ok = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) ==
            GLES20.GL_FRAMEBUFFER_COMPLETE
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

        if (!ok) {
            GLES20.glDeleteRenderbuffers(2, rbs, 0)
            GLES20.glDeleteFramebuffers(1, fbo, 0)
            android.util.Log.w("GlEngineSource", "多采样 FBO 不可用，退化到无抗锯齿")
            return false
        }
        msaaColorRb = rbs[0]
        msaaDepthRb = rbs[1]
        msaaFbo = fbo[0]
        msaaActive = true
        return true
    }

    /** GL 线程调用（宿主销毁时）。 */
    fun releaseGl() {
        releaseTargets()
        created = false
        engineSurfaceReady = false
        width = 0
        height = 0
        requestedW = 0
        requestedH = 0
    }

    private fun releaseTargets() {
        if (resolveFbo != 0) {
            val f = intArrayOf(resolveFbo)
            GLES20.glDeleteFramebuffers(1, f, 0)
            resolveFbo = 0
        }
        if (resolveTex != 0) {
            val t = intArrayOf(resolveTex)
            GLES20.glDeleteTextures(1, t, 0)
            resolveTex = 0
        }
        if (msaaFbo != 0) {
            val f = intArrayOf(msaaFbo)
            GLES20.glDeleteFramebuffers(1, f, 0)
            msaaFbo = 0
        }
        val rbs = intArrayOf(msaaColorRb, msaaDepthRb)
        if (rbs[0] != 0 || rbs[1] != 0) {
            GLES20.glDeleteRenderbuffers(2, rbs, 0)
        }
        msaaColorRb = 0
        msaaDepthRb = 0
        msaaActive = false
    }
}
