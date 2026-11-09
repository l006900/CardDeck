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
 * 帧调度策略。
 *
 * [maxFps] 是硬上限：即使 `RENDERMODE_CONTINUOUSLY` 每帧回调，未到间隔也直接 return，
 * 省下的是整帧的填充率与 draw call（GL 线程只多一次时间比较）。
 * [mode] = [RenderMode.ON_DEMAND] 时配合 `RENDERMODE_WHEN_DIRTY`，连回调都不再触发。
 */
data class RenderPolicy(
    val mode: RenderMode = RenderMode.CONTINUOUS,
    val maxFps: Int = 60,
) {
    init {
        require(maxFps in 1..120) { "maxFps 必须在 1..120" }
    }

    fun frameIntervalNs(): Long = 1_000_000_000L / maxFps
}

/**
 * 一帧的实测指标（GL 线程采集，主线程展示）。
 *
 * 这些数字是「优化延迟 / 内存 / CPU / GPU 占用」的验收口径，不是估算：
 * - [cpuFrameMs]：GL 线程这一帧从进入到返回的墙钟时间（含 GLES 提交，不含 GPU 执行）；
 * - [gpuPasses] / [drawCalls]：实际执行的 pass 数与 draw call 数；
 * - [textureBytes]：库自己持有的 FBO + 纹理字节（不含 BufferQueue）；
 * - [latencyP50Ms] / [latencyP95Ms]：帧到达 → 被消费的延迟分布，来自 C++ 帧时钟；
 * - [droppedFrames]：生产者入队时队列已满的次数（背压 / 掉帧信号）。
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
) {
    /** HUD 用的紧凑一行。 */
    fun summary(): String =
        "%.1f fps · cpu %.2f ms · %d draws · %d passes · %.1f MB · p50 %.1f / p95 %.1f ms · drop %d".format(
            fps, cpuFrameMs, drawCalls, gpuPasses, textureBytes / 1_048_576f,
            latencyP50Ms, latencyP95Ms, droppedFrames,
        )
}
