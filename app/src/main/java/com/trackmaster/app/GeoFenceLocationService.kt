package com.trackmaster.app

import android.app.AlarmManager
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
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.amap.api.location.AMapLocation
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import com.amap.api.location.AMapLocationListener
import com.trackmaster.app.locationservice.Location
import java.util.Date
import java.util.concurrent.CopyOnWriteArrayList

data class LocationServiceState(
    val isRunning: Boolean = false,
    val schedulerLevel: Int = 0,
    val lastLocation: Location? = null,
    val lastUpdateTime: Date? = null,
    val locationCount: Int = 0,
    val isFirstLocation: Boolean = true,
    val currentFenceName: String? = null,
    val isInsideFence: Boolean = false
)

class GeoFenceLocationService : Service(), AMapLocationListener {

    companion object {
        private const val TAG = "GeoFenceLocationService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "geofence_location_channel"
        private const val CHANNEL_NAME = "地理围栏定位服务"
        private const val FIRST_LOCATION_TIMEOUT = 20000L
        private const val STOP_THRESHOLD = 3
        private const val CHECK_INTERVAL_WHEN_STOPPED = 5

        const val ACTION_WAKEUP = "com.trackmaster.app.ACTION_LOCATION_WAKEUP"
        private const val ALARM_REQUEST_CODE = 1001
        private const val WAKEUP_INTERVAL_MS = CHECK_INTERVAL_WHEN_STOPPED * 60 * 1000L
    }

    private val binder = LocalBinder()

    // 保留旧的 AMapLocationClient 引用用于兼容
    private var locationClient: AMapLocationClient? = null
    private var locationOption: AMapLocationClientOption? = null

    private val stateListeners = CopyOnWriteArrayList<(LocationServiceState) -> Unit>()

    private var firstLocationRetryCount = 0
    private val MAX_FIRST_LOCATION_RETRIES = 5

    private var consecutiveNotProcessCount = 0
    private var isLocationClientStopped = false
    private var lastResumeCheckTime = 0L
    private var isTemporaryCheck = false

