package com.arcana.gfx

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Handler
import android.os.Looper
import com.arcana.gl.GlUtil
import java.util.concurrent.ConcurrentHashMap
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * **GPU 合成管线**：多路画面源 → 离屏拼接 → 可组合后处理链 → 屏幕。
 *
 * 一帧的固定开销结构（与源路数、pass 数线性相关，与业务无关）：
 * ```
 * sources.update()              锁进最新帧（OES 零拷贝；2D 走 texSubImage）
 *   ↓
 * FBO.write                     每路源 1 个 draw call，viewport 决定它落在哪块
 *   ↓
 * passes.forEach               每 pass 1 个 draw call，ping-pong 交换读写纹理
 *   ↓
 * default framebuffer           最后 1 个 draw call 输出
 * ```
 *
 * 所有 GLES 调用都在 GL 线程；[tiles] / [passes] / [policy] 由 UI 线程改（volatile 引用），
 * 帧到达事件由解码线程投进 [FrameTiming] 的无锁队列。三条线程各管一段，互不阻塞。
 *
 * 自定义特效不需要 fork 这个库：给 [ShaderPass] 传一段遵守 uniform 约定的 GLSL 即可，
 * 源码相同的 pass 会复用已编译 program。
 */
class GpuCompositor : GLSurfaceView.Renderer {

    /** 画面拼接描述（UI 线程可随时替换整个列表）。 */
    @Volatile
    var tiles: List<TileSpec> = emptyList()
        set(value) {
            field = value
            activeSourceKeys = value.map { it.source }.toSet()
            invalidate()
        }

    /** 后处理链（UI 线程可随时替换）。 */
    @Volatile
    var passes: List<ShaderPass> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    /** 帧调度策略（UI 线程可随时替换）。 */
    @Volatile
    var policy: RenderPolicy = RenderPolicy()
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** 有依赖 uTime 的动态效果时置 true，让 ON_DEMAND 模式也持续出帧。 */
    @Volatile
    var animating: Boolean = false

    /** GL 线程实测指标，约每 0.5s 回调一次（已切回主线程）。 */
    @Volatile
    var onStats: ((GpuStats) -> Unit)? = null

    /** 自定义 GLSL 编译失败等可恢复错误（已切回主线程），不让 GL 线程崩。 */
    @Volatile
    var onError: ((String) -> Unit)? = null

    private val sources = ConcurrentHashMap<String, FrameSource>()
    private val timing = FrameTiming()
    private val targets = TargetPair()
    /** 每路画面独有的 ping-pong 目标，按 tile 下标复用，布局切换时才重建。 */
    private val tilePairs = ArrayList<TargetPair>()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val identity = FloatArray(16)
    private val stScratch = FloatArray(16)

    private var host: GLSurfaceView? = null
    private var surfaceReady = false
    private var viewW = 1
    private var viewH = 1

    private var blit2D: BlitProgram? = null
    private var blitOes: BlitProgram? = null
    private val passCache = HashMap<String, PassProgram>()
    private val failedShaders = HashSet<String>()
    private val attribHandles = HashSet<Int>()
    /**
     * UI 线程在 [tiles] 变化时生成的**不可变**快照，供 GL 线程过滤、解码线程读。
     * 用 volatile 引用 + copy-on-write，避免可变集合跨线程读写。
     */
    @Volatile
    private var activeSourceKeys: Set<String> = emptySet()

    private val startNs = System.nanoTime()
    private var lastDrawNs = 0L
    private var fpsWindowStart = 0f
    private var frames = 0
    private var dirty = true
    private var released = false

    init {
        Matrix.setIdentityM(identity, 0)
    }

    /* ------------------------------------------------------ 公开操作 */

    /** 由 [CompositeStage] 在 View 创建后调用，用于 queueEvent / requestRender。 */
    fun attach(view: GLSurfaceView) {
        host = view
    }

