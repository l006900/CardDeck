package com.arcana.gfx

/**
 * 合成结果的一帧接出点。
 *
 * [GpuCompositor] 在**GL 线程**、画完屏幕之后回调；`texture` 是当前 EGL share group
 * 里的 `GL_TEXTURE_2D`，尺寸为离屏合成尺寸（可能小于屏幕，见 [RenderPolicy.resolutionScale]）。
 *
 * 实现约定（[VideoRecorder] 就是这么做的）：
 * - 若要用自己的 EGL context 采样这张纹理，必须把它建成当前 context 的**共享 context**；
 * - 调用结束前恢复原来的 EGL context / draw / read surface，并禁用自己启用的顶点属性数组，
 *   否则会污染下一帧合成器的 GL 状态；
 * - 不要在这里做阻塞式 CPU 拷贝（会把 GL 线程拖成编码线程）；输出 drain 请放独立线程。
 */
interface GpuFrameSink {
    /**
     * 录制目标帧率。合成器据此做**降采样**：当源帧率高于本值时（例如 60fps 源、
     * 30fps 录制），丢弃来得太快的帧，把输出锁在目标帧率；源帧率 ≤ 本值时逐帧如实编码。
     * 同时它也是 PTS 时间基准的单位（间隔 = 1e9 / targetFps）。合成器录制时走「源驱动」
     * （WHEN_DIRTY），只在源来了新帧时才喂一帧，从根本上杜绝把未更新的旧纹理反复送
     * 进编码器导致的「渲染一帧然后一直插它」。
     */
    val targetFps: Int

    /**
     * @param ptsNs 这一帧的呈现时间戳（纳秒）。录制实现应把它当作 [onFrame] 的 PTS
     *   直接喂给 `eglPresentationTimeANDROID`，且必须单调递增、间隔均匀（由合成器保证）。
     * @param sourceW / sourceH 离屏合成纹理尺寸（可能小于屏幕，见动态分辨率）。
     */
    fun onFrame(texture: Int, sourceW: Int, sourceH: Int, ptsNs: Long)

    /** 已成功送进编码器的帧数，用于 HUD 与验收。 */
    fun framesWritten(): Int
}
