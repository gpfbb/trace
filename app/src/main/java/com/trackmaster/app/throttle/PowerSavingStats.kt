package com.trackmaster.app.throttle

import com.trackmaster.app.core.PowerMode

/**
 * PowerSavingStats - 简化的省电统计
 *
 * 注意: 详细的定位记录和性能指标统计已被移除，因为它们未持久化且未用于UI显示。
 * UI显示的统计数据来自 AppPreferences (已持久化) 和 GeoMath (缓存统计)。
 *
 * @deprecated 此类已简化，详细统计功能未被使用
 */
class PowerSavingStats {
    // 已移除未使用的详细统计追踪
    // 如需恢复详细统计，请从 git 历史恢复此前版本

    fun recordLocation(
        timestamp: Long,
        powerMode: PowerMode,
        shouldProcess: Boolean,
        level: Int,
        intervalMs: Long,
        inBoundaryBuffer: Boolean
    ) {
        // 已禁用：详细统计未持久化且未用于UI显示
    }

    fun recordPerformanceMetrics(
        memoryUsageKB: Long,
        cpuUsagePercent: Double,
        batteryLevelPercent: Int
    ) {
        // 已禁用：详细统计未持久化且未用于UI显示
    }

    fun getTotalLocations(): Int = 0
    fun getAverageMemoryUsageKB(): Long = 0L
    fun getUptimeMs(): Long = 0L
    fun clear() {}
    fun generateReport(): String = "详细统计已禁用"
}
