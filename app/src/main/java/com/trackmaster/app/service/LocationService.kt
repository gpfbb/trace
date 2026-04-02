package com.trackmaster.app.service

import android.content.Context
import android.location.Location
import com.amap.api.location.AMapLocation
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import com.amap.api.location.AMapLocationListener
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

enum class MotionState {
    STATIONARY, WALKING, RIDING, DRIVING
}

enum class PowerMode {
    HIGH_PERFORMANCE, BALANCED, POWER_SAVE
}

data class LocationData(
    val location: AMapLocation?,
    val motionState: MotionState,
    val timestamp: Long
)

@Singleton
class LocationService @Inject constructor(
    @ApplicationContext private val context: Context
) : AMapLocationListener {

    private var locationClient: AMapLocationClient? = null
    private val _locationData = MutableStateFlow<LocationData?>(null)
    val locationData: StateFlow<LocationData?> = _locationData.asStateFlow()

    private val _motionState = MutableStateFlow(MotionState.STATIONARY)
    val motionState: StateFlow<MotionState> = _motionState.asStateFlow()

    private val _powerMode = MutableStateFlow(PowerMode.BALANCED)
    val powerMode: StateFlow<PowerMode> = _powerMode.asStateFlow()

    private var motionStateBuffer = mutableListOf<MotionState>()
    private val motionStateDebounceCount = 3

    private var lastLocation: Location? = null
    private var isScreenOn = true

    fun init() {
        locationClient = AMapLocationClient(context.applicationContext)
        locationClient?.setLocationListener(this)
    }

    fun setPowerMode(mode: PowerMode) {
        _powerMode.value = mode
        updateLocationInterval()
    }

    fun setScreenState(isOn: Boolean) {
        isScreenOn = isOn
        updateLocationInterval()
    }

    private fun updateLocationInterval() {
        val interval = when (_powerMode.value) {
            PowerMode.HIGH_PERFORMANCE -> 2000L
            PowerMode.BALANCED -> getIntervalForState(_motionState.value)
            PowerMode.POWER_SAVE -> 60000L
        }
        val finalInterval = if (!isScreenOn) interval * 2 else interval
        locationClient?.setLocationOption(createLocationOption(finalInterval))
    }

    private fun getIntervalForState(state: MotionState): Long {
        return when (state) {
            MotionState.STATIONARY -> 60000L
            MotionState.WALKING -> 10000L
            MotionState.RIDING -> 5000L
            MotionState.DRIVING -> 2000L
        }
    }

    private fun createLocationOption(interval: Long): AMapLocationClientOption {
        val option = AMapLocationClientOption()
        option.locationMode = AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
        option.interval = interval
        option.isNeedAddress = true
        option.isOnceLocation = false
        option.isOnceLocationLatest = false
        option.isWifiScan = true
        option.isLocationCacheEnable = true
        option.httpTimeOut = 30000
        return option
    }

    fun startLocation() {
        locationClient?.startLocation()
    }

    fun stopLocation() {
        locationClient?.stopLocation()
    }

    override fun onLocationChanged(location: AMapLocation?) {
        if (location == null) return

        if (location.errorCode != 0) {
            return
        }

        if (location.accuracy > 50f) {
            return
        }

        val currentState = detectMotionState(location)
        updateMotionState(currentState)

        if (lastLocation != null) {
            val speed = location.speed
            if (speed > 50f) {
                return
            }
        }

        lastLocation = Location("").apply {
            latitude = location.latitude
            longitude = location.longitude
        }

        _locationData.value = LocationData(
            location = location,
            motionState = _motionState.value,
            timestamp = System.currentTimeMillis()
        )
    }

    private fun detectMotionState(location: AMapLocation): MotionState {
        val speed = location.speed
        return when {
            speed < 0.5f -> MotionState.STATIONARY
            speed < 2.0f -> MotionState.WALKING
            speed < 8.0f -> MotionState.RIDING
            else -> MotionState.DRIVING
        }
    }

    private fun updateMotionState(newState: MotionState) {
        motionStateBuffer.add(newState)
        if (motionStateBuffer.size > motionStateDebounceCount) {
            motionStateBuffer.removeAt(0)
        }

        if (motionStateBuffer.size >= motionStateDebounceCount) {
            val allSame = motionStateBuffer.all { it == newState }
            if (allSame) {
                _motionState.value = newState
                if (_powerMode.value == PowerMode.BALANCED) {
                    updateLocationInterval()
                }
            }
        }
    }

    fun destroy() {
        locationClient?.onDestroy()
        locationClient = null
    }
}
