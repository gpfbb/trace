package com.trackmaster.app.locationservice.amap

import android.util.Log
import com.amap.api.location.AMapLocation
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import com.amap.api.location.AMapLocationListener
import com.trackmaster.app.locationservice.Location
import com.trackmaster.app.locationservice.LocationCallback
import com.trackmaster.app.locationservice.LocationMode
import com.trackmaster.app.locationservice.LocationProvider

/**
 * AMap 定位提供者
 *
 * 实现 LocationProvider 接口，内部持有 AMapLocationClient，
 * 将 AMapLocation 转换为统一的 Location 类型后通过 LocationCallback 回调。
 */
class AMapLocationProvider(
    private val context: android.content.Context
) : LocationProvider, AMapLocationListener {

    companion object {
        private const val TAG = "AMapLocationProvider"
        private const val DEFAULT_TIMEOUT_MS = 10000L
        private const val HIGH_ACCURACY_INTERVAL_MS = 60_000L
        private const val BATTERY_SAVING_INTERVAL_MS = 60_000L
        private const val AMAP_TYPE_GPS = 1
        private const val AMAP_TYPE_NETWORK = 2
    }

    private val client: AMapLocationClient = AMapLocationClient(context).apply {
        // 隐私合规：必须在 AMapLocationClient 创建后、setLocationListener 之前调用
        AMapLocationClient.updatePrivacyShow(context, true, true)
        AMapLocationClient.updatePrivacyAgree(context, true)
    }

    private var callback: LocationCallback? = null
    private var currentMode: LocationMode = LocationMode.HIGH_ACCURACY
    private var isOnceLocation: Boolean = false
    private var locationCount: Int = 0

    private var _lastLocation: Location? = null

    override fun getLastLocation(): Location? = _lastLocation
    override fun isRunning(): Boolean = client.isStarted

    init {
        client.setLocationListener(this)
    }

    override fun start() {
        if (!client.isStarted) {
            client.startLocation()
            Log.d(TAG, "AMapLocationProvider started")
        }
    }

    override fun stop() {
        if (client.isStarted) {
            client.stopLocation()
            Log.d(TAG, "AMapLocationProvider stopped")
        }
    }

    override fun setLocationCallback(callback: LocationCallback) {
        this.callback = callback
    }

    override fun removeLocationCallback() {
        this.callback = null
    }

    override fun setLocationMode(mode: LocationMode) {
        if (currentMode == mode) return
        currentMode = mode
        val wasRunning = client.isStarted
        if (wasRunning) {
            client.stopLocation()
        }
        applyMode(mode)
        if (wasRunning) {
            client.startLocation()
        }
        Log.d(TAG, "Location mode changed to $mode")
    }

    override fun getProviderName(): String = "AMap"

    private fun applyMode(mode: LocationMode) {
        val option = AMapLocationClientOption().apply {
            when (mode) {
                LocationMode.HIGH_ACCURACY -> {
                    locationMode = AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
                    isOnceLocation = false
                    interval = HIGH_ACCURACY_INTERVAL_MS
                }
                LocationMode.BATTERY_SAVING -> {
                    locationMode = AMapLocationClientOption.AMapLocationMode.Battery_Saving
                    isOnceLocation = false
                    interval = BATTERY_SAVING_INTERVAL_MS
                }
                LocationMode.DEVICE_ONLY -> {
                    locationMode = AMapLocationClientOption.AMapLocationMode.Device_Sensors
                    isOnceLocation = false
                    interval = HIGH_ACCURACY_INTERVAL_MS
                }
            }
            isNeedAddress = false
            isWifiScan = true
            isSensorEnable = true
            httpTimeOut = DEFAULT_TIMEOUT_MS
        }
        client.setLocationOption(option)
    }

    fun setOnceLocation(once: Boolean) {
        isOnceLocation = once
        val option = AMapLocationClientOption()
        option.isOnceLocation = once
        client.setLocationOption(option)
    }

    override fun setInterval(intervalMs: Long) {
        val option = AMapLocationClientOption()
        option.interval = intervalMs
        option.httpTimeOut = maxOf(intervalMs * 3, DEFAULT_TIMEOUT_MS)
        client.setLocationOption(option)
    }

    fun setHighAccuracyOnce(callback: ((Location) -> Unit)? = null) {
        currentMode = LocationMode.HIGH_ACCURACY
        val wasRunning = client.isStarted
        if (wasRunning) client.stopLocation()

        val option = AMapLocationClientOption().apply {
            locationMode = AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
            isOnceLocation = true
            isNeedAddress = false
            isWifiScan = true
            isSensorEnable = true
            httpTimeOut = 20_000L
        }
        client.setLocationOption(option)

        // One-time callback wrapper
        if (callback != null) {
            val wrapper = object : LocationCallback {
                override fun onLocationChanged(location: Location) {
                    removeLocationCallback()
                    callback(location)
                }
                override fun onLocationError(errorCode: Int, errorInfo: String) {
                    removeLocationCallback()
                }
            }
            setLocationCallback(wrapper)
        }

        client.startLocation()
        Log.d(TAG, "AMapLocationProvider started in once-high-accuracy mode")
    }

    override fun onLocationChanged(location: AMapLocation?) {
        location ?: return

        if (location.errorCode != 0) {
            callback?.onLocationError(location.errorCode, location.errorInfo ?: "unknown error")
            return
        }

        if (location.latitude == 0.0 && location.longitude == 0.0) {
            callback?.onLocationError(-1, "Invalid coordinate (0,0)")
            return
        }

        locationCount++

        _lastLocation = toLocation(location)
        callback?.onLocationChanged(_lastLocation!!)

        Log.d(TAG, "AMap location: (${location.latitude}, ${location.longitude}), " +
                "accuracy=${location.accuracy}m, type=${location.locationType}")
    }

    private fun toLocation(aMapLocation: AMapLocation): Location {
        val locationType = when (aMapLocation.locationType) {
            AMAP_TYPE_GPS -> com.trackmaster.app.locationservice.LocationType.GPS
            AMAP_TYPE_NETWORK -> com.trackmaster.app.locationservice.LocationType.NETWORK
            else -> com.trackmaster.app.locationservice.LocationType.UNKNOWN
        }
        return Location(
            latitude = aMapLocation.latitude,
            longitude = aMapLocation.longitude,
            accuracy = aMapLocation.accuracy,
            speed = aMapLocation.speed,
            bearing = aMapLocation.bearing,
            timestamp = aMapLocation.time,
            locationType = locationType
        )
    }

    fun destroy() {
        stop()
        client.onDestroy()
    }
}
