package com.trackmaster.app

import android.content.Context
import android.content.SharedPreferences
import com.trackmaster.app.core.PowerMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class MapProvider { AMAP, GOOGLE }
enum class AppLanguage { CHINESE, ENGLISH, MALAY }

class AppPreferences(context: Context) {

    companion object {
        private const val PREFS_NAME = "trackmaster_prefs"

        // 高级设置键
        private const val KEY_STATIONARY_THRESHOLD = "stationary_threshold"
        private const val KEY_BOUNDARY_BUFFER = "boundary_buffer"
        private const val KEY_DRIFT_THRESHOLD = "drift_threshold"
        private const val KEY_POWER_MODE = "power_mode"
        private const val KEY_FILL_ALPHA = "fill_alpha"

        // 省电统计键
        private const val KEY_TOTAL_LOCATIONS = "total_locations"
        private const val KEY_PROCESSED_LOCATIONS = "processed_locations"
        private const val KEY_LAST_RESET_DATE = "last_reset_date"

        // 用户体验键
        private const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"
        private const val KEY_AUTO_CLEANUP_DAYS = "auto_cleanup_days"
        // 地图类型：1=标准 2=卫星 3=夜景（对应 AMap.MAP_TYPE_ constants）
        private const val KEY_MAP_TYPE = "map_type"
        private const val KEY_MAP_PROVIDER = "map_provider"
        private const val KEY_APP_LANGUAGE = "app_language"
        private const val KEY_CAMERA_LAT = "camera_lat"
        private const val KEY_CAMERA_LNG = "camera_lng"
        private const val KEY_CAMERA_ZOOM = "camera_zoom"
        private const val KEY_HAS_CAMERA_POSITION = "has_camera_position"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ============================================================
    // 高级设置
    // ============================================================

    var stationaryThreshold: Double
        get() = prefs.getFloat(KEY_STATIONARY_THRESHOLD, 30.0f).toDouble()
        set(value) = prefs.edit().putFloat(KEY_STATIONARY_THRESHOLD, value.toFloat()).apply()

    var boundaryBuffer: Double
        get() = prefs.getFloat(KEY_BOUNDARY_BUFFER, 10.0f).toDouble()
        set(value) = prefs.edit().putFloat(KEY_BOUNDARY_BUFFER, value.toFloat()).apply()

    var driftThreshold: Double
        get() = prefs.getFloat(KEY_DRIFT_THRESHOLD, 30.0f).toDouble()
        set(value) = prefs.edit().putFloat(KEY_DRIFT_THRESHOLD, value.toFloat()).apply()

    var powerMode: PowerMode
        get() {
            val ordinal = prefs.getInt(KEY_POWER_MODE, PowerMode.BALANCED.ordinal)
            return PowerMode.values().getOrElse(ordinal) { PowerMode.BALANCED }
        }
        set(value) = prefs.edit().putInt(KEY_POWER_MODE, value.ordinal).apply()

    // 围栏填充透明度
    var fillAlpha: Float
        get() = prefs.getFloat(KEY_FILL_ALPHA, 0.3f)
        set(value) = prefs.edit().putFloat(KEY_FILL_ALPHA, value).apply()

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
        get() = prefs.getLong(KEY_LAST_RESET_DATE, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_RESET_DATE, value).apply()

    fun incrementLocations(shouldProcess: Boolean) {
        synchronized(prefs) {
            val total = prefs.getInt(KEY_TOTAL_LOCATIONS, 0) + 1
            val processed = if (shouldProcess) {
                prefs.getInt(KEY_PROCESSED_LOCATIONS, 0) + 1
            } else {
                prefs.getInt(KEY_PROCESSED_LOCATIONS, 0)
            }
            prefs.edit()
                .putInt(KEY_TOTAL_LOCATIONS, total)
                .putInt(KEY_PROCESSED_LOCATIONS, processed)
                .apply()
        }
    }

    fun resetStats() {
        totalLocations = 0
        processedLocations = 0
        lastResetDate = System.currentTimeMillis()
    }

    fun getSavedPercentage(): Double {
        // 如果从未重置过，返回0%
        if (lastResetDate == 0L) return 0.0

        return if (totalLocations > 0) {
            // 性能模式假设：每 2 秒一次定位（43200 次/12小时）
            // 计算从上次重置到现在的时间，估算性能模式会有多少次请求
            val elapsedMs = System.currentTimeMillis() - lastResetDate
            val elapsedHours = elapsedMs / (1000.0 * 60 * 60)
            val performanceModeEstimate = (elapsedHours * 1800).toLong() // 1800次/小时 = 每2秒1次

            // 如果估算值小于实际处理次数，使用实际处理次数作为基准
            val performanceTotal = performanceModeEstimate.coerceAtLeast(processedLocations.toLong())

            if (performanceTotal > 0) {
                val saved = performanceTotal - processedLocations.toLong()
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
    fun getPerformanceModeEstimate(): Long {
        // 如果从未重置过，返回实际处理次数
        if (lastResetDate == 0L) return processedLocations.toLong()

        val elapsedMs = System.currentTimeMillis() - lastResetDate
        val elapsedHours = elapsedMs / (1000.0 * 60 * 60)
        return (elapsedHours * 1800).toLong().coerceAtLeast(processedLocations.toLong())
    }

    // ============================================================
    // 用户体验
    // ============================================================

    /**
     * 是否已完成引导
     * 用于判断首次启动时是否显示引导页
     */
    var isOnboardingCompleted: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDING_COMPLETED, false)
        set(value) = prefs.edit().putBoolean(KEY_ONBOARDING_COMPLETED, value).apply()

    /**
     * 日志自动清理天数
     * 超过此天数的日志事件将在下次清理时删除
     */
    var autoCleanupDays: Int
        get() = prefs.getInt(KEY_AUTO_CLEANUP_DAYS, 7)
        set(value) = prefs.edit().putInt(KEY_AUTO_CLEANUP_DAYS, value).apply()

    // 地图类型：1=标准 2=卫星 3=夜景
    var mapType: Int
        get() = prefs.getInt(KEY_MAP_TYPE, 1)
        set(value) = prefs.edit().putInt(KEY_MAP_TYPE, value).apply()

    private val _mapProviderFlow = MutableStateFlow(
        prefs.getString(KEY_MAP_PROVIDER, null)?.let { MapProvider.valueOf(it) } ?: MapProvider.AMAP
    )
    val mapProviderFlow: StateFlow<MapProvider> = _mapProviderFlow.asStateFlow()

    var mapProvider: MapProvider
        get() = prefs.getString(KEY_MAP_PROVIDER, null)?.let { MapProvider.valueOf(it) } ?: MapProvider.AMAP
        set(value) {
            prefs.edit().putString(KEY_MAP_PROVIDER, value.name).apply()
            _mapProviderFlow.value = value
        }

    val sharedPreferences: SharedPreferences get() = prefs

    // 相机位置（退出地图时保存，进入时恢复）
    var hasCameraPosition: Boolean
        get() = prefs.getBoolean(KEY_HAS_CAMERA_POSITION, false)
        set(value) = prefs.edit().putBoolean(KEY_HAS_CAMERA_POSITION, value).apply()

    var cameraLatitude: Double
        get() = prefs.getFloat(KEY_CAMERA_LAT, 0f).toDouble()
        set(value) = prefs.edit().putFloat(KEY_CAMERA_LAT, value.toFloat()).apply()

    var cameraLongitude: Double
        get() = prefs.getFloat(KEY_CAMERA_LNG, 0f).toDouble()
        set(value) = prefs.edit().putFloat(KEY_CAMERA_LNG, value.toFloat()).apply()

    var cameraZoom: Float
        get() = prefs.getFloat(KEY_CAMERA_ZOOM, 15f)
        set(value) = prefs.edit().putFloat(KEY_CAMERA_ZOOM, value).apply()

    var appLanguage: AppLanguage
        get() {
            val name = prefs.getString(KEY_APP_LANGUAGE, null)
            return if (name != null) AppLanguage.valueOf(name) else AppLanguage.ENGLISH
        }
        set(value) = prefs.edit().putString(KEY_APP_LANGUAGE, value.name).apply()
}
