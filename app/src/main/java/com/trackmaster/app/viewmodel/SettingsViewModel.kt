package com.trackmaster.app.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.ViewModel
import com.trackmaster.app.AppPreferences
import com.trackmaster.app.PermissionManager
import com.trackmaster.app.TrackMasterApplication
import com.trackmaster.app.core.GeoFenceType
import com.trackmaster.app.utils.GeoMath
import com.trackmaster.app.core.Geofence
import com.trackmaster.app.core.PowerMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class SettingsViewModel(
    private val application: Application
) : ViewModel() {

    companion object {
        private const val TAG = "SettingsViewModel"
    }

    private val app: TrackMasterApplication
        get() = application as TrackMasterApplication

    private val permissionManager = PermissionManager(application)
    private val prefs: AppPreferences
        get() = app.appPreferences

    private val _permissionStatus = MutableStateFlow(permissionManager.checkPermissions())
    val permissionStatus: StateFlow<PermissionManager.PermissionStatus> = _permissionStatus

    private val _selectedPowerMode = MutableStateFlow(prefs.powerMode)
    val selectedPowerMode: StateFlow<PowerMode> = _selectedPowerMode

    private val _stationaryThreshold = MutableStateFlow(prefs.stationaryThreshold)
    val stationaryThreshold: StateFlow<Double> = _stationaryThreshold

    private val _boundaryBuffer = MutableStateFlow(prefs.boundaryBuffer)
    val boundaryBuffer: StateFlow<Double> = _boundaryBuffer

    private val _driftThreshold = MutableStateFlow(prefs.driftThreshold)
    val driftThreshold: StateFlow<Double> = _driftThreshold

    private val _isRecalculating = MutableStateFlow(false)
    val isRecalculating: StateFlow<Boolean> = _isRecalculating

    private val _recalculateMessage = MutableStateFlow<String?>(null)
    val recalculateMessage: StateFlow<String?> = _recalculateMessage

    fun refreshPermissionStatus() {
        _permissionStatus.value = permissionManager.checkPermissions()
    }

    fun setPowerMode(mode: PowerMode) {
        _selectedPowerMode.value = mode
        prefs.powerMode = mode
        app.getLocationService()?.setPowerMode(mode)
    }

    fun setStationaryThreshold(value: Double) {
        _stationaryThreshold.value = value
        prefs.stationaryThreshold = value
        app.updateEngineConfig()
    }

    fun setBoundaryBuffer(value: Double) {
        _boundaryBuffer.value = value
        prefs.boundaryBuffer = value
        app.updateEngineConfig()
    }

    fun setDriftThreshold(value: Double) {
        _driftThreshold.value = value
        prefs.driftThreshold = value
        app.updateEngineConfig()
    }

    fun restoreDefaultSettings() {
        setStationaryThreshold(30.0)
        setBoundaryBuffer(200.0)
        setDriftThreshold(50.0)
    }

    fun recalculateParentChildRelationships() {
        val fences = app.geoFences
        if (fences.isEmpty()) {
            _recalculateMessage.value = "没有围栏数据"
            return
        }

        _isRecalculating.value = true
        _recalculateMessage.value = null

        try {
            app.fenceTreeManager.clearAll()

            val coreFences = fences.map { GeoMath.buildEngineGeofence(it) }
            if (coreFences.isEmpty()) {
                _recalculateMessage.value = "围栏转换失败"
                return
            }

            var relationshipCount = 0
            for (fenceA in coreFences) {
                for (fenceB in coreFences) {
                    if (fenceA.id == fenceB.id) continue

                    if (isContainedBy(fenceB, fenceA)) {
                        if (isContainedBy(fenceA, fenceB)) {
                            continue
                        }
                        app.fenceTreeManager.setParent(fenceB.id, fenceA.id)
                        relationshipCount++
                    }
                }
            }

            _recalculateMessage.value = "重新计算完成，建立了 $relationshipCount 对父子关系"
        } catch (e: Exception) {
            Log.e(TAG, "重新计算父子关系失败", e)
            _recalculateMessage.value = "重新计算失败: ${e.message}"
        } finally {
            _isRecalculating.value = false
        }
    }

    private fun isContainedBy(inner: Geofence, outer: Geofence): Boolean {
        return when {
            inner.type == GeoFenceType.CIRCLE && outer.type == GeoFenceType.CIRCLE -> {
                val innerCenter = inner.center ?: return false
                val innerRadius = inner.radius ?: return false
                val outerCenter = outer.center ?: return false
                val outerRadius = outer.radius ?: return false
                val dist = GeoMath.haversine(innerCenter, outerCenter)
                dist + innerRadius <= outerRadius
            }
            inner.type == GeoFenceType.POLYGON && outer.type == GeoFenceType.POLYGON -> {
                val innerPoly = inner.polygon
                val outerPoly = outer.polygon
                if (innerPoly.size < 3 || outerPoly.size < 3) return false
                innerPoly.all { GeoMath.pointInPolygon(it.lat, it.lng, outerPoly) }
            }
            inner.type == GeoFenceType.CIRCLE && outer.type == GeoFenceType.POLYGON -> {
                val innerCenter = inner.center ?: return false
                val innerRadius = inner.radius ?: return false
                val outerPoly = outer.polygon
                if (outerPoly.size < 3) return false
                GeoMath.pointInPolygon(innerCenter.lat, innerCenter.lng, outerPoly) &&
                        outerPoly.all { GeoMath.pointInCircle(it, innerCenter, innerRadius) }
            }
            inner.type == GeoFenceType.POLYGON && outer.type == GeoFenceType.CIRCLE -> {
                val innerPoly = inner.polygon
                val outerCenter = outer.center ?: return false
                val outerRadius = outer.radius ?: return false
                if (innerPoly.isEmpty()) return false
                innerPoly.all { GeoMath.pointInCircle(it, outerCenter, outerRadius) }
            }
            else -> false
        }
    }
}
