package com.arcana.gfx

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Debug
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import java.io.File
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
 * **CPU / 内存 / 功耗**采样器。
 *
 * 这些接口（`Debug.getMemoryInfo`、读 `/proc/self/stat`）都有毫秒级开销，
 * 所以只在 [GpuCompositor] 的 0.5s 指标窗口里调一次，绝不进每帧热路径；
 * 采样本身也就不会污染它要测的那个 CPU 数字。
 */
class SystemMeters(context: Context) {

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

    fun sample(): SystemSample {
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
        val voltageMv = try {
            appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
        } catch (_: Throwable) {
            -1
        }
        if (voltageMv <= 0) return -1f
        return abs(currentUa.toFloat()) * voltageMv / 1_000_000f
    }
}
