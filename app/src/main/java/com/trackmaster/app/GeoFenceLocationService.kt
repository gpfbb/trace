package com.trackmaster.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.amap.api.location.AMapLocation
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import com.amap.api.location.AMapLocationListener
import java.util.Date

enum class LocationMode {
    POWER_SAVING,
    BALANCED,
    PERFORMANCE
}

data class LocationServiceState(
    val isRunning: Boolean = false,
    val currentMode: LocationMode = LocationMode.BALANCED,
    val lastLocation: AMapLocation? = null,
    val lastUpdateTime: Date? = null,
    val locationCount: Int = 0
)

class GeoFenceLocationService : Service(), AMapLocationListener {

    companion object {
        private const val TAG = "GeoFenceLocationService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "geofence_location_channel"
        private const val CHANNEL_NAME = "地理围栏定位服务"

        // 定位频率配置（毫秒）
        private const val INTERVAL_POWER_SAVING = 60000L
        private const val INTERVAL_BALANCED_STATIC = 30000L
        private const val INTERVAL_BALANCED_WALKING = 15000L
        private const val INTERVAL_BALANCED_RUNNING = 5000L
        private const val INTERVAL_PERFORMANCE = 2000L
        private const val INTERVAL_BOUNDARY = 10000L

        // 运动状态判断阈值
        private const val SPEED_WALKING_THRESHOLD = 1.5f
        private const val SPEED_RUNNING_THRESHOLD = 4.0f
        private const val ACCURACY_THRESHOLD = 50f
    }

    private val binder = LocalBinder()
    private var locationClient: AMapLocationClient? = null
    private var locationOption: AMapLocationClientOption? = null
    private var currentMode = LocationMode.BALANCED
    private var isInBoundaryZone = false
    private var lastSpeed = 0f
    private var consecutiveStaticCount = 0
    private val stateListeners = mutableListOf<(LocationServiceState) -> Unit>()
    
    private var serviceState = LocationServiceState()
        set(value) {
            field = value
            stateListeners.forEach { it(value) }
        }

    inner class LocalBinder : Binder() {
        val service: GeoFenceLocationService
            get() = this@GeoFenceLocationService
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "定位服务创建")
        createNotificationChannel()
        initLocationClient()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "定位服务启动")
        startForeground(NOTIFICATION_ID, createNotification())
        startLocation()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "定位服务销毁")
        stopLocation()
        locationClient?.onDestroy()
        (application as? TrackMasterApplication)?.stayDurationManager?.saveAllActiveSessions()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "地理围栏后台定位服务"
                setShowBadge(false)
                setSound(null, null)
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("地理围栏正在运行")
            .setContentText("后台监测中...")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun initLocationClient() {
        try {
            locationClient = AMapLocationClient(applicationContext)
            locationClient?.setLocationListener(this)
            updateLocationOptions()
        } catch (e: Exception) {
            Log.e(TAG, "初始化定位客户端失败", e)
        }
    }

    private fun updateLocationOptions() {
        locationOption = AMapLocationClientOption().apply {
            locationPurpose = AMapLocationClientOption.AMapLocationPurpose.Transport
            locationMode = AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
            isOnceLocation = false
            isNeedAddress = true
            isWifiScan = true
            isSensorEnable = true
            
            interval = getCurrentInterval()
            Log.d(TAG, "定位间隔设置为: ${interval}ms")
        }
        locationClient?.setLocationOption(locationOption)
    }

    private fun getCurrentInterval(): Long {
        return when (currentMode) {
            LocationMode.POWER_SAVING -> {
                if (isInBoundaryZone) INTERVAL_BOUNDARY else INTERVAL_POWER_SAVING
            }
            LocationMode.BALANCED -> {
                when {
                    isInBoundaryZone -> INTERVAL_BOUNDARY
                    consecutiveStaticCount >= 3 -> INTERVAL_BALANCED_STATIC
                    lastSpeed < SPEED_WALKING_THRESHOLD -> INTERVAL_BALANCED_WALKING
                    else -> INTERVAL_BALANCED_RUNNING
                }
            }
            LocationMode.PERFORMANCE -> INTERVAL_PERFORMANCE
        }
    }

    fun setLocationMode(mode: LocationMode) {
        if (currentMode != mode) {
            Log.d(TAG, "切换定位模式: $mode")
            currentMode = mode
            updateLocationOptions()
            serviceState = serviceState.copy(currentMode = mode)
        }
    }

    fun getLocationMode(): LocationMode = currentMode

    fun addStateListener(listener: (LocationServiceState) -> Unit) {
        stateListeners.add(listener)
        listener(serviceState)
    }

    fun removeStateListener(listener: (LocationServiceState) -> Unit) {
        stateListeners.remove(listener)
    }

    private fun startLocation() {
        try {
            locationClient?.startLocation()
            serviceState = serviceState.copy(isRunning = true)
            Log.d(TAG, "定位已启动")
        } catch (e: Exception) {
            Log.e(TAG, "启动定位失败", e)
        }
    }

    private fun stopLocation() {
        try {
            locationClient?.stopLocation()
            serviceState = serviceState.copy(isRunning = false)
            Log.d(TAG, "定位已停止")
        } catch (e: Exception) {
            Log.e(TAG, "停止定位失败", e)
        }
    }

    override fun onLocationChanged(location: AMapLocation?) {
        location ?: return

        if (location.errorCode != 0) {
            Log.e(TAG, "定位错误: ${location.errorInfo}")
            return
        }

        if (location.accuracy > ACCURACY_THRESHOLD) {
            Log.d(TAG, "精度不足: ${location.accuracy}m，忽略")
            return
        }

        val speed = location.speed
        lastSpeed = speed

        if (speed < 0.5f) {
            consecutiveStaticCount++
        } else {
            consecutiveStaticCount = 0
        }

        Log.d(TAG, "定位更新: 速度=$speed m/s, 精度=${location.accuracy}m, 状态=${getMotionState()}")

        serviceState = serviceState.copy(
            lastLocation = location,
            lastUpdateTime = Date(),
            locationCount = serviceState.locationCount + 1
        )

        (application as? TrackMasterApplication)?.let { app ->
            app.checkGeoFences(location)
        }

        val currentInterval = getCurrentInterval()
        if (locationOption?.interval != currentInterval) {
            updateLocationOptions()
        }
    }

    fun setBoundaryZone(isInZone: Boolean) {
        if (isInBoundaryZone != isInZone) {
            isInBoundaryZone = isInZone
            Log.d(TAG, "边界区域状态: $isInZone")
            updateLocationOptions()
        }
    }

    private fun getMotionState(): String {
        return when {
            consecutiveStaticCount >= 3 -> "静止"
            lastSpeed < SPEED_WALKING_THRESHOLD -> "步行"
            lastSpeed < SPEED_RUNNING_THRESHOLD -> "跑步"
            else -> "高速移动"
        }
    }
}
