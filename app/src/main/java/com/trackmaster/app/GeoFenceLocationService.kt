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

data class LocationServiceState(
    val isRunning: Boolean = false,
    val schedulerLevel: Int = 0,
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
    }

    private val binder = LocalBinder()
    private var locationClient: AMapLocationClient? = null
    private var locationOption: AMapLocationClientOption? = null
    private val stateListeners = mutableListOf<(LocationServiceState) -> Unit>()

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
        registerReceiver(screenReceiver, filter)
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

    private fun updateLocationOptions(intervalMs: Long = 5_000L) {
        locationOption = AMapLocationClientOption().apply {
            locationPurpose = AMapLocationClientOption.AMapLocationPurpose.Transport
            locationMode = AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
            isOnceLocation = false
            isNeedAddress = true
            isWifiScan = true
            isSensorEnable = true

            interval = intervalMs
            Log.d(TAG, "定位间隔设置为: ${intervalMs}ms")
        }
        locationClient?.setLocationOption(locationOption)
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

        Log.d(TAG, "定位更新: 精度=${location.accuracy}m, 速度=${location.speed}m/s")

        serviceState = serviceState.copy(
            lastLocation = location,
            lastUpdateTime = Date(),
            locationCount = serviceState.locationCount + 1
        )

        // 围栏检测委托给 Engine（通过 TrackMasterApplication.checkGeoFences）
        (application as? TrackMasterApplication)?.checkGeoFences(location)
    }
}