    /**
     * 注册一路源。可在任意时机调用：若 GL surface 已就绪，初始化会被投到 GL 线程执行。
     * 外部纹理源的帧到达事件会自动接到帧时钟并触发按需渲染。
     */
    fun register(key: String, source: FrameSource) {
        sources[key] = source
        if (source is ExternalTextureSource) {
            source.frameSink = { arrivalNs ->
                // 只有当前布局真的用到这一路，才计入延迟统计并请求渲染；
                // 否则一路闲置的摄像头/解码器会白白把按需渲染打成连续渲染。
                if (activeSourceKeys.contains(key)) {
                    timing.push(arrivalNs)
                    host?.requestRender()
                }
            }
        }
        dirty = true
        if (surfaceReady) {
            host?.queueEvent {
                if (sources[key] === source) source.onGlCreated()
            }
        }
    }

    fun unregister(key: String) {
        sources.remove(key)
        dirty = true
    }

    fun sources(): Collection<FrameSource> = sources.values

    /** 参数变化 / 外部想强制重画一帧。任意线程可调。 */
    fun invalidate() {
        if (released) return
        dirty = true
        host?.requestRender()
    }

    /**
     * View 销毁时调用（**主线程**）：只丢引用、停帧时钟，绝不碰 GLES。
     * GL 对象随 EGL 上下文由驱动回收；下次 surface 创建会重新建 program 与 FBO。
     */
    fun release() {
        released = true
        surfaceReady = false
        for (s in sources.values) s.onGlLost()
        passCache.clear()
        timing.close()
        host = null
    }

    /* ------------------------------------------------------ Renderer */

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        passCache.clear()
        failedShaders.clear()
        attribHandles.clear()
        // 旧 EGL 上下文的 FBO / 纹理 id 已失效：归零让 onSurfaceChanged 里的 ensure 重建，
        // 否则「尺寸没变」会让 ensure 直接返回，页面回来就是黑屏。
        targets.forgetGpu()
        for (p in tilePairs) p.forgetGpu()
        blit2D = compileBlit(GfxShaders.BLIT_2D_FRAGMENT)
        blitOes = compileBlit(GfxShaders.BLIT_OES_FRAGMENT)

