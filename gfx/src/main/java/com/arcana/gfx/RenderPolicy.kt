package com.arcana.gfx

/**
 * 一次 GL 回调要做的事。
 *
 * - [DRAW]：重新合成（内容代次变了 / 布局变了）；
 * - [REPEAT]：内容没变但「另一个缓冲还是旧的」——把上一帧的输出再贴一遍（1 次 blit），
 *   让两个缓冲区内容一致。**这一条是 30fps 卡顿的真正解药**，见 [RenderPolicy.presentMode]；
 * - [IDLE]：什么都不做（两个缓冲区已经一致，或静态页）。
 */
enum class PresentMode { DRAW, REPEAT, IDLE }

/**
 * 帧调度 + 资源预算策略。
 *
 * **没有渲染模式开关**：宿主恒为 `RENDERMODE_CONTINUOUSLY`（节拍永不断链），
 * 画不画由 [Companion.shouldDraw] 决定，**出场节拍与内容代次解耦**：
 * 有外部源（视频 / 摄像头）时每个回调都出场（恒刷新率，绝不空拍）；
 * 纯程序化 / 动画按 [maxFps] 节流；内容没变则不画。
 * 送不送编码器是另一条判据：只有内容代次真的变了才喂帧。
 *
 * 历史上四次错误都写在这里留档：① `WHEN_DIRTY` + 节流，被跳过的回调**永久丢帧**（一直卡帧）；
 * ② 按 animating 无条件放行，源空闲时每 33ms 重画旧纹理（「渲染一帧然后一直插它」）；
 * ③ 时间阈值限速，GL 线程迟到 1ms 就滑掉一整格（33ms 混 41~50ms 尖峰）；
 * ④ 让「新帧到达」等 vsync 槽位——源周期与槽位周期相位漂移，出场退化成 33/50ms 交替
 *    （视频也卡）。结论：**内容更新跟着源，出场节奏跟着屏幕，两者不要互相牵制。**
 *
 * [resolutionScale] 让离屏合成按低于屏幕的分辨率跑，最后一步上采样输出：
 * FBO 显存和填充率按 scale² 下降，是显存/功耗预算里最划算的一刀。
 * [maxTextureBytes] 非 0 时，合成器会在预算不够时自动往下选档（0.75 / 0.5 / 0.35），
 * 实际生效的档位会出现在 [GpuStats.resolutionScale] 里，不静默改参数。
 */
