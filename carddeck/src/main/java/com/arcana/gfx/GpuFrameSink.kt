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
    /** @param sourceW / sourceH 离屏合成纹理尺寸（可能小于屏幕，见动态分辨率）。 */
    fun onFrame(texture: Int, sourceW: Int, sourceH: Int, ptsNs: Long)

    /** 已成功送进编码器的帧数，用于 HUD 与验收。 */
    fun framesWritten(): Int
}
