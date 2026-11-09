package com.arcana.gfx

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * 一次系统级采样。所有数字都是**本机实测**，不是按分辨率推算的估值。
 *
 * @param cpuPercent 进程 CPU 占用（utime+stime 增量 / 墙钟增量，多核可 >100%）
 * @param pssKb / nativePssKb / graphicsPssKb `Debug.MemoryInfo` 的 PSS 分项（KB）
 * @param nativeHeapKb `Debug.getNativeHeapAllocatedSize()`（KB），C++ 侧分配的量
 * @param powerMw 整机瞬时功率估算（µA × mV），-1 = 设备不提供电流读数
 */
data class SystemSample(
    val cpuPercent: Float = 0f,
    val pssKb: Int = 0,
    val nativePssKb: Int = 0,
    val graphicsPssKb: Int = 0,
    val nativeHeapKb: Long = 0L,
    val powerMw: Float = -1f,
)

/**
 * **CPU / 内存 / 功耗**采样器，跑在自己的后台线程上。
 *
 * `Debug.getMemoryInfo()`、`registerReceiver(null, ACTION_BATTERY_CHANGED)` 和读
 * `/proc/self/stat` 都是同步 binder / 文件 IO，**真机单次约 50ms**。以前虽然只在
 * 0.5s 指标窗口调一次，但那次调用就在 GL 线程里：每半秒直接卡掉 1~2 个 vsync，
 * 画面规律性地顿一下，而 `cpuFrameMs` 又因为先算完而漏记这 50ms，指标完全看不出来。
 *
 * 现在 [requestSample] 只投递任务并立即返回，[latest] 读上一次结果（零系统调用）。
 * 指标天然是 0.5s 粒度，晚一个窗口不影响口径，但绝不能让采样阻塞渲染线程。
 */
class SystemMeters(context: Context) : AutoCloseable {

    private val worker = HandlerThread("gfx-meters").apply { start() }
    private val handler = Handler(worker.looper)
    private val sampling = AtomicBoolean(false)

    /** 上一次后台采样结果，GL 线程只读这个引用。 */
    @Volatile
    private var cached = SystemSample()

    /** 上一次采样的真实耗时（ms），仅供 HUD / 排错观察，不参与指标计算。 */
    @Volatile
    var lastCostMs: Float = 0f
        private set

    /** 投递一次采样，立即返回；上一次没做完就跳过，绝不堆积任务。 */
    fun requestSample() {
        if (!sampling.compareAndSet(false, true)) return
        handler.post {
            val t0 = System.nanoTime()
            try {
                cached = doSample()
            } catch (_: Throwable) {
                // 采样失败保留上一次结果，不能把异常带进渲染线程。
            } finally {
                lastCostMs = (System.nanoTime() - t0) / 1_000_000f
                sampling.set(false)
            }
        }
    }

    fun latest(): SystemSample = cached

    override fun close() {
        worker.quitSafely()
    }

    private val appContext = context.applicationContext
    private val battery = appContext.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
    private val memoryInfo = Debug.MemoryInfo()

    private val ticksPerSecond = try {
        Os.sysconf(OsConstants._SC_CLK_TCK).takeIf { it > 0 } ?: 100L
    } catch (_: Throwable) {
        100L
    }

    private var lastCpuTicks = 0L
    private var lastWallMs = 0L
    private var lastCpuPercent = 0f
    private var cachedVoltageMv = -1
    private var voltageQueries = 0

    private fun doSample(): SystemSample {
        val nowMs = SystemClock.elapsedRealtime()
        val ticks = readCpuTicks()
        if (lastWallMs != 0L && ticks >= lastCpuTicks && nowMs > lastWallMs) {
            val cpuSeconds = (ticks - lastCpuTicks).toFloat() / ticksPerSecond
            val wallSeconds = (nowMs - lastWallMs) / 1000f
            lastCpuPercent = cpuSeconds / wallSeconds * 100f
        }
        lastCpuTicks = ticks
        lastWallMs = nowMs

        var pss = 0
        var nativePss = 0
        var graphicsPss = 0
        try {
            Debug.getMemoryInfo(memoryInfo)
            pss = memoryInfo.totalPss
            nativePss = memoryInfo.nativePss
            // graphics 分项没有公开字段，走 API 23+ 的 memory stat（KB 字符串）。
            graphicsPss = memoryInfo.getMemoryStat("summary.graphics")?.toIntOrNull() ?: 0
        } catch (_: Throwable) {
            // 个别 ROM 上 memtrack 会失败：内存分项记 0，不影响其它指标。
        }

        val nativeHeapKb = try {
            Debug.getNativeHeapAllocatedSize() / 1024L
        } catch (_: Throwable) {
            0L
        }

        return SystemSample(
            cpuPercent = lastCpuPercent,
            pssKb = pss,
            nativePssKb = nativePss,
            graphicsPssKb = graphicsPss,
            nativeHeapKb = nativeHeapKb,
            powerMw = readPowerMw(),
        )
    }

    /** /proc/self/stat 的第 14/15 字段 utime+stime，单位 clock ticks。 */
    private fun readCpuTicks(): Long {
        return try {
            val stat = File("/proc/self/stat").readText()
            // comm 字段可能含空格和 ')'，从最后一个 ')' 之后开始数才不会错位。
            val end = stat.lastIndexOf(')')
            if (end < 0) {
                0L
            } else {
                val fields = stat.substring(end + 2).split(' ')
                // fields[0] 是 stat 的第 3 字段(state)：utime=14 → index 11，stime=15 → index 12。
                fields[11].toLong() + fields[12].toLong()
            }
        } catch (_: Throwable) {
            0L
        }
    }

    /**
     * `BATTERY_PROPERTY_CURRENT_NOW` 是 µA（充电时为负），`EXTRA_VOLTAGE` 是 mV。
     * µA × mV = 1e-9 W = 1e-6 mW → mW = µA * mV / 1e6。
     * 这是**整机**功率，包含屏幕与其它进程，只能用于同机 A/B 对比，不是本库单独的功耗。
     */
    private fun readPowerMw(): Float {
        val currentUa = battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            ?: return -1f
        if (currentUa == 0 || currentUa == Int.MIN_VALUE) return -1f
        // 电压变化很慢，不值得每 0.5s 走一次同步 binder；每 10 次采样刷新一次。
        if (cachedVoltageMv <= 0 || voltageQueries++ % 10 == 0) {
            cachedVoltageMv = try {
                appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                    ?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
            } catch (_: Throwable) {
                -1
            }
        }
        val v = cachedVoltageMv
        if (v <= 0) return -1f
        return abs(currentUa.toFloat()) * v / 1_000_000f
    }
}
