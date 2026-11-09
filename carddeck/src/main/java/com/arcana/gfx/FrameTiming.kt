package com.arcana.gfx

/**
 * 帧延迟时钟（JNI 到 C++ 无锁 SPSC 环形队列）。
 *
 * **为什么用 C++ 而不是 Kotlin 的 `ArrayDeque`**：
 * - 生产者在解码线程、消费者在 GL 线程，两条线程每帧都要碰队列；
 *   Java 队列要么加锁（抖动），要么每帧分配节点（GC 尖刺，正好会污染延迟统计本身）；
 * - native 侧是固定容量数组 + `std::atomic` acquire/release，热路径零 malloc、零锁、
 *   零 JNI 对象分配，测出来的 p95 才真是管线的延迟而不是 GC 的延迟。
 *
 * 用法：`frameSink = { timing.push(it) }`（解码线程）→ `timing.drain(renderNs)`（GL 线程）。
 *
 * [drain] 返回的数组：
 * `[本帧消费帧数, p50Ms, p95Ms, maxMs, 本窗口丢弃帧数, 帧间隔抖动 p95-p50(ms)]`。
 *
 * 最后一项是**播放流畅度**指标：解码/采集帧到达间隔的长尾抖动，
 * 恒定 30fps 时接近 0，掉帧或解码抖动时显著变大，比平均 fps 更能反映"卡不卡"。
 */
class FrameTiming(capacity: Int = 240) : AutoCloseable {

    companion object {
        init {
            System.loadLibrary("arcana_frameclock")
        }
    }

    private var handle: Long = nativeCreate(capacity.coerceIn(16, 4096))
    private val out = FloatArray(6)

    /** 生产者线程：记录一帧到达。返回 false 表示队列已满（背压，计入 dropped）。 */
    fun push(arrivalNs: Long): Boolean =
        handle != 0L && nativePush(handle, arrivalNs)

    /** GL 线程：消费所有尚未呈现的帧并算延迟分布。 */
    fun drain(renderNs: Long): FloatArray {
        if (handle != 0L) nativeDrain(handle, renderNs, out)
        return out
    }

    override fun close() {
        if (handle != 0L) {
            nativeDestroy(handle)
            handle = 0L
        }
    }

    private external fun nativeCreate(capacity: Int): Long
    private external fun nativePush(handle: Long, arrivalNs: Long): Boolean
    private external fun nativeDrain(handle: Long, renderNs: Long, out: FloatArray)
    private external fun nativeDestroy(handle: Long)
}