        surfaceReady = true
        for (s in sources.values) s.onGlCreated()
        dirty = true
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        val nw = w.coerceAtLeast(1)
        val nh = h.coerceAtLeast(1)
        // 尺寸没变（或只是重复回调）时不重建 FBO、也不置脏：
        // 上层任何一次无意义重组都不该把 ON_DEMAND 唤醒成一帧真实绘制。
        val changed = nw != viewW || nh != viewH
        viewW = nw
        viewH = nh
        if (changed || targets.read.texture == 0) {
            targets.ensure(nw, nh)
            dirty = true
        }
        GLES20.glViewport(0, 0, nw, nh)
    }

    override fun onDrawFrame(gl: GL10?) {
        if (released || !surfaceReady) return
        val renderNs = System.nanoTime()

        // 1) 锁进**当前布局实际用到**的源的最新帧（GL 线程独占 GLES）。
        //    注册了但没被任何 tile 引用的源不做上传/锁帧，省掉无谓的带宽与功耗。
        val specs = tiles
        val active = activeSourceKeys
        var newFrame = false
        for ((key, s) in sources) {
            if (!active.contains(key)) continue
            if (s.update() != 0L) newFrame = true
        }

        // 2) 帧门控：未到间隔 / 无新帧且非连续模式 → 直接返回，省掉整帧 GPU 负载
        val currentPolicy = policy
        if (renderNs - lastDrawNs < currentPolicy.frameIntervalNs() - FRAME_TOLERANCE_NS) return
        if (currentPolicy.mode == RenderMode.ON_DEMAND &&
            !newFrame && !dirty && !animating
        ) return
        dirty = false
        lastDrawNs = renderNs

        val t = (renderNs - startNs) / 1_000_000_000f
        val twoD = blit2D
        val oes = blitOes
        if (twoD == null || oes == null || targets.read.texture == 0) return

        var draws = 0
        var passCount = 0

        // 3) 拼接：每路源先（可选）跑自己独有的特效链，再合成进目标的 viewport。
        //    y 翻转只在 Tile.toViewport 里做一次，避免每一路各自翻导致某格上下颠倒。
        targets.reset()
        val scene = targets.write
        scene.bind()
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glDisable(GLES20.GL_BLEND)

        for (index in specs.indices) {
            val spec = specs[index]
            val source = sources[spec.source] ?: continue
            val id = source.textureId()
            if (id <= 0) continue
            val program = if (source.external) oes else twoD
            val vp = spec.tile.toViewport(viewW, viewH)

            if (spec.passes.isEmpty()) {
                // 快路径：无独立特效，直接一格一个 draw call。
                scene.bind()
                blitSource(program, source, spec, vp)
                draws++
                continue
            }

            // 独立链：这一路独占一对小 FBO，尺寸就是这一格的像素大小。
            val pair = tilePairAt(index, vp[2], vp[3])
            pair.reset()
            pair.write.bind()
            GLES20.glClearColor(0f, 0f, 0f, 0f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glDisable(GLES20.GL_BLEND)
            blitSource(program, source, spec, intArrayOf(0, 0, vp[2], vp[3]))
            draws++
            pair.swap()
            val texelX = 1f / vp[2]
            val texelY = 1f / vp[3]
            for (pass in spec.passes) {
                if (!pass.enabled) continue
                val passProgram = programFor(pass.fragment) ?: continue
                runPass(passProgram, pass, pair, t, texelX, texelY)
                draws++
                passCount++
            }

            scene.bind()
            GLES20.glViewport(vp[0], vp[1], vp[2], vp[3])
            GLES20.glDisable(GLES20.GL_BLEND)
            blitTexture2D(twoD, pair.read.texture)
            draws++
        }
        targets.swap()

        // 4) 全局后处理链：作用于拼接完成的整幅画面
        for (pass in passes) {
            if (!pass.enabled) continue
            val program = programFor(pass.fragment) ?: continue
            runPass(program, pass, targets, t, 1f / viewW, 1f / viewH)
            draws++
            passCount++
        }

        // 5) 输出到屏幕。开启混合，透明区域露出下层 Compose UI。
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, viewW, viewH)
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        blitTexture2D(twoD, targets.read.texture)
        draws++

        // 6) 指标：只统计真正渲染的帧，跳过的帧不算进 FPS
        val cpuFrameMs = (System.nanoTime() - renderNs) / 1_000_000f
        val latency = timing.drain(renderNs)
        frames++
        if (t - fpsWindowStart >= 0.5f) {
            val fps = frames / (t - fpsWindowStart)
            frames = 0
            fpsWindowStart = t
            var bytes = targets.bytes()
            for (p in tilePairs) bytes += p.bytes()
            for ((key, s) in sources) if (active.contains(key)) bytes += s.byteCount()
            val snapshot = GpuStats(
                fps = fps,
                cpuFrameMs = cpuFrameMs,
                drawCalls = draws,
                gpuPasses = passCount,
                textureBytes = bytes,
                latencyP50Ms = latency[1],
                latencyP95Ms = latency[2],
                droppedFrames = latency[4].toInt(),
                mode = currentPolicy.mode,
            )
            val cb = onStats
            if (cb != null) mainHandler.post { cb(snapshot) }
        }
    }

    /* ------------------------------------------------------ 内部 */

    private class BlitProgram(
        val program: Int,
        val pos: Int, val uv: Int,
        val texture: Int, val crop: Int, val stMatrix: Int, val flipY: Int,
    )

    private class PassProgram(
        val program: Int,
        val pos: Int, val uv: Int,
        val texture: Int, val texel: Int, val params: Int, val time: Int,
    )

    private fun compileBlit(fragment: String): BlitProgram {
        val p = GlUtil.buildProgram(GfxShaders.BLIT_VERTEX, fragment)
        val prog = BlitProgram(
            p,
            GlUtil.attrib(p, "aPos"), GlUtil.attrib(p, "aUv"),
            GlUtil.location(p, "uTexture"), GlUtil.location(p, "uCrop"),
            GlUtil.location(p, "uSTMatrix"), GlUtil.location(p, "uFlipY"),
        )
        remember(prog.pos, prog.uv)
        return prog
    }

    private fun compilePass(fragment: String): PassProgram {
        val p = GlUtil.buildProgram(GfxShaders.PASS_VERTEX, fragment)
        val prog = PassProgram(
            p,
            GlUtil.attrib(p, "aPos"), GlUtil.attrib(p, "aUv"),
            GlUtil.location(p, "uTexture"), GlUtil.location(p, "uTexel"),
            GlUtil.location(p, "uParams"), GlUtil.location(p, "uTime"),
        )
        remember(prog.pos, prog.uv)
        return prog
    }

    private fun remember(vararg handles: Int) {
        for (h in handles) if (h >= 0) attribHandles.add(h)
    }

    /** 同一份 GLSL 只编译一次；编译失败也记下来，避免每帧重试拖垮 GL 线程。 */
    private fun programFor(fragment: String): PassProgram? {
        passCache[fragment]?.let { return it }
        if (fragment in failedShaders) return null
        return try {
            compilePass(fragment).also { passCache[fragment] = it }
        } catch (e: Throwable) {
            failedShaders.add(fragment)
            val message = "shader 编译失败: ${e.message}"
            val cb = onError
            if (cb != null) mainHandler.post { cb(message) }
            null
        }
    }

    /** 把一路源画进当前绑定目标的指定 viewport。 */
    private fun blitSource(
        program: BlitProgram,
        source: FrameSource,
        spec: TileSpec,
        vp: IntArray,
    ) {
        GLES20.glViewport(vp[0], vp[1], vp[2], vp[3])
        GLES20.glUseProgram(program.program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(source.textureTarget, source.textureId())
        GLES20.glUniform1i(program.texture, 0)
        GLES20.glUniform4f(program.crop, spec.crop[0], spec.crop[1], spec.crop[2], spec.crop[3])
        GLES20.glUniformMatrix4fv(program.stMatrix, 1, false, source.transform(stScratch), 0)
        GLES20.glUniform1f(program.flipY, if (spec.flipY) 1f else 0f)
        drawQuad(program.pos, program.uv)
    }

    /** 把一张 2D 纹理原样画进当前 viewport（不改 viewport，由调用方设好）。 */
    private fun blitTexture2D(program: BlitProgram, texture: Int) {
        GLES20.glUseProgram(program.program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glUniform1i(program.texture, 0)
        GLES20.glUniform4f(program.crop, 0f, 0f, 1f, 1f)
        GLES20.glUniformMatrix4fv(program.stMatrix, 1, false, identity, 0)
        GLES20.glUniform1f(program.flipY, 0f)
        drawQuad(program.pos, program.uv)
    }

    /** 一次后处理：从 read 采样、写进 write、交换。texel 用目标自身尺寸。 */
    private fun runPass(
        program: PassProgram,
        pass: ShaderPass,
        pair: TargetPair,
        timeSec: Float,
        texelX: Float,
        texelY: Float,
    ) {
        pair.write.bind()
        GLES20.glUseProgram(program.program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, pair.read.texture)
        GLES20.glUniform1i(program.texture, 0)
        GLES20.glUniform2f(program.texel, texelX, texelY)
        GLES20.glUniform4fv(program.params, 1, pass.params, 0)
        GLES20.glUniform1f(program.time, timeSec)
        drawQuad(program.pos, program.uv)
        pair.swap()
    }

    private fun tilePairAt(index: Int, w: Int, h: Int): TargetPair {
        while (tilePairs.size <= index) tilePairs.add(TargetPair())
        return tilePairs[index].also { it.ensure(w, h) }
    }

    private fun drawQuad(pos: Int, uv: Int) {
        clearAttribs()
        GlUtil.bindQuad(pos, uv)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun clearAttribs() {
        for (a in attribHandles) GLES20.glDisableVertexAttribArray(a)
    }

    private companion object {
        /** 允许 1ms 的调度抖动，避免 60fps 边界上因为回调早到而整帧被跳过。 */
        const val FRAME_TOLERANCE_NS = 1_000_000L
    }
}
