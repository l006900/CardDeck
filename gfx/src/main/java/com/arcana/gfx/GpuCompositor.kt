package com.arcana.gfx

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Handler
import android.os.Looper
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

    /**
     * 有依赖 uTime 的动态 shader 时置 true：内容代次每帧都算变，按 maxFps 节流出帧。
     *
     * **不再承担「自驱 requestRender」的职责**——宿主恒为连续回调，节拍由渲染线程自己维持。
     * CPU 自产纹理（[DynamicTexture2DSource]）不用这个标志，它自己的 `selfAnimating` 会报。
     */
    @Volatile
    var animating: Boolean = false

    /** GL 线程实测指标，约每 0.5s 回调一次（已切回主线程）。 */
    @Volatile
    var onStats: ((GpuStats) -> Unit)? = null

    /** 自定义 GLSL 编译失败等可恢复错误（已切回主线程），不让 GL 线程崩。 */
    @Volatile
    var onError: ((String) -> Unit)? = null

    /**
     * 可选的一帧输出接出点（GL 线程回调）。典型用途是把合成结果送进 MediaCodec 编码。
     * 传进来的 texture 属于当前 EGL share group；实现方要自己管理 context 切换。
     */
    @Volatile
    var frameSink: GpuFrameSink? = null

    private val sources = ConcurrentHashMap<String, FrameSource>()
    private val timing = FrameTiming()
    private val gpuTimer = GpuTimer()
    private var systemMeters: SystemMeters? = null
    private val targets = TargetPair()
    /** 每路画面独有的 ping-pong 目标，按 tile 下标复用，布局切换时才重建。 */
    private val tilePairs = ArrayList<TargetPair>()
    /**
     * 双输入 pass（混合 / 转场）的第二路输入缓冲：先把 [ShaderPass.secondarySource]
     * 那一路源 blit 成 2D 纹理，再绑给 `uTexture2`。这样 2D / 摄像头 / 视频源都能混。
     */
    private val secondaryScratch = TargetPair()
    private val mainHandler = Handler(Looper.getMainLooper())

    // 离屏合成尺寸 = 屏幕尺寸 × 实际生效的缩放；输出到屏幕时线性上采样。
    private var sceneW = 1
    private var sceneH = 1
    private var appliedScale = 1f
    private var appliedPolicyScale = 1f
    private var appliedBudget = 0L

    private val identity = FloatArray(16)
    private val stScratch = FloatArray(16)
    /** 等比 cover 裁剪的临时 uv 矩形，避免每次绘制 new 数组。 */
    private val coverCrop = FloatArray(4)

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
    private var windowDropped = 0
    private var lastEncodedFrames = 0
    private var dirty = true
    /** 上一个被换出去的缓冲里装的不是当前画面：下一次跳过合成时必须重贴一次。 */
    private var needsRepaint = false
    private var released = false

    // 录制时钟：仅在 frameSink（编码器接出点）非空时启用。
    // PTS 用「序号 × 目标帧间隔」的 CFR 序列（源到达抖动不进时间戳），
    // recordLastFeedNs 用于把比目标帧率更快的源降采样到目标帧率。
    private var recordStartNs = 0L
    private var recordFrameIndex = 0L
    private var recordLastPtsNs = -1L
    private var recordLastFeedNs = 0L

    // 稳态节拍：回调槽位计数（vsync 锁相），见 onDrawFrame 内注释。
    private var cbLastNs = 0L
    private var cbPeriodEma = 0.0
    private var slotSinceDraw = 0


    init {
        Matrix.setIdentityM(identity, 0)
    }

    /* ------------------------------------------------------ 公开操作 */

    /** 由 [CompositeStage] 在 View 创建后调用，用于 queueEvent / requestRender。 */
    fun attach(view: GLSurfaceView) {
        host = view
        systemMeters = SystemMeters(view.context.applicationContext)
    }

    /**
     * 注册一路源。可在任意时机调用：若 GL surface 已就绪，初始化会被投到 GL 线程执行。
     * 外部纹理源的帧到达事件会自动接到帧时钟并触发按需渲染。
     */
    fun register(key: String, source: FrameSource) {
        sources[key] = source
        // 统一接线：视频 / 摄像头 / 任意实现了 frameSink 的生产者都进帧时钟。
        // 没有外部生产者的源用接口默认空实现，这里赋值不会有任何副作用。
        source.frameSink = { arrivalNs ->
            // 只有当前布局真的用到这一路，才计入延迟统计并请求渲染；
            // 否则一路闲置的摄像头/解码器会白白把按需渲染打成连续渲染。
            if (activeSourceKeys.contains(key)) {
                timing.push(arrivalNs)
                host?.requestRender()
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
     * 把合成结果接给编码器（录制开始）或断开（录制结束）。
     *
     * 只清录制时钟（起点 + 上一帧 PTS），**不动渲染模式**：宿主恒为连续回调，
     * 由「内容代次」门控决定画不画、送不送。旧实现会在这里切 `WHEN_DIRTY`，
     * 而按需渲染下每次 `requestRender` 只换一次回调，门控一旦跳过就**永久丢帧**
     * （没有下一次补偿），正是「一直卡帧」的来源。
     */
    fun attachFrameSink(sink: GpuFrameSink?) {
        frameSink = sink
        recordStartNs = 0L
        recordFrameIndex = 0L
        recordLastPtsNs = -1L
        recordLastFeedNs = 0L
    }

    /**
     * 把一段代码投到 GL 线程执行。释放 EGL / GL 对象这类必须持有上下文的操作用它，
     * 例如录制结束后的 `VideoRecorder.releaseGl()`。
     */
    fun postGl(block: () -> Unit) {
        if (!released) host?.queueEvent(block)
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
        gpuTimer.onGlLost()
        gpuTimer.close()
        systemMeters?.close()
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
        gpuTimer.onGlCreated()

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
            ensureTargets()
        }
        GLES20.glViewport(0, 0, nw, nh)
    }

    /* ------------------------------------------------------ 资源预算 */

    /**
     * 按预算挑一档离屏缩放。选档逻辑是 [RenderPolicy.chooseScale] 这个纯函数，
     * 真正上报的显存数字仍是各 TargetPair 的实测尺寸之和。
     */
    private fun chooseScale(p: RenderPolicy): Float =
        RenderPolicy.chooseScale(viewW, viewH, p.resolutionScale, p.maxTextureBytes)

    private fun ensureTargets() {
        val p = policy
        appliedPolicyScale = p.resolutionScale
        appliedBudget = p.maxTextureBytes
        appliedScale = chooseScale(p)
        sceneW = (viewW * appliedScale).toInt().coerceAtLeast(1)
        sceneH = (viewH * appliedScale).toInt().coerceAtLeast(1)
        targets.ensure(sceneW, sceneH)
        // 尺寸变了，旧的 per-tile FBO 由 tilePairAt 的 ensure 自行重建。
        dirty = true
    }

    override fun onDrawFrame(gl: GL10?) {
        if (released || !surfaceReady) return
        val renderNs = System.nanoTime()

        val specs = tiles
        val active = activeSourceKeys
        val currentPolicy = policy
        val sink = frameSink
        val recording = sink != null

        // 1) **只查询、不消费**：帧到达与 GL 回调是两个独立时钟。
        //    被节流跳过的回调不能把新帧取走——帧留在 BufferQueue 里，下次真正绘制时一次追到最新。
        var pending = false
        for ((key, s) in sources) {
            if (active.contains(key) && s.external && s.hasPendingFrame()) {
                pending = true
                break
            }
        }
        // 2) 内容代次会不会变：外部新帧 / uTime 动画 / CPU 自产纹理 / 布局参数变化。
        val selfProducing = active.any { sources[it]?.selfAnimating == true }
        // 布局里有没有外部帧源（视频 / 摄像头）：有则**恒定刷新率出场**，见 shouldDraw 第 2 条。
        val externalPresent = active.any { sources[it]?.external == true }

        // 3) 稳态节拍：槽位计数（回调被 eglSwapBuffers 锁相在 vsync 上，实测 ≈60/s）。
        //    不用「时间阈值」——GL 线程迟到大 1ms 就会把 k+2 号回调判为「未到间隔」而跳过，
        //    该帧滑到 k+3（+50ms）才画，实测每秒混 3~5 个 41~50ms 尖峰就是它造成的。
        //    槽位计数把节奏锁在 vsync 整数格上，单次迟到由下一格立刻追平，零滑拍。
        if (cbLastNs != 0L) {
            val d = renderNs - cbLastNs
            if (d in 1..100_000_000L) {
                cbPeriodEma = if (cbPeriodEma == 0.0) d.toDouble()
                else cbPeriodEma * 0.9 + d * 0.1
            }
        }
        cbLastNs = renderNs
        slotSinceDraw++
        val cbPeriod = if (cbPeriodEma > 1_000_000.0) cbPeriodEma else 16_666_666.0
        val slotsPerFrame =
            (currentPolicy.frameIntervalNs() / cbPeriod).coerceIn(1.0, 8.0)
        val intervalOk = slotSinceDraw >= Math.round(slotsPerFrame)

        val mode = RenderPolicy.presentMode(
            pending = pending,
            dirty = dirty,
            animating = animating,
            selfProducing = selfProducing,
            externalPresent = externalPresent,
            intervalOk = intervalOk,
            needsRepaint = needsRepaint,
        )

        // 3b) 不重新合成，但后备缓冲里还是旧画面 → 把当前输出重贴一次（一次 blit）。
        //     不做这一步，`eglSwapBuffers` 会把两帧以前的旧缓冲翻上来，
        //     30fps 就变成「动 动 停 停」（详见 RenderPolicy.presentMode 的注释）。
        if (mode == PresentMode.REPEAT) {
            repeatPresent()
            needsRepaint = false
            return
        }
        if (mode == PresentMode.IDLE) return

        // 4) 真正要画了才锁帧：注册了但没被 tile 引用的源不碰 GLES；
        //    队列里若积压了多帧，update() 会一次追到最新，不会把旧帧显示出来。
        for ((key, s) in sources) {
            if (active.contains(key)) s.update()
        }
        // 5) 分辨率 / 显存预算是策略的一部分：变化时在真正绘制前重建离屏目标。
        if (currentPolicy.resolutionScale != appliedPolicyScale ||
            currentPolicy.maxTextureBytes != appliedBudget || targets.read.texture == 0
        ) ensureTargets()
        dirty = false
        lastDrawNs = renderNs
        slotSinceDraw = 0

        val t = (renderNs - startNs) / 1_000_000_000f
        val twoD = blit2D
        val oes = blitOes
        if (twoD == null || oes == null || targets.read.texture == 0) return
        val timingActive = gpuTimer.begin()

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
            val vp = spec.tile.toViewport(sceneW, sceneH)

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
            runPass(program, pass, targets, t, 1f / sceneW, 1f / sceneH)
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
        // 这一帧被换出去了，后备缓冲此刻装的还是更早的画面；下一次若因节流不合成，
        // 必须重贴一次，否则 swap 会把旧画面翻上来（30fps「动 动 停 停」的根因）。
        needsRepaint = true
        // timer query 必须在本 context 内闭合：编码器会切走 EGL context，别把它的开销算进 GPU 时间。
        if (timingActive) gpuTimer.end()

        // 6) 把最终合成纹理接给外部编码器（GL 线程，共享 EGL context 内二次渲染）。
        //
        //    **只喂「内容代次真的变了」的帧**：pending（外部新帧）/ animating（uTime 动画）/
        //    selfProducing（CPU 自产纹理）；布局里根本没有外部源时每次重画内容都在变，也算。
        //    第 2 步为保证出场平滑，视频页每个 vsync 都会重画一次**内容未变**的画面——
        //    那些帧只用于显示层，绝不能送编码器，否则就是「渲染出一帧然后一直插它」。
        //
        //    PTS 不能直接用墙钟：源到达时刻自带 ±7ms 抖动，帧时长就会在 23~45ms 之间跳
        //    （播放器看起来就是卡）。也不能用「序号 × 间隔」：源比目标慢时会把视频压短、
        //    播放变快。正确做法是把**真实经过时间吸附到目标帧间隔的整数格**上——
        //    既均匀（同一格内的抖动被吃掉），又忠实（真的慢了就多占几格，确实是定格）。
        //    `tooSoon` 负责反向：源比目标快时按 0.75×间隔降采样，输出锁在目标帧率。
        if (recording) {
            val first = recordStartNs == 0L
            if (first) recordStartNs = renderNs
            val contentChanged = pending || animating || selfProducing || !externalPresent
            val targetFps = sink!!.targetFps
            val intervalNs = if (targetFps > 0) 1_000_000_000L / targetFps else 0L
            val tooSoon = intervalNs > 0 && !first &&
                renderNs - recordLastFeedNs < intervalNs * 3 / 4
            if (contentChanged && !tooSoon) {
                val elapsed = (renderNs - recordStartNs).coerceAtLeast(0L)
                val pts = if (intervalNs > 0) {
                    val snapped = Math.round(elapsed.toDouble() / intervalNs) * intervalNs
                    if (snapped <= recordLastPtsNs) recordLastPtsNs + intervalNs else snapped
                } else {
                    elapsed
                }
                recordLastPtsNs = pts
                recordLastFeedNs = renderNs
                recordFrameIndex++
                frameSink?.onFrame(targets.read.texture, sceneW, sceneH, pts)
            }
        }

        // 7) 指标：只统计真正渲染的帧，跳过的帧不算进 FPS
        val latency = timing.drain(renderNs)
        // native 的 dropped 是 exchange(0)，每帧都要累加，否则 0.5s 窗口里只看到最后一次。
        windowDropped += latency[4].toInt()
        frames++
        if (t - fpsWindowStart >= 0.5f) {
            val fps = frames / (t - fpsWindowStart)
            val dropped = windowDropped
            frames = 0
            windowDropped = 0
            fpsWindowStart = t
            var bytes = targets.bytes()
            for (p in tilePairs) bytes += p.bytes()
            for ((key, s) in sources) if (active.contains(key)) bytes += s.byteCount()
            // 系统采样投递到独立后台线程：同步 binder 单次约 50ms，绝不能落在 GL 线程。
            systemMeters?.requestSample()
            val sys = systemMeters?.latest()
            if (sink != null) lastEncodedFrames = sink.framesWritten()
            val snapshot = GpuStats(
                fps = fps,
                // 在整帧工作做完后取；旧实现在采样前就算完了，把最慢的 50ms 漏得干干净净。
                cpuFrameMs = (System.nanoTime() - renderNs) / 1_000_000f,
                drawCalls = draws,
                gpuPasses = passCount,
                textureBytes = bytes,
                latencyP50Ms = latency[1],
                latencyP95Ms = latency[2],
                droppedFrames = dropped,
                gpuFrameMs = gpuTimer.pollMs(),
                cpuPercent = sys?.cpuPercent ?: 0f,
                pssKb = sys?.pssKb ?: 0,
                graphicsPssKb = sys?.graphicsPssKb ?: 0,
                nativeHeapKb = sys?.nativeHeapKb ?: 0L,
                powerMw = sys?.powerMw ?: -1f,
                jitterP95Ms = latency[5],
                resolutionScale = appliedScale,
                encodedFrames = lastEncodedFrames,
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
        val texture2: Int,
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
            GlUtil.location(p, "uTexture2"),
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

    /**
     * 把一路源画进当前绑定目标的指定 viewport。
     *
     * [spec.crop] 是调用方指定的 uv 子区域（画中画 / 多画面拼接用），默认全幅；
     * 在此之上再叠加一层**等比 cover 裁剪**：按源内禀宽高比（含旋转对调）与瓦片像素宽高比
     * 算出一个子矩形，让画面「填满瓦片且不拉伸」，多出来的边被裁掉（类似 CSS object-fit: cover）。
     * 源未提供尺寸（[FrameSource.contentWidth] == 0，如未知尺寸的视频）时跳过，保持旧行为。
     */
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
        val cc = coverCrop(source, spec, vp)
        GLES20.glUniform4f(program.crop, cc[0], cc[1], cc[2], cc[3])
        GLES20.glUniformMatrix4fv(program.stMatrix, 1, false, source.transform(stScratch), 0)
        GLES20.glUniform1f(program.flipY, if (spec.flipY) 1f else 0f)
        drawQuad(program.pos, program.uv)
    }

    /**
     * 计算最终 uv crop（在 [spec.crop] 框定的区域内再做等比 cover 裁剪）。
     * 返回 [coverCrop]（复用字段，调用方需立即使用，不跨帧持有）。
     */
    private fun coverCrop(source: FrameSource, spec: TileSpec, vp: IntArray): FloatArray {
        val base = spec.crop
        val cw = source.contentWidth
        val ch = source.contentHeight
        // 仅对「已知尺寸的外部媒体源」做适配；程序化/未知尺寸源维持原样（不裁不拉）。
        if (cw <= 0 || ch <= 0 || !source.external) {
            coverCrop[0] = base[0]; coverCrop[1] = base[1]; coverCrop[2] = base[2]; coverCrop[3] = base[3]
            return coverCrop
        }
        val tileW = vp[2].toFloat(); val tileH = vp[3].toFloat()
        val t = if (tileH > 0f) tileW / tileH else 1f          // 瓦片屏幕宽高比 w/h
        // 旋转 90/270 时内容宽高对调。
        val swapped = ((source.contentRotationDeg % 180) + 360) % 180 == 90
        // 在「原始 uv 空间」（旋转前）选子矩形 (su, sv)，旋转后其显示宽高比须 == t。
        // 旋转后：显示宽 = sv*ch，显示高 = su*cw（90/270）或 显示宽=su*cw, 显示高=sv*ch（0/180）。
        var su = 1f; var sv = 1f
        if (swapped) {
            val r = t * cw / ch                              // sv/su
            if (r <= 1f) { su = 1f; sv = r } else { sv = 1f; su = 1f / r }
        } else {
            val r = t * ch / cw                              // su/sv
            if (r <= 1f) { sv = 1f; su = r } else { su = 1f; sv = 1f / r }
        }
        // 子矩形在原始 uv 居中，再线性映射到 spec.crop 框定的区间（与调用方区域兼容）。
        val u0 = (1f - su) / 2f; val u1 = (1f + su) / 2f
        val v0 = (1f - sv) / 2f; val v1 = (1f + sv) / 2f
        coverCrop[0] = base[0] + u0 * (base[2] - base[0])
        coverCrop[1] = base[1] + v0 * (base[3] - base[1])
        coverCrop[2] = base[0] + u1 * (base[2] - base[0])
        coverCrop[3] = base[1] + v1 * (base[3] - base[1])
        return coverCrop
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

    /**
     * 只重贴、不重新合成：把上一帧的输出再画进默认帧缓冲。
     *
     * 一次 clear + 一次全屏 quad（约 0.3ms），比重新跑「每路源 + 每格特效链 + 全局后处理」
     * 便宜一个数量级；换来的是**两个缓冲区内容始终一致**，swap 翻哪张都不会出现旧帧。
     * 不碰 [targets]、不改 [slotSinceDraw]、不计入 fps 与编码帧。
     */
    private fun repeatPresent() {
        val program = blit2D ?: return
        val texture = targets.read.texture
        if (texture == 0) return
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, viewW, viewH)
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        blitTexture2D(program, texture)
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

        // 双输入：把第二路源先 blit 进 scratch（2D 或 OES 都先转成 2D），再绑给 uTexture2。
        if (program.texture2 >= 0 && pass.secondarySource != null) {
            val sec = sources[pass.secondarySource]
            val secTex = sec?.textureId() ?: 0
            if (sec != null && secTex > 0) {
                sec.update()
                val sw = pair.read.width.coerceAtLeast(2)
                val sh = pair.read.height.coerceAtLeast(2)
                secondaryScratch.ensure(sw, sh)
                secondaryScratch.reset()
                secondaryScratch.write.bind()
                GLES20.glClearColor(0f, 0f, 0f, 0f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                GLES20.glDisable(GLES20.GL_BLEND)
                val secProg = if (sec.external) blitOes else blit2D
                if (secProg != null) {
                    blitSource(
                        secProg,
                        sec,
                        TileSpec(source = pass.secondarySource, tile = Tile(0f, 0f, 1f, 1f)),
                        intArrayOf(0, 0, sw, sh),
                    )
                }
                secondaryScratch.swap()
                GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, secondaryScratch.read.texture)
                GLES20.glUniform1i(program.texture2, 1)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            }
        }

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
    }
}
