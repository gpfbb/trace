package com.trackmaster.app.viewmodel

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trackmaster.app.locationservice.Location
import com.trackmaster.app.data.GeoFence
import com.trackmaster.app.TrackMasterApplication
import com.trackmaster.app.storage.Repository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 主 ViewModel - 协调位置、围栏和跨屏幕状态
 *
 * 职责：
 * - 管理当前位置和定位状态
 * - 管理围栏列表
 * - 管理当前匹配的围栏
 * - 协调 TrackMasterApplication 的状态
 */
class MainViewModel(
    private val application: Application,
    private val repository: Repository
) : ViewModel() {

    private val app: TrackMasterApplication
        get() = application as TrackMasterApplication

    // ============================================================
    // 位置状态
    // ============================================================

    private val _currentLocation = MutableStateFlow<Location?>(null)
    val currentLocation: StateFlow<Location?> = _currentLocation

    private val _locationStatus = MutableStateFlow("正在初始化...")
    val locationStatus: StateFlow<String> = _locationStatus

    private val _locationError = MutableStateFlow<String?>(null)
    val locationError: StateFlow<String?> = _locationError

    // ============================================================
    // 围栏状态
    // ============================================================

    private val _fences = MutableStateFlow<List<GeoFence>>(emptyList())
    val fences: StateFlow<List<GeoFence>> = _fences

    private val _currentMatchedFences = MutableStateFlow<List<GeoFence>>(emptyList())
    val currentMatchedFences: StateFlow<List<GeoFence>> = _currentMatchedFences

    // ============================================================
    // UI 状态
    // ============================================================

    private val _isLocationPermissionGranted = MutableStateFlow(false)
    val isLocationPermissionGranted: StateFlow<Boolean> = _isLocationPermissionGranted

    // ============================================================
    // 初始化
    // ============================================================

    init {
        loadFences()
        observeFenceState()
        observeLocationService()
    }

    /**
     * 从数据库加载围栏列表
     */
    private fun loadFences() {
        _fences.value = app.dbHelper.getAllFences()
    }

    /**
     * 观察 TrackMasterApplication 的围栏状态变化
     */
    private fun observeFenceState() {
        viewModelScope.launch {
            app.fenceStateVersion.collect {
                _currentMatchedFences.value = app.currentMatchedFences.toList()
            }
        }
    }

    /**
     * 观察定位服务状态
     */
    private fun observeLocationService() {
        val service = app.getLocationService()
        if (service != null) {
            val listener = { state: com.trackmaster.app.LocationServiceState ->
                _currentLocation.value = state.lastLocation
                _locationStatus.value = when {
                    state.isFirstLocation -> "首次定位中..."
                    state.locationCount == 0 -> "正在获取位置..."
                    else -> "定位成功 (${state.locationCount}次更新)"
                }
                if (!state.isRunning) {
                    _locationStatus.value = "定位服务已停止"
                }
            }
            service.addStateListener(listener)
        }
    }

    /**
     * 刷新围栏列表（从数据库重新加载）
     */
    fun refreshFences() {
        loadFences()
    }

    /**
     * 获取定位服务实例
     */
    fun getLocationService() = app.getLocationService()

    /**
     * 更新定位权限状态
     */
    fun updatePermissionStatus(granted: Boolean) {
        _isLocationPermissionGranted.value = granted
    }
}

/**
 * 定位服务状态
 */
data class LocationServiceState(
    val lastLocation: Location? = null,
    val isFirstLocation: Boolean = true,
    val locationCount: Int = 0,
    val isRunning: Boolean = false
)