    private val powerSaveHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val powerSaveCheckRunnable = object : Runnable {
        override fun run() {
            if (isLocationClientStopped) {
                val now = System.currentTimeMillis()
                if (now - lastResumeCheckTime >= CHECK_INTERVAL_WHEN_STOPPED * 60 * 1000L) {
                    Log.d(TAG, "定时检查：尝试恢复定位")
                    lastResumeCheckTime = now
                    temporaryResumeForCheck()
                }
            }
            powerSaveHandler.postDelayed(this, CHECK_INTERVAL_WHEN_STOPPED * 60 * 1000L)
        }
    }

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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, createNotification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, createNotification())
        }
        startLocationWithFastFirst()
        powerSaveHandler.post(powerSaveCheckRunnable)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "定位服务销毁")
        powerSaveHandler.removeCallbacks(powerSaveCheckRunnable)
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
            locationMode = AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
            isOnceLocation = true
            isNeedAddress = false
            isWifiScan = true
            isSensorEnable = true
            httpTimeOut = FIRST_LOCATION_TIMEOUT
        }
        locationClient?.setLocationOption(locationOption)
        Log.d(TAG, "配置快速首次定位模式")
    }

    private fun updateLocationOptionsForContinuous(intervalMs: Long = 5000L) {
        locationOption = AMapLocationClientOption().apply {
            locationMode = AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
            isOnceLocation = false
            isNeedAddress = false
            isWifiScan = true
            isSensorEnable = true
            interval = intervalMs
            httpTimeOut = maxOf(intervalMs * 3, 10000L)
        }
        locationClient?.setLocationOption(locationOption)
        Log.d(TAG, "配置连续定位模式，间隔: ${intervalMs}ms, httpTimeOut: ${maxOf(intervalMs * 3, 10000L)}ms")
    }

    private fun startLocationWithFastFirst() {
        try {
            firstLocationRetryCount = 0
            serviceState = serviceState.copy(isRunning = true, isFirstLocation = true)
            updateLocationOptionsForFastFirst()
            locationClient?.startLocation()
            Log.d(TAG, "启动快速首次定位")
        } catch (e: Exception) {
            Log.e(TAG, "启动定位失败", e)
            serviceState = serviceState.copy(isRunning = false)
        }
    }

    fun setPowerMode(mode: com.trackmaster.app.core.PowerMode) {
        val engine = (application as? TrackMasterApplication)?.engine
        engine?.setPowerMode(mode)

        if (isLocationClientStopped) {
            Log.d(TAG, "电源模式切换: 定位已停止，触发临时检查")
            isTemporaryCheck = true
            lastResumeCheckTime = System.currentTimeMillis()
            temporaryResumeForCheck()
        } else {
            consecutiveNotProcessCount = 0
            Log.d(TAG, "电源模式切换: 定位运行中，重置省电计数器")
        }
    }

    fun addStateListener(listener: (LocationServiceState) -> Unit) {
        stateListeners.add(listener)
        listener(serviceState)
    }

    fun removeStateListener(listener: (LocationServiceState) -> Unit) {
        stateListeners.remove(listener)
    }

    private fun startContinuousLocation() {
        startContinuousLocationWithSmartInterval(30_000L)
    }

    private fun startContinuousLocationWithSmartInterval(baseIntervalMs: Long) {
        try {
            locationClient?.stopLocation()

            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                try {
                    updateLocationOptionsForContinuous(baseIntervalMs)
                    locationClient?.startLocation()
                    serviceState = serviceState.copy(isFirstLocation = false)
                    Log.d(TAG, "切换到连续定位模式，基础间隔: ${baseIntervalMs}ms")
                } catch (e: Exception) {
                    Log.e(TAG, "启动连续定位失败", e)
                }
            }, 100)
        } catch (e: Exception) {
            Log.e(TAG, "停止定位失败", e)
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

    private fun updatePowerSavingState(shouldProcess: Boolean) {
        val now = System.currentTimeMillis()

        if (isTemporaryCheck) {
            isTemporaryCheck = false
            if (shouldProcess) {
                Log.d(TAG, "省电: 临时检查需要处理，恢复定位")
                isLocationClientStopped = false
                consecutiveNotProcessCount = 0
                cancelWakeupAlarm()
                resumeLocationWithNormalMode()
            } else {
                Log.d(TAG, "省电: 临时检查不需要处理，继续停止，调度 Alarm")
                isLocationClientStopped = true
                consecutiveNotProcessCount = 0
                lastResumeCheckTime = now
                stopLocation()
                scheduleNextWakeup()
            }
            return
        }

        if (isLocationClientStopped) {
            if (now - lastResumeCheckTime >= CHECK_INTERVAL_WHEN_STOPPED * 60 * 1000L) {
                Log.d(TAG, "省电: 定时检查，临时恢复定位")
                lastResumeCheckTime = now
                isTemporaryCheck = true
                resumeLocationWithNormalMode()
            }
        } else {
            if (shouldProcess) {
                consecutiveNotProcessCount = 0
                cancelWakeupAlarm()
                Log.d(TAG, "省电: 需要处理，重置计数")
            } else {
                consecutiveNotProcessCount++
                Log.d(TAG, "省电: 不需要处理 ($consecutiveNotProcessCount/$STOP_THRESHOLD)")

                if (consecutiveNotProcessCount >= STOP_THRESHOLD) {
                    Log.d(TAG, "省电: 连续${consecutiveNotProcessCount}次不需要处理，停止定位，调度 Alarm")
                    isLocationClientStopped = true
                    lastResumeCheckTime = now
                    stopLocation()
                    scheduleNextWakeup()
                }
            }
        }
    }

    fun scheduleNextWakeup() {
        try {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(this, WakeupReceiver::class.java).apply {
                action = ACTION_WAKEUP
            }
            val pendingIntent = PendingIntent.getBroadcast(
                this,
                ALARM_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val triggerTime = SystemClock.elapsedRealtime() + WAKEUP_INTERVAL_MS

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
            } else {
                alarmManager.setExact(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
            }
            Log.d(TAG, "已调度 AlarmManager 唤醒，${WAKEUP_INTERVAL_MS / 1000 / 60}分钟后")
        } catch (e: Exception) {
            Log.e(TAG, "调度 Alarm 失败", e)
        }
    }

    fun cancelWakeupAlarm() {
        try {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(this, WakeupReceiver::class.java).apply {
                action = ACTION_WAKEUP
            }
            val pendingIntent = PendingIntent.getBroadcast(
                this,
                ALARM_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
            Log.d(TAG, "已取消 AlarmManager 唤醒")
        } catch (e: Exception) {
            Log.e(TAG, "取消 Alarm 失败", e)
        }
    }

    fun performWakeupLocation() {
        Log.d(TAG, "执行唤醒定位检查")
        isTemporaryCheck = true
        lastResumeCheckTime = System.currentTimeMillis()
        temporaryResumeForCheck()
    }

    fun isRunning(): Boolean = !isLocationClientStopped

    private fun resumeLocation() {
        try {
            locationOption?.let { locationClient?.setLocationOption(it) }
            locationClient?.startLocation()
            serviceState = serviceState.copy(isRunning = true)
            Log.d(TAG, "定位已恢复")
        } catch (e: Exception) {
            Log.e(TAG, "恢复定位失败", e)
        }
    }

    private fun resumeLocationWithNormalMode() {
        try {
            updateLocationOptionsForContinuous(30_000L)
            locationClient?.startLocation()
            serviceState = serviceState.copy(isRunning = true, isFirstLocation = false)
            Log.d(TAG, "恢复正常定位模式")
        } catch (e: Exception) {
            Log.e(TAG, "恢复正常定位失败", e)
        }
    }

    private fun temporaryResumeForCheck() {
        try {
            isTemporaryCheck = true
            locationOption = AMapLocationClientOption().apply {
                locationMode = AMapLocationClientOption.AMapLocationMode.Battery_Saving
                isOnceLocation = true
                isNeedAddress = false
                isWifiScan = true
                isSensorEnable = true
                httpTimeOut = 5000L
            }
            locationClient?.setLocationOption(locationOption)
            locationClient?.startLocation()
            Log.d(TAG, "临时恢复定位检查")
        } catch (e: Exception) {
            Log.e(TAG, "临时恢复定位失败", e)
        }
    }

    override fun onLocationChanged(location: AMapLocation?) {
        location ?: return

        if (location.errorCode != 0) {
            Log.e(TAG, "定位错误: ${location.errorInfo}, 错误码: ${location.errorCode}")
            if (serviceState.isFirstLocation) {
                if (firstLocationRetryCount < MAX_FIRST_LOCATION_RETRIES) {
                    firstLocationRetryCount++
                    Log.d(TAG, "首次定位失败，重试第${firstLocationRetryCount}次")
                    locationClient?.startLocation()
                } else {
                    Log.d(TAG, "GPS定位失败次数过多，切换到连续定位模式")
                    serviceState = serviceState.copy(isFirstLocation = false)
                    locationClient?.stopLocation()
                    startContinuousLocationWithSmartInterval(60_000L)
                }
            }
            return
        }

        val isValidCoordinate = location.latitude != 0.0 && location.longitude != 0.0
        if (!isValidCoordinate) {
            Log.w(TAG, "坐标无效 (0,0)，跳过此次定位")
            if (serviceState.isFirstLocation && firstLocationRetryCount < MAX_FIRST_LOCATION_RETRIES) {
                firstLocationRetryCount++
                locationClient?.startLocation()
            }
            return
        }

        Log.d(TAG, "定位成功: 精度=${location.accuracy}m, 速度=${location.speed}m/s, " +
                "来源=${if (location.locationType == 1) "GPS" else if (location.locationType == 2) "前次" else "网络"}")

        val app = application as? TrackMasterApplication
        val shouldProcess = app?.checkGeoFences(location) ?: false

        val fix = com.trackmaster.app.core.LocationFix(
            lat = location.latitude,
            lng = location.longitude,
            accuracy = location.accuracy,
            timestamp = location.time
        )
        val isInFence = app?.engine?.isLocationInAnyFence(fix) == true

        val currentFenceName = if (isInFence) {
            val matchedIds = app?.engine?.currentMatchedFenceIds ?: emptyList()
            val firstMatchId = matchedIds.firstOrNull()
            if (firstMatchId != null) {
                app?.geoFences?.find { it.id == firstMatchId }?.name
            } else null
        } else null

        serviceState = serviceState.copy(
            lastLocation = toLocation(location),
            lastUpdateTime = Date(),
            locationCount = serviceState.locationCount + 1,
            isInsideFence = isInFence,
            currentFenceName = currentFenceName
        )

        Log.d(TAG, "省电检查: shouldProcess=$shouldProcess, isInFence=$isInFence, isStopped=$isLocationClientStopped")

        val shouldStopForPowerSave = shouldProcess.not() && isInFence.not()
        updatePowerSavingState(shouldStopForPowerSave)

        updateNotification()

        if (serviceState.isFirstLocation) {
            firstLocationRetryCount = 0
            Log.d(TAG, "首次定位成功，处理持久化状态恢复")
            (application as? TrackMasterApplication)?.handleFirstLocationAfterRestore(location)

            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                startContinuousLocationWithSmartInterval(30_000L)
            }, 1000)
        }
    }

    fun refreshLocation() {
        try {
            locationClient?.stopLocation()
            locationClient?.startLocation()
            Log.d(TAG, "手动刷新位置")
        } catch (e: Exception) {
            Log.e(TAG, "刷新位置失败", e)
        }
    }

    /** 将 AMapLocation 转换为统一的 Location 类型 */
    private fun toLocation(aMapLocation: AMapLocation): Location {
        val locationType = when (aMapLocation.locationType) {
            1 -> com.trackmaster.app.locationservice.LocationType.GPS
            2 -> com.trackmaster.app.locationservice.LocationType.NETWORK
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
}
