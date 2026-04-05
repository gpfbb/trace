package com.trackmaster.app.architecture.data.datasources

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.*
import android.util.Log
import androidx.core.app.ActivityCompat
import com.amap.api.location.*
import com.trackmaster.app.architecture.domain.models.LocationFix
import com.trackmaster.app.architecture.domain.usecases.FenceTrackingEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.*

/**
 * 定位服务 - 处理位置更新和围栏检测
 */
class LocationService(
    private val context: Context,
    private val engine: FenceTrackingEngine
) {
    companion object {
        private const val TAG = "LocationService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "location_service"
        private const val FIRST_LOCATION_TIMEOUT = 10000L
        private const val LOCATION_INTERVAL = 5000L
    }

    private var locationClient: AMapLocationClient? = null
    private var locationOption: AMapLocationClientOption? = null
    private var isRunning = false

    /**
     * 启动定位服务
     */
    fun start() {
        if (isRunning) return

        try {
            // 检查权限
            if (!checkLocationPermissions()) {
                Log.e(TAG, "定位权限未授予")
                return
            }

            // 检查定位服务是否开启
            if (!isLocationEnabled()) {
                Log.e(TAG, "定位服务未开启")
                return
            }

            // 初始化定位客户端
            initLocationClient()

            // 启动定位
            startLocation()

            isRunning = true
            Log.d(TAG, "定位服务已启动")

        } catch (e: Exception) {
            Log.e(TAG, "启动定位服务失败", e)
            isRunning = false
        }
    }

    /**
     * 停止定位服务
     */
    fun stop() {
        try {
            locationClient?.stopLocation()
            locationClient?.onDestroy()
            locationClient = null
            isRunning = false
            Log.d(TAG, "定位服务已停止")
        } catch (e: Exception) {
            Log.e(TAG, "停止定位服务失败", e)
        }
    }

    /**
     * 初始化定位客户端
     */
    private fun initLocationClient() {
        locationClient = AMapLocationClient(context)
        locationOption = AMapLocationClientOption().apply {
            locationMode = AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
            isNeedAddress = true
            isOnceLocation = false
            interval = LOCATION_INTERVAL
            isWifiScan = true
            isSensorEnable = true
        }
        locationClient?.setLocationOption(locationOption)
        locationClient?.setLocationListener(locationListener)
    }

    /**
     * 启动定位（快速首次定位模式）
     */
    private fun startLocation() {
        try {
            // 先使用快速模式获取首次定位
            val fastOption = AMapLocationClientOption().apply {
                locationMode = AMapLocationClientOption.AMapLocationMode.Battery_Saving
                isOnceLocation = true
                isNeedAddress = false
                isWifiScan = true
                isSensorEnable = false
                httpTimeOut = FIRST_LOCATION_TIMEOUT
            }
            locationClient?.setLocationOption(fastOption)
            locationClient?.startLocation()
            Log.d(TAG, "启动快速首次定位")
        } catch (e: Exception) {
            Log.e(TAG, "启动定位失败", e)
        }
    }

    /**
     * 定位监听器
     */
    private val locationListener = AMapLocationListener {
        if (it != null) {
            if (it.errorCode == 0) {
                // 定位成功
                processLocation(it)
            } else {
                // 定位失败
                Log.e(TAG, "定位失败: ${it.errorCode}, ${it.errorInfo}")
            }
        }
    }

    /**
     * 处理定位结果
     */
    private fun processLocation(location: AMapLocation) {
        val fix = LocationFix(
            lat = location.latitude,
            lng = location.longitude,
            accuracy = location.accuracy,
            timestamp = location.time
        )

        // 切换到连续定位模式（仅首次定位后）
        if (locationClient != null && locationOption != null) {
            locationClient?.setLocationOption(locationOption)
        }

        // 处理位置更新
        CoroutineScope(Dispatchers.IO).launch {
            engine.onLocationUpdate(fix)
        }
    }

    /**
     * 检查定位权限
     */
    private fun checkLocationPermissions(): Boolean {
        return ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 检查定位服务是否开启
     */
    private fun isLocationEnabled(): Boolean {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    /**
     * 发送前台服务通知
     */
    private fun startForegroundService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "定位服务",
                NotificationManager.IMPORTANCE_LOW
            )
            val notificationManager = context.getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)

            val notification = Notification.Builder(context, CHANNEL_ID)
                .setContentTitle("轨迹大师")
                .setContentText("正在后台运行定位服务")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .build()

            (context as Service).startForeground(NOTIFICATION_ID, notification)
        }
    }
}