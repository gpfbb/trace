package com.trackmaster.app.throttle

import android.content.Context
import android.os.BatteryManager
import android.os.Debug
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class PerformanceMonitor(
    private val context: Context,
    private val stats: PowerSavingStats,
    private val sampleIntervalMs: Long = 5000L
) {
    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private var monitoringJob: Job? = null
    private var lastCpuTime = 0L
    private var lastUptime = 0L

    fun startMonitoring() {
        stopMonitoring()
        monitoringJob = scope.launch {
            while (true) {
                collectAndRecordMetrics()
                delay(sampleIntervalMs)
            }
        }
    }

    fun stopMonitoring() {
        monitoringJob?.cancel()
        monitoringJob = null
    }

    fun collectAndRecordMetrics() {
        val memoryUsageKB = getMemoryUsageKB()
        val cpuUsagePercent = getCpuUsagePercent()
        val batteryLevelPercent = getBatteryLevel()

        stats.recordPerformanceMetrics(
            memoryUsageKB = memoryUsageKB,
            cpuUsagePercent = cpuUsagePercent,
            batteryLevelPercent = batteryLevelPercent
        )
    }

    private fun getMemoryUsageKB(): Long {
        val runtime = Runtime.getRuntime()
        val usedMemory = runtime.totalMemory() - runtime.freeMemory()
        return usedMemory / 1024L
    }

    private fun getCpuUsagePercent(): Double {
        val currentCpuTime = Debug.threadCpuTimeNanos()
        val currentUptime = System.nanoTime()

        if (lastCpuTime == 0L || lastUptime == 0L) {
            lastCpuTime = currentCpuTime
            lastUptime = currentUptime
            return 0.0
        }

        val cpuTimeDiff = currentCpuTime - lastCpuTime
        val uptimeDiff = currentUptime - lastUptime

        lastCpuTime = currentCpuTime
        lastUptime = currentUptime

        return if (uptimeDiff > 0) {
            (cpuTimeDiff.toDouble() / uptimeDiff.toDouble()) * 100.0
        } else {
            0.0
        }
    }

    private fun getBatteryLevel(): Int {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        return batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 0
    }

    fun getDetailedMetrics(): DetailedMetrics {
        val runtime = Runtime.getRuntime()
        return DetailedMetrics(
            maxMemoryMB = runtime.maxMemory() / (1024 * 1024),
            totalMemoryMB = runtime.totalMemory() / (1024 * 1024),
            freeMemoryMB = runtime.freeMemory() / (1024 * 1024),
            usedMemoryMB = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024),
            availableProcessors = runtime.availableProcessors(),
            currentBatteryLevel = getBatteryLevel()
        )
    }
}

data class DetailedMetrics(
    val maxMemoryMB: Long,
    val totalMemoryMB: Long,
    val freeMemoryMB: Long,
    val usedMemoryMB: Long,
    val availableProcessors: Int,
    val currentBatteryLevel: Int
) {
    fun toReportString(): String {
        return """
            详细性能指标:
              最大可用内存: ${maxMemoryMB} MB
              已分配内存: ${totalMemoryMB} MB
              空闲内存: ${freeMemoryMB} MB
              已使用内存: ${usedMemoryMB} MB
              可用处理器: ${availableProcessors} 核
              当前电池: ${currentBatteryLevel}%
        """.trimIndent()
    }
}
