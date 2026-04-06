package com.trackmaster.app.throttle

import com.trackmaster.app.core.PowerMode

data class LocationRecord(
    val timestamp: Long,
    val powerMode: PowerMode,
    val shouldProcess: Boolean,
    val level: Int,
    val intervalMs: Long,
    val inBoundaryBuffer: Boolean
)

data class PowerModeStats(
    var totalLocations: Int = 0,
    var processedLocations: Int = 0,
    var totalIntervalMs: Long = 0L,
    val levelDistribution: MutableMap<Int, Int> = mutableMapOf(),
    var inBoundaryBufferCount: Int = 0
) {
    val averageIntervalMs: Long
        get() = if (totalLocations > 0) totalIntervalMs / totalLocations else 0L

    val processingRate: Double
        get() = if (totalLocations > 0) processedLocations.toDouble() / totalLocations else 0.0
}

data class PerformanceMetrics(
    val timestamp: Long,
    val memoryUsageKB: Long,
    val cpuUsagePercent: Double,
    val batteryLevelPercent: Int
)

class PowerSavingStats {
    private val locationRecords = mutableListOf<LocationRecord>()
    private val modeStatsMap = mutableMapOf<PowerMode, PowerModeStats>()
    private val performanceMetrics = mutableListOf<PerformanceMetrics>()
    private var startTime: Long = System.currentTimeMillis()
    private var totalMemoryAllocatedKB: Long = 0L
    private var memorySampleCount: Int = 0

    init {
        PowerMode.values().forEach { mode ->
            modeStatsMap[mode] = PowerModeStats()
        }
    }

    fun recordLocation(
        timestamp: Long,
        powerMode: PowerMode,
        shouldProcess: Boolean,
        level: Int,
        intervalMs: Long,
        inBoundaryBuffer: Boolean
    ) {
        val record = LocationRecord(
            timestamp = timestamp,
            powerMode = powerMode,
            shouldProcess = shouldProcess,
            level = level,
            intervalMs = intervalMs,
            inBoundaryBuffer = inBoundaryBuffer
        )
        locationRecords.add(record)

        val modeStats = modeStatsMap[powerMode] ?: PowerModeStats()
        modeStats.totalLocations++
        if (shouldProcess) modeStats.processedLocations++
        modeStats.totalIntervalMs += intervalMs
        modeStats.levelDistribution[level] = (modeStats.levelDistribution[level] ?: 0) + 1
        if (inBoundaryBuffer) modeStats.inBoundaryBufferCount++
        modeStatsMap[powerMode] = modeStats
    }

    fun recordPerformanceMetrics(
        memoryUsageKB: Long,
        cpuUsagePercent: Double,
        batteryLevelPercent: Int
    ) {
        val metrics = PerformanceMetrics(
            timestamp = System.currentTimeMillis(),
            memoryUsageKB = memoryUsageKB,
            cpuUsagePercent = cpuUsagePercent,
            batteryLevelPercent = batteryLevelPercent
        )
        performanceMetrics.add(metrics)
        totalMemoryAllocatedKB += memoryUsageKB
        memorySampleCount++
    }

    fun getModeStats(mode: PowerMode): PowerModeStats? = modeStatsMap[mode]

    fun getAllModeStats(): Map<PowerMode, PowerModeStats> = modeStatsMap.toMap()

    fun getLocationRecords(): List<LocationRecord> = locationRecords.toList()

    fun getPerformanceMetrics(): List<PerformanceMetrics> = performanceMetrics.toList()

    fun getTotalLocations(): Int = locationRecords.size

    fun getAverageMemoryUsageKB(): Long = if (memorySampleCount > 0) {
        totalMemoryAllocatedKB / memorySampleCount
    } else 0L

    fun getUptimeMs(): Long = System.currentTimeMillis() - startTime

    fun clear() {
        locationRecords.clear()
        performanceMetrics.clear()
        modeStatsMap.clear()
        PowerMode.values().forEach { mode ->
            modeStatsMap[mode] = PowerModeStats()
        }
        startTime = System.currentTimeMillis()
        totalMemoryAllocatedKB = 0L
        memorySampleCount = 0
    }

    fun generateReport(): String {
        val sb = StringBuilder()
        sb.appendLine("=== 省电算法统计报告 ===")
        sb.appendLine("运行时间: ${getUptimeMs() / 1000} 秒")
        sb.appendLine("总定位次数: ${getTotalLocations()}")
        sb.appendLine()

        PowerMode.values().forEach { mode ->
            val stats = modeStatsMap[mode]
            if (stats != null && stats.totalLocations > 0) {
                sb.appendLine("--- 模式: $mode ---")
                sb.appendLine("  总定位: ${stats.totalLocations}")
                sb.appendLine("  处理定位: ${stats.processedLocations}")
                sb.appendLine("  处理率: %.2f%%".format(stats.processingRate * 100))
                sb.appendLine("  平均间隔: ${stats.averageIntervalMs} ms")
                sb.appendLine("  等级分布: ${stats.levelDistribution}")
                sb.appendLine("  边界缓冲区次数: ${stats.inBoundaryBufferCount}")
                sb.appendLine()
            }
        }

        if (performanceMetrics.isNotEmpty()) {
            sb.appendLine("--- 性能指标 ---")
            sb.appendLine("  平均内存使用: ${getAverageMemoryUsageKB()} KB")
            val latest = performanceMetrics.last()
            sb.appendLine("  最新电池: ${latest.batteryLevelPercent}%")
            sb.appendLine("  最新CPU: %.2f%%".format(latest.cpuUsagePercent))
        }

        return sb.toString()
    }
}
