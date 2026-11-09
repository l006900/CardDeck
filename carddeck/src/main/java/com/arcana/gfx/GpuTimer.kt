package com.arcana.gfx

/**
 * **实测 GPU 帧耗时**（不是估算、不是"帧间隔减 CPU 时间"）。
 *
 * Android 的 Java GL 绑定没有暴露 `GL_EXT_disjoint_timer_query` 那一组入口，
 * 所以在 C++ 侧用 `eglGetProcAddress` 动态解析扩展函数，在 GLES2 上下文上就能用。
 * 三枚 query 轮换、只取已完成的结果，不调 `glFinish`、不阻塞 GPU 流水线。
 *
 * 设备不支持扩展时 [supported] 为 false、[pollMs] 返回 -1；HUD 应如实显示
 * 「GPU 计时不可用」，不要拿 CPU 帧耗时冒充 GPU 时间。
 *
 * 生命周期：[onGlCreated] 在 GL 线程调用；EGL 上下文失效时**只需丢弃引用**，
 * 旧 query 会随上下文由驱动回收，下次 [onGlCreated] 重新建。
 */
class GpuTimer : AutoCloseable {

    companion object {
        init {
            System.loadLibrary("arcana_frameclock")
        }
    }

    private var handle: Long = nativeCreate()

    /** 设备/驱动是否真的支持 disjoint timer query。 */
    var supported: Boolean = false
        private set

    /** GL 线程：上下文创建或重建后调用。 */
    fun onGlCreated() {
        supported = handle != 0L && nativeReset(handle)
    }

    /** GL 线程：一帧真正开始绘制前调用。三枚 query 都忙时返回 false，本帧不计时。 */
    fun begin(): Boolean = supported && nativeBegin(handle)

    /** GL 线程：一帧绘制结束后调用。 */
    fun end() {
        if (supported) nativeEnd(handle)
    }

    /**
     * GL 线程：取最近一次有效结果（毫秒）。
     * @return -1 = 扩展不可用、结果还没出来，或 GPU 发生 disjoint 本轮作废。
     */
    fun pollMs(): Float = if (supported) nativePoll(handle) else -1f

    /** 上下文失效：只丢引用，绝不在这里碰 GLES。 */
    fun onGlLost() {
        supported = false
    }

    /** 只释放 C++ 对象；GL 对象随 EGL 上下文回收，因此可在任意线程调用。 */
    override fun close() {
        if (handle != 0L) {
            nativeDestroy(handle)
            handle = 0L
        }
    }

    private external fun nativeCreate(): Long
    private external fun nativeReset(handle: Long): Boolean
    private external fun nativeBegin(handle: Long): Boolean
    private external fun nativeEnd(handle: Long)
    private external fun nativePoll(handle: Long): Float
    private external fun nativeDestroy(handle: Long)
}
