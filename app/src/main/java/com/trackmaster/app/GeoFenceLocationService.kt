package com.trackmaster.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import java.util.concurrent.CopyOnWriteArrayList

data class LocationServiceState(
    val isRunning: Boolean = false,
    val schedulerLevel: Int = 0,
    val lastLocation: AMapLocation? = null,
    val lastUpdateTime: Date? = null,
    val locationCount: Int = 0,
    val isFirstLocation: Boolean = true
)

class GeoFenceLocationService : Service(), AMapLocationListener {

    companion object {
        private const val TAG = "GeoFenceLocationService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "geofence_location_channel"
        private const val CHANNEL_NAME = "地理围栏定位服务"
        private const val FIRST_LOCATION_TIMEOUT = 10000L // 首次定位超时10秒
    }

    private val binder = LocalBinder()
    private var locationClient: AMapLocationClient? = null
    private var locationOption: AMapLocationClientOption? = null
    private val stateListeners = CopyOnWriteArrayList<(LocationServiceState) -> Unit>()

    private var serviceState = LocationServiceState()
        set(value) {
            field = value
            stateListeners.forEach { it(value) }
        }

    /** 屏幕状态接收器 */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val app = application as? TrackMasterApplication ?: return
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> app.engine.onScreenStateChanged(true)
                Intent.ACTION_SCREEN_OFF -> app.engine.onScreenStateChanged(false)
            }
        }
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

        // 注册屏幕状态接收器
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(screenReceiver, filter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "定位服务启动")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, createNotification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, createNotification())
        }
        startLocationWithFastFirst()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "定位服务销毁")
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        stopLocation()
        locationClient?.onDestroy()
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

        val locationText = serviceState.lastLocation?.let {
            "定位中... 精度${it.accuracy.toInt()}米"
        } ?: "正在获取位置..."

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("地理围栏正在运行")
            .setContentText(locationText)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, createNotification())
    }

    private fun initLocationClient() {
        try {
            locationClient = AMapLocationClient(applicationContext)
            locationClient?.setLocationListener(this)
        } catch (e: Exception) {
            Log.e(TAG, "初始化定位客户端失败", e)
        }
    }

    private fun updateLocationOptionsForFastFirst() {
        locationOption = AMapLocationClientOption().apply {
            // 优先使用网络定位，加快首次定位速度
            locationMode = AMapLocationClientOption.AMapLocationMode.Battery_Saving
            isOnceLocation = true
            isNeedAddress = false // 首次定位不需要地址信息，加快速度
            isWifiScan = true
            isSensorEnable = false // 首次定位不需要传感器
            httpTimeOut = FIRST_LOCATION_TIMEOUT
        }
        locationClient?.setLocationOption(locationOption)
        Log.d(TAG, "配置快速首次定位模式")
    }

    private fun updateLocationOptionsForContinuous(intervalMs: Long = 5000L) {
        locationOption = AMapLocationClientOption().apply {
            locationPurpose = AMapLocationClientOption.AMapLocationPurpose.Transport
            locationMode = AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
            isOnceLocation = false
            isNeedAddress = true
            isWifiScan = true
            isSensorEnable = true
            interval = intervalMs
        }
        locationClient?.setLocationOption(locationOption)
        Log.d(TAG, "配置连续定位模式，间隔: ${intervalMs}ms")
    }

    /**
     * 启动快速首次定位，成功后切换到连续定位
     */
    private fun startLocationWithFastFirst() {
        try {
            serviceState = serviceState.copy(isRunning = true, isFirstLocation = true)
            
            // 先使用快速模式获取首次定位
            updateLocationOptionsForFastFirst()
            locationClient?.startLocation()
            Log.d(TAG, "启动快速首次定位")
            
        } catch (e: Exception) {
            Log.e(TAG, "启动定位失败", e)
            serviceState = serviceState.copy(isRunning = false)
        }
    }

    /** 设置电源模式（转发给 Engine） */
    fun setPowerMode(mode: com.trackmaster.app.core.PowerMode) {
        (application as? TrackMasterApplication)?.engine?.setPowerMode(mode)
    }

    fun addStateListener(listener: (LocationServiceState) -> Unit) {
        stateListeners.add(listener)
        listener(serviceState)
    }

    fun removeStateListener(listener: (LocationServiceState) -> Unit) {
        stateListeners.remove(listener)
    }

    private fun startContinuousLocation() {
        try {
            locationClient?.stopLocation()
            updateLocationOptionsForContinuous(5000L)
            locationClient?.startLocation()
            serviceState = serviceState.copy(isFirstLocation = false)
            Log.d(TAG, "切换到连续定位模式")
        } catch (e: Exception) {
            Log.e(TAG, "启动连续定位失败", e)
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
            Log.e(TAG, "定位错误: ${location.errorInfo}, 错误码: ${location.errorCode}")
            // 首次定位失败，尝试切换到连续定位
            if (serviceState.isFirstLocation) {
                Log.d(TAG, "首次定位失败，切换到连续定位模式")
                startContinuousLocation()
            }
            return
        }

        Log.d(TAG, "定位成功: 精度=${location.accuracy}m, 速度=${location.speed}m/s, " +
                "来源=${if (location.locationType == 1) "GPS" else if (location.locationType == 2) "前次" else "网络"}")

        serviceState = serviceState.copy(
            lastLocation = location,
            lastUpdateTime = Date(),
            locationCount = serviceState.locationCount + 1
        )

        // 更新省电统计 - 记录每个定位请求
        (application as? TrackMasterApplication)?.let { app ->
            // 先让 Engine 处理位置，看看是否被处理
            app.checkGeoFences(location)
            // 假设每次定位都算实际请求（简化处理）
            app.appPreferences.incrementLocations(shouldProcess = true)
        }

        // 更新通知显示最新位置信息
        updateNotification()

        // 如果是首次定位成功，处理持久化状态恢复
        if (serviceState.isFirstLocation) {
            Log.d(TAG, "首次定位成功，处理持久化状态恢复")
            (application as? TrackMasterApplication)?.handleFirstLocationAfterRestore(location)

            // 延迟1秒后切换到连续定位模式
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                startContinuousLocation()
            }, 1000)
        }
    }

    /**
     * 强制刷新位置 - 供UI调用
     */
    fun refreshLocation() {
        try {
            locationClient?.stopLocation()
            locationClient?.startLocation()
            Log.d(TAG, "手动刷新位置")
        } catch (e: Exception) {
            Log.e(TAG, "刷新位置失败", e)
        }
    }
}
