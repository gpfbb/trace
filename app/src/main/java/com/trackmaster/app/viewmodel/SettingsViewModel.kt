package com.trackmaster.app.viewmodel

import android.app.Application
import androidx.lifecycle.ViewModel
import com.trackmaster.app.AppPreferences
import com.trackmaster.app.PermissionManager
import com.trackmaster.app.TrackMasterApplication
import com.trackmaster.app.core.GeoMath
import com.trackmaster.app.core.PowerMode
import com.trackmaster.app.throttle.PowerSavingStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 设置 ViewModel - 管理设置状态
 *
 * 职责：
 * - 管理电源模式选择
 * - 管理高级设置（静止阈值、边界缓冲、漂移阈值）
 * - 管理省电统计
 * - 管理权限状态
 */
class SettingsViewModel(
    private val application: Application
) : ViewModel() {

    private val app: TrackMasterApplication
        get() = application as TrackMasterApplication

    private val permissionManager = PermissionManager(application)
    private val prefs: AppPreferences
        get() = app.appPreferences

    // ============================================================
    // 权限状态
    // ============================================================

    private val _permissionStatus = MutableStateFlow(permissionManager.checkPermissions())
    val permissionStatus: StateFlow<PermissionManager.PermissionStatus> = _permissionStatus

    // ============================================================
    // 电源模式
    // ============================================================

    private val _selectedPowerMode = MutableStateFlow(prefs.powerMode)
    val selectedPowerMode: StateFlow<PowerMode> = _selectedPowerMode

    // ============================================================
    // 高级设置
    // ============================================================

    private val _stationaryThreshold = MutableStateFlow(prefs.stationaryThreshold)
    val stationaryThreshold: StateFlow<Double> = _stationaryThreshold

    private val _boundaryBuffer = MutableStateFlow(prefs.boundaryBuffer)
    val boundaryBuffer: StateFlow<Double> = _boundaryBuffer

    private val _driftThreshold = MutableStateFlow(prefs.driftThreshold)
    val driftThreshold: StateFlow<Double> = _driftThreshold

    // ============================================================
    // 省电统计
    // ============================================================

    private val _totalLocations = MutableStateFlow(prefs.totalLocations)
    val totalLocations: StateFlow<Int> = _totalLocations

    private val _processedLocations = MutableStateFlow(prefs.processedLocations)
    val processedLocations: StateFlow<Int> = _processedLocations

    private val _cacheStats = MutableStateFlow<GeoMath.CacheStats?>(null)
    val cacheStats: StateFlow<GeoMath.CacheStats?> = _cacheStats

    private val _powerSavingStats = MutableStateFlow<PowerSavingStats?>(null)
    val powerSavingStats: StateFlow<PowerSavingStats?> = _powerSavingStats

    // ============================================================
    // 操作
    // ============================================================

    /**
     * 刷新权限状态
     */
    fun refreshPermissionStatus() {
        _permissionStatus.value = permissionManager.checkPermissions()
    }

    /**
     * 设置电源模式
     */
    fun setPowerMode(mode: PowerMode) {
        _selectedPowerMode.value = mode
        prefs.powerMode = mode
        app.getLocationService()?.setPowerMode(mode)
    }

    /**
     * 设置静止判定阈值
     */
    fun setStationaryThreshold(value: Double) {
        _stationaryThreshold.value = value
        prefs.stationaryThreshold = value
        app.updateEngineConfig()
    }

    /**
     * 设置边界缓冲距离
     */
    fun setBoundaryBuffer(value: Double) {
        _boundaryBuffer.value = value
        prefs.boundaryBuffer = value
        app.updateEngineConfig()
    }

    /**
     * 设置漂移检测阈值
     */
    fun setDriftThreshold(value: Double) {
        _driftThreshold.value = value
        prefs.driftThreshold = value
        app.updateEngineConfig()
    }

    /**
     * 重置统计
     */
    fun resetStats() {
        prefs.resetStats()
        _totalLocations.value = 0
        _processedLocations.value = 0
    }

    /**
     * 清除缓存
     */
    fun clearCache() {
        GeoMath.clearCirclePolygonCache()
        _cacheStats.value = GeoMath.getCacheStats()
    }

    /**
     * 刷新省电统计
     */
    fun refreshStats() {
        _totalLocations.value = prefs.totalLocations
        _processedLocations.value = prefs.processedLocations
        _cacheStats.value = GeoMath.getCacheStats()
        _powerSavingStats.value = app.engine.getScheduler().stats
    }

    /**
     * 恢复默认设置
     */
    fun restoreDefaultSettings() {
        setStationaryThreshold(30.0)
        setBoundaryBuffer(200.0)
        setDriftThreshold(50.0)
    }

    /**
     * 获取省电百分比
     */
    fun getSavedPercentage(): Double {
        return prefs.getSavedPercentage()
    }

    /**
     * 获取性能模式估算
     */
    fun getPerformanceModeEstimate(): Long {
        return prefs.getPerformanceModeEstimate()
    }
}