data class RenderPolicy(
    val maxFps: Int = 60,
    val resolutionScale: Float = 1f,
    val maxTextureBytes: Long = 0L,
) {
    init {
        require(maxFps in 1..120) { "maxFps 必须在 1..120" }
        require(resolutionScale in 0.25f..1f) { "resolutionScale 必须在 0.25..1.0" }
        require(maxTextureBytes >= 0L) { "maxTextureBytes 不能为负" }
    }

    fun frameIntervalNs(): Long = 1_000_000_000L / maxFps

    companion object {
        /** 自动预算允许的降档序列，从高到低。 */
        val SCALE_STEPS = floatArrayOf(1f, 0.75f, 0.5f, 0.35f)

        /**
         * 纯函数：按显存预算挑离屏缩放档位，便于 JVM 单测与真机数字对照。
         *
         * 估算口径：主 ping-pong 2 张 + 每路独立链最多再 2 张，四路布局最坏约
         * `w*h*16` 字节 RGBA。这个数只用来选档，真正上报的显存仍是各 FBO 的实测尺寸和。
         * [maxScale] 是上限，预算宽裕也不会把调用方手动设的低分辨率抬高。
         */
        fun chooseScale(viewW: Int, viewH: Int, maxScale: Float, budgetBytes: Long): Float {
            if (budgetBytes <= 0L) return maxScale
            for (s in SCALE_STEPS) {
                if (s > maxScale) continue
                val w = (viewW * s).toInt().coerceAtLeast(1)
                val h = (viewH * s).toInt().coerceAtLeast(1)
                if (w.toLong() * h * 16L <= budgetBytes) return s
            }
            return SCALE_STEPS.last()
        }

        /**
         * 纯函数：这一次回调做什么。三层判据，**顺序不能换**：
         *
         * 1. [shouldDraw] 为真 → [PresentMode.DRAW]（内容代次变了，或出场需要刷新）；
         * 2. 否则若 [needsRepaint] → [PresentMode.REPEAT]；
         * 3. 否则 [PresentMode.IDLE]。
         *
         * **为什么必须有第 2 条**（2026-11-09 真机定位到的第五个坑，也是「30fps 卡帧」的根因）：
         * `RENDERMODE_CONTINUOUSLY` 下 GLSurfaceView **不管 renderer 画没画都会 `eglSwapBuffers`**，
         * 而双缓冲的后备缓冲里装的是**两帧以前**的画面。于是「隔帧画」的 30fps 场景变成：
         *
         * ```
         * 回调 k   画 N      → 上屏 N
         * 回调 k+1 不画，swap → 上屏 N-1（旧缓冲）
         * 回调 k+2 画 N+1    → 上屏 N+1
         * 回调 k+3 不画，swap → 上屏 N（旧缓冲）
         * ```
         *
         * 录屏逐帧差实测正是 `动 动 停 停` 四帧一循环（1.6ms 间隔下 `4.7 4.7 0 0`），
         * 肉眼看就是「一顿一顿、时不时插前面的某一帧」。60fps 时每帧都画所以完全正常
         * （实测每帧都有新内容），这也是为什么以前一直以为是「源」的问题。
         *
         * 解法不是「不画就 return」，而是**不画也要把当前画面重贴到后备缓冲**：
         * 一次全屏 blit（约 0.3ms，比重新合成便宜一个数量级），两次上屏内容就一致了。
         * 只贴一次就够——之后两个缓冲区已经相同，再跳过也不会有旧帧冒出来。
         * */
        fun presentMode(
            pending: Boolean,
            dirty: Boolean,
            animating: Boolean,
            selfProducing: Boolean,
            externalPresent: Boolean,
            intervalOk: Boolean,
            needsRepaint: Boolean,
        ): PresentMode = when {
            shouldDraw(pending, dirty, animating, selfProducing, externalPresent, intervalOk) ->
                PresentMode.DRAW
            needsRepaint -> PresentMode.REPEAT
            else -> PresentMode.IDLE
        }

        /**
         * 纯函数：这一帧要不要**重新合成**。**判据是「内容代次变没变」，不是「过没过时间」。**
         *
         * 这是踩了三次坑之后定下来的唯一正确口径：
         * - 旧实现 A（时间阈值）：GL 线程迟到 1ms 就让 `k+2` 号回调的实测间隔掉到阈值下被跳过，
         *   该帧滑到 `k+3`（+50ms）才画，每秒混 3~5 个 41~50ms 尖峰 → 画面顿挫。
         * - 旧实现 B（按需渲染 WHEN_DIRTY + 节流）：每次 `requestRender` 只换一次回调，
         *   回调被跳过就是**永久丢帧**（没有下一次补偿），必然卡。
         * - 旧实现 C（按 animating 放行）：`animating=true` 时源没新帧也每 33ms 重画旧纹理，
         *   录进编码器就是「渲染出一帧，然后一直插它」。
         *
         * 现在的规则（**出场节拍与内容代次彻底解耦**）：
         * 1. [dirty]（布局 / 参数变化）→ **立刻画**，不受节流；
         * 2. [externalPresent]（布局有视频 / 摄像头源）→ **每个回调都画**，出场恒为屏幕刷新率。
         *    内容由源按自己的节奏换，屏幕按固定 16.7ms 换，两者互不牵制；
         *    30fps 内容自然显示两次，永不出现「33ms / 50ms 交替」的空拍；
         * 3. 纯程序化布局：[animating]（uTime）/ [selfProducing]（CPU 自产纹理）→ 内容每帧都变，
         *    按 [intervalOk] 节流（[maxFps]），避免 60 次/秒空转；
         * 4. 以上都没有 → **内容没变，绝不重画**（静态页零空转）。
         *
         * 注意第 2 条产生的「显示层重复帧」不会进编码器：录制只在内容代次变化时喂帧。
         * */
        fun shouldDraw(
            pending: Boolean,
            dirty: Boolean,
            animating: Boolean,
            selfProducing: Boolean,
            externalPresent: Boolean,
            intervalOk: Boolean,
        ): Boolean {
            // 结构 / 参数变化：立刻生效，等下一格会让人误以为按钮没响应。
            if (dirty) return true

            // 布局里有外部帧源（视频 / 摄像头）→ **每个回调都出场，恒定 60Hz**。
            //
            // 这是第四条错路，也是「视频都会卡」的直接原因：把「新帧到达」也压进
            // intervalOk 等槽位。源在 33.3ms 周期上产帧、槽位在 16.7ms 网格上开启，
            // 两个周期只差不可通约的一点点，相位会缓慢漂移；一旦某帧落在
            // 「刚画完的下一个回调」，它就要多等一格，于是出场间隔在 33ms 与 50ms
            // 之间来回跳（1 帧 / 2 帧的节拍突变），肉眼就是卡顿。
            //
            // 正确做法是「内容按源的节奏更新，出场按屏幕的节奏固定」：
            // 30fps 内容在 60Hz 屏上每帧自然显示两次，出场节拍恒为 16.7ms，
            // 无论源什么时候到都不会出现 50ms 的空拍。多画的那些帧内容与上一帧相同，
            // **只在显示层重复，不送编码器**（见 GpuCompositor 第 6 步），
            // 所以不会再出现「重复插帧」。
            if (externalPresent) return true

            // 纯程序化 / 动画布局：内容自产，按 maxFps 节流避免 60 次/秒空转。
            if (!pending && !animating && !selfProducing) return false
            return intervalOk
        }
    }
}

