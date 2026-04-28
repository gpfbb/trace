package com.trackmaster.app.locationservice

import android.content.Context
import com.amap.api.location.AMapLocation
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import com.amap.api.location.AMapLocationListener
import com.trackmaster.app.MapProvider
import com.trackmaster.app.TrackMasterApplication
import com.trackmaster.app.locationservice.google.GoogleLocationProvider
import com.trackmaster.app.utils.AppLog

/**
 * 定位提供商管理器 — 双提供商（Google/AMap）初始化、切换、启停、配置
 */
class LocationProviderManager(
    private val context: Context,
    private val appProvider: () -> TrackMasterApplication?,
    private val onLocation: (AMapLocation) -> Unit,
    private val onError: (Int, String) -> Unit,
) {
    companion object {
        private const val TAG = "LocationProviderManager"
        private const val FIRST_LOCATION_TIMEOUT = 20000L
    }

    var isUsingGoogleProvider = false
        private set

    private var locationClient: AMapLocationClient? = null
    private var locationProvider: LocationProvider? = null
    private var locationOption: AMapLocationClientOption? = null
    private var appCallback: LocationCallback? = null
    @Volatile var initFailed = false
        private set

    // AMap 定位类型常量
    private val amapTypeGps = 1
    private val amapTypeNetwork = 2

    fun init() {
        val app = appProvider()
        val mapProvider = app?.appPreferences?.mapProvider ?: MapProvider.AMAP
        isUsingGoogleProvider = (mapProvider == MapProvider.GOOGLE)

        if (isUsingGoogleProvider) {
            try {
                locationProvider = GoogleLocationProvider(context)
                appCallback = object : LocationCallback {
                    override fun onLocationChanged(location: Location) {
                        val locationId = when (location.locationType) {
                            LocationType.GPS -> "gps"
                            LocationType.NETWORK -> "network"
                            else -> "offline"
                        }
                        val aMapLoc = AMapLocation(locationId).apply {
                            latitude = location.latitude
                            longitude = location.longitude
                            accuracy = location.accuracy
                            speed = location.speed
                            bearing = location.bearing
                            time = location.timestamp
                        }
                        onLocation(aMapLoc)
                    }
                    override fun onLocationError(errorCode: Int, errorInfo: String) {
                        onError(errorCode, errorInfo)
                    }
                }
                locationProvider?.setLocationCallback(appCallback!!)
                AppLog.d(TAG, "初始化 Google Fused Location Provider")
            } catch (e: Exception) {
                AppLog.e(TAG, "初始化 Google 定位客户端失败，降级到 AMap", e)
                initAmap()
            }
        } else {
            initAmap()
        }
    }

    fun reinit() {
        AppLog.d(TAG, "重新初始化定位客户端")
        stop()
        destroyProvider()
        locationProvider = null
        locationClient = null
        init()
    }

    fun destroy() {
        destroyProvider()
        locationProvider = null
        locationClient = null
    }

    private fun destroyProvider() {
        if (isUsingGoogleProvider) {
            (locationProvider as? GoogleLocationProvider)?.destroy()
        } else {
            locationClient?.onDestroy()
        }
    }

    private fun initAmap() {
        isUsingGoogleProvider = false
        try {
            AMapLocationClient(context).apply {
                AMapLocationClient.updatePrivacyShow(context, true, true)
                AMapLocationClient.updatePrivacyAgree(context, true)
            }.also { locationClient = it }
            locationClient?.setLocationListener(AMapLocationListener { aMapLoc ->
                aMapLoc?.let { onLocation(it) }
            })
            initFailed = false
            AppLog.d(TAG, "初始化 AMap 定位客户端（隐私合规已调用）")
        } catch (e: Exception) {
            initFailed = true
            locationClient = null
            AppLog.e(TAG, "初始化定位客户端失败，定位功能不可用", e)
            onError(-1, "定位SDK初始化失败: ${e.message}")
        }
    }

    fun startFastFirst() {
        if (isUsingGoogleProvider) {
            locationProvider?.start()
        } else {
            locationClient?.stopLocation()
            locationOption = AMapLocationClientOption().apply {
                locationMode = AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
                isOnceLocation = true
                isNeedAddress = false
                isWifiScan = true
                isSensorEnable = true
                httpTimeOut = FIRST_LOCATION_TIMEOUT
            }
            locationClient?.setLocationOption(locationOption)
            locationClient?.startLocation()
        }
        AppLog.d(TAG, "启动快速首次定位, provider=${if (isUsingGoogleProvider) "Google" else "AMap"}")
    }

    fun startContinuous(baseIntervalMs: Long) {
        val delay = 100L
        if (isUsingGoogleProvider) {
            locationProvider?.stop()
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                locationProvider?.setInterval(baseIntervalMs)
                locationProvider?.start()
                AppLog.d(TAG, "切换到 Google 连续定位模式，间隔: ${baseIntervalMs}ms")
            }, delay)
        } else {
            locationClient?.stopLocation()
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                applyContinuousOptions(baseIntervalMs, forceGps = false)
                locationClient?.startLocation()
                AppLog.d(TAG, "切换到 AMap 连续定位模式，基础间隔: ${baseIntervalMs}ms")
            }, delay)
        }
    }

    fun stop() {
        if (isUsingGoogleProvider) {
            locationProvider?.stop()
        } else {
            locationClient?.stopLocation()
        }
        AppLog.d(TAG, "定位已停止")
    }

    fun setInterval(intervalMs: Long) {
        if (isUsingGoogleProvider) {
            locationProvider?.setInterval(intervalMs)
        } else {
            applyContinuousOptions(intervalMs, forceGps = false)
        }
    }

    fun setThrottledInterval(intervalMs: Long, forceGps: Boolean) {
        if (isUsingGoogleProvider) {
            locationProvider?.setInterval(intervalMs)
        } else {
            applyContinuousOptions(intervalMs, forceGps)
        }
    }

    fun resumeWithNormalMode() {
        if (isUsingGoogleProvider) {
            locationProvider?.start()
        } else {
            applyContinuousOptions(60_000L, forceGps = false)
            locationClient?.startLocation()
        }
        AppLog.d(TAG, "恢复正常定位模式")
    }

    fun temporaryResumeForCheck(highAccuracy: Boolean = true) {
        if (isUsingGoogleProvider) {
            (locationProvider as? GoogleLocationProvider)?.setLocationMode(
                if (highAccuracy) LocationMode.HIGH_ACCURACY else LocationMode.BATTERY_SAVING
            )
            locationProvider?.start()
        } else {
            locationClient?.stopLocation()
            locationOption = AMapLocationClientOption().apply {
                locationMode = AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
                isOnceLocation = true
                isNeedAddress = false
                isWifiScan = true
                isSensorEnable = true
                httpTimeOut = 5000L
            }
            locationClient?.setLocationOption(locationOption)
            locationClient?.startLocation()
        }
        AppLog.d(TAG, "临时恢复定位检查")
    }

    fun resume() {
        if (isUsingGoogleProvider) {
            locationProvider?.start()
        } else {
            locationOption?.let { locationClient?.setLocationOption(it) }
            locationClient?.startLocation()
        }
        AppLog.d(TAG, "定位已恢复")
    }

    fun refresh() {
        if (isUsingGoogleProvider) {
            locationProvider?.stop()
            locationProvider?.start()
        } else {
            locationClient?.stopLocation()
            locationClient?.startLocation()
        }
        AppLog.d(TAG, "手动刷新位置")
    }

    fun removeCallback() {
        locationProvider?.removeLocationCallback()
    }

    private fun applyContinuousOptions(intervalMs: Long, forceGps: Boolean) {
        val mode = if (forceGps) {
            AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
        } else {
            AMapLocationClientOption.AMapLocationMode.Battery_Saving
        }
        locationOption = AMapLocationClientOption().apply {
            locationMode = mode
            isOnceLocation = false
            isNeedAddress = false
            isWifiScan = true
            isSensorEnable = true
            interval = intervalMs
            httpTimeOut = maxOf(intervalMs * 3, 10000L)
        }
        locationClient?.setLocationOption(locationOption)
        locationClient?.stopLocation()
        locationClient?.startLocation()
    }
}
