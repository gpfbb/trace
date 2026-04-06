package com.trackmaster.app

import android.content.Context
import android.content.SharedPreferences
import com.trackmaster.app.core.PowerMode

class AppPreferences(context: Context) {
    
    companion object {
        private const val PREFS_NAME = "trackmaster_prefs"
        
        // 高级设置键
        private const val KEY_STATIONARY_THRESHOLD = "stationary_threshold"
        private const val KEY_BOUNDARY_BUFFER = "boundary_buffer"
        private const val KEY_DRIFT_THRESHOLD = "drift_threshold"
        private const val KEY_POWER_MODE = "power_mode"
        
        // 省电统计键
        private const val KEY_TOTAL_LOCATIONS = "total_locations"
        private const val KEY_PROCESSED_LOCATIONS = "processed_locations"
        private const val KEY_LAST_RESET_DATE = "last_reset_date"
    }
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    // ============================================================
    // 高级设置
    // ============================================================
    
    var stationaryThreshold: Double
        get() = prefs.getFloat(KEY_STATIONARY_THRESHOLD, 30.0f).toDouble()
        set(value) = prefs.edit().putFloat(KEY_STATIONARY_THRESHOLD, value.toFloat()).apply()
    
    var boundaryBuffer: Double
        get() = prefs.getFloat(KEY_BOUNDARY_BUFFER, 200.0f).toDouble()
        set(value) = prefs.edit().putFloat(KEY_BOUNDARY_BUFFER, value.toFloat()).apply()
    
    var driftThreshold: Double
        get() = prefs.getFloat(KEY_DRIFT_THRESHOLD, 50.0f).toDouble()
        set(value) = prefs.edit().putFloat(KEY_DRIFT_THRESHOLD, value.toFloat()).apply()
    
    var powerMode: PowerMode
        get() {
            val ordinal = prefs.getInt(KEY_POWER_MODE, PowerMode.BALANCED.ordinal)
            return PowerMode.values().getOrElse(ordinal) { PowerMode.BALANCED }
        }
        set(value) = prefs.edit().putInt(KEY_POWER_MODE, value.ordinal).apply()
    
    // ============================================================
    // 省电统计
    // ============================================================
    
    var totalLocations: Int
        get() = prefs.getInt(KEY_TOTAL_LOCATIONS, 0)
        set(value) = prefs.edit().putInt(KEY_TOTAL_LOCATIONS, value).apply()
    
    var processedLocations: Int
        get() = prefs.getInt(KEY_PROCESSED_LOCATIONS, 0)
        set(value) = prefs.edit().putInt(KEY_PROCESSED_LOCATIONS, value).apply()
    
    var lastResetDate: Long
        get() = prefs.getLong(KEY_LAST_RESET_DATE, System.currentTimeMillis())
        set(value) = prefs.edit().putLong(KEY_LAST_RESET_DATE, value).apply()
    
    fun incrementLocations(shouldProcess: Boolean) {
        // totalLocations = 所有收到的定位（包括被忽略的）
        // processedLocations = 实际处理的定位
        totalLocations++
        if (shouldProcess) {
            processedLocations++
        }
    }
    
    fun resetStats() {
        totalLocations = 0
        processedLocations = 0
        lastResetDate = System.currentTimeMillis()
    }
    
    fun getSavedPercentage(): Double {
        return if (totalLocations > 0) {
            // 性能模式假设：每 2 秒一次定位（43200 次/12小时）
            // 计算从上次重置到现在的时间，估算性能模式会有多少次请求
            val elapsedMs = System.currentTimeMillis() - lastResetDate
            val elapsedHours = elapsedMs / (1000.0 * 60 * 60)
            val performanceModeEstimate = (elapsedHours * 1800).toInt() // 1800次/小时 = 每2秒1次
            
            // 如果估算值小于实际处理次数，使用实际处理次数作为基准
            val performanceTotal = performanceModeEstimate.coerceAtLeast(processedLocations)
            
            if (performanceTotal > 0) {
                val saved = performanceTotal - processedLocations
                (saved.toDouble() / performanceTotal * 100).coerceIn(0.0, 100.0)
            } else {
                0.0
            }
        } else {
            0.0
        }
    }
    
    /**
     * 获取性能模式估算的请求次数
     */
    fun getPerformanceModeEstimate(): Int {
        val elapsedMs = System.currentTimeMillis() - lastResetDate
        val elapsedHours = elapsedMs / (1000.0 * 60 * 60)
        return (elapsedHours * 1800).toInt().coerceAtLeast(processedLocations)
    }
}