/**
 * 一帧的实测指标（GL 线程采集，主线程展示）。
 *
 * 这些数字是「优化延迟 / 内存 / CPU / GPU 占用 / 功耗」的验收口径，不是估算：
 * - [cpuFrameMs]：GL 线程这一帧从进入到返回的墙钟时间（含 GLES 提交，**不含** GPU 执行）；
 * - [gpuFrameMs]：GL_EXT_disjoint_timer_query 实测的 GPU 执行时间，-1 = 设备不支持；
 * - [cpuPercent] / [pssKb] / [graphicsPssKb] / [nativeHeapKb] / [powerMw]：系统级采样；
 * - [textureBytes]：库自己持有的 FBO + 纹理字节（不含 BufferQueue）；
 * - [latencyP50Ms] / [latencyP95Ms] / [jitterP95Ms]：C++ 帧时钟出的延迟与抖动分布；
 * - [droppedFrames]：生产者入队时队列已满的次数（背压 / 掉帧信号）。
 *
 * [powerMw] 是**整机**瞬时功率（µA × mV），只适合同机 A/B 对比，不是本库单独的功耗。
 */
data class GpuStats(
    val fps: Float = 0f,
    val cpuFrameMs: Float = 0f,
    val drawCalls: Int = 0,
    val gpuPasses: Int = 0,
    val textureBytes: Long = 0L,
    val latencyP50Ms: Float = 0f,
    val latencyP95Ms: Float = 0f,
    val droppedFrames: Int = 0,
    val gpuFrameMs: Float = -1f,
    val cpuPercent: Float = 0f,
    val pssKb: Int = 0,
    val graphicsPssKb: Int = 0,
    val nativeHeapKb: Long = 0L,
    val powerMw: Float = -1f,
    val jitterP95Ms: Float = 0f,
    val resolutionScale: Float = 1f,
    val encodedFrames: Int = 0,
) {
    /** HUD 第一行：保持固定单行，文本换行会带动 GLSurfaceView 高度抖动。 */
    fun summary(): String =
        "%.1f fps · cpu %.2f ms · %d draws · %d passes · %.1f MB · p50 %.1f / p95 %.1f ms · drop %d".format(
            fps, cpuFrameMs, drawCalls, gpuPasses, textureBytes / 1_048_576f,
            latencyP50Ms, latencyP95Ms, droppedFrames,
        )

    /** HUD 第二行：GPU / 进程 CPU / 内存 / 整机功率。不可用项如实写 n/a。 */
    fun systemLine(): String = buildString {
        append("gpu ")
        append(if (gpuFrameMs < 0f) "n/a" else "%.2f ms".format(gpuFrameMs))
        append(" · proc cpu %.0f%%".format(cpuPercent))
        append(" · pss %.1f MB (gfx %.1f)".format(pssKb / 1024f, graphicsPssKb / 1024f))
        append(" · native %.1f MB".format(nativeHeapKb / 1024f))
        append(" · ")
        append(if (powerMw < 0f) "power n/a" else "power %.0f mW".format(powerMw))
    }

    /** HUD 第三行：帧到达抖动 / 动态分辨率 / 已编码帧数。 */
    fun flowLine(): String =
        "jitter %.1f ms · scale %.2f · enc %d".format(jitterP95Ms, resolutionScale, encodedFrames)
}
