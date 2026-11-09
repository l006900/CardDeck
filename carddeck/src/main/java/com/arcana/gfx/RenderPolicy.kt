package com.arcana.gfx

/** 渲染调度模式。 */
enum class RenderMode {
    /** 每帧都跑（动态 shader、连续动画）。 */
    CONTINUOUS,

    /**
     * 只在「新帧到达 / 参数变化 / 显式 requestRender」时跑一次。
     * 视频 30fps 的页面不再空转 60 次 GPU，是功耗优化最有效的一刀。
     */
    ON_DEMAND,
}

/**
 * 帧调度 + 资源预算策略。
 *
 * [maxFps] 是硬上限：即使 `RENDERMODE_CONTINUOUSLY` 每帧回调，未到间隔也直接 return，
 * 省下的是整帧的填充率与 draw call（GL 线程只多一次时间比较）。
 * [mode] = [RenderMode.ON_DEMAND] 时配合 `RENDERMODE_WHEN_DIRTY`，连回调都不再触发。
 *
 * [resolutionScale] 让离屏合成按低于屏幕的分辨率跑，最后一步上采样输出：
 * FBO 显存和填充率按 scale² 下降，是显存/功耗预算里最划算的一刀。
 * [maxTextureBytes] 非 0 时，合成器会在预算不够时自动往下选档（0.75 / 0.5 / 0.35），
 * 实际生效的档位会出现在 [GpuStats.resolutionScale] 里，不静默改参数。
 */
data class RenderPolicy(
    val mode: RenderMode = RenderMode.CONTINUOUS,
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
    val mode: RenderMode = RenderMode.CONTINUOUS,
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
