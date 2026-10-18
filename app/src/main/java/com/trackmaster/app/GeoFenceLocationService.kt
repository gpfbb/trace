package com.trackmaster.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import com.trackmaster.app.utils.AppLog
import androidx.core.app.NotificationCompat
import com.amap.api.location.AMapLocation
import com.trackmaster.app.locationservice.Location
import com.trackmaster.app.locationservice.LocationType
import com.trackmaster.app.locationservice.LocationProviderManager
import com.trackmaster.app.locationservice.PowerSaveController
import com.trackmaster.app.core.PowerMode
import com.trackmaster.app.core.AccelerometerData
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

class GeoFenceLocationService : Service() {

    companion object {
        private const val TAG = "GeoFenceLocationService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "geofence_location_channel"
        private const val CHANNEL_NAME = "地理围栏定位服务"
        private const val FIRST_LOCATION_TIMEOUT = 20000L
        private const val MAX_FIRST_LOCATION_RETRIES = 5
    }

    private val binder = LocalBinder()

    private val providerManager by lazy {
        LocationProviderManager(
            context = this,
            appProvider = { application as? TrackMasterApplication },
            onLocation = { aMapLoc -> onLocationChanged(aMapLoc) },
            onError = { code, info -> onLocationError(code, info) }
        )
    }

    private val powerSaveController by lazy {
        PowerSaveController(context = this).also {
            it.providerControl = object : PowerSaveController.LocationProviderControl {
                override fun stop() = providerManager.stop()
                override fun resumeWithNormalMode() = providerManager.resumeWithNormalMode()
                override fun temporaryResumeForCheck() = providerManager.temporaryResumeForCheck()
                override fun setThrottledInterval(intervalMs: Long, forceGps: Boolean) =
                    providerManager.setThrottledInterval(intervalMs, forceGps)
            }
        }
    }

    private val stateListeners = CopyOnWriteArrayList<(LocationServiceState) -> Unit>()

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "map_provider") {
            AppLog.d(TAG, "检测到 mapProvider 变化，重新初始化定位客户端")
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                providerManager.reinit()
                startLocationWithFastFirst()
            }
        }
    }

    private var firstLocationRetryCount = 0
    private var lastAppliedGpsIntervalMs = 0L

    // ============================================================
    // 加速度传感器（用于静止检测）
    // ============================================================
    private var sensorManager: SensorManager? = null
    @Volatile private var accelerometerData: AccelerometerData? = null
    private val accelListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
                accelerometerData = AccelerometerData(
                    x = event.values[0],
                    y = event.values[1],
                    z = event.values[2],
                    timestamp = System.currentTimeMillis()
                )
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
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
        AppLog.d(TAG, "定位服务创建")
        createNotificationChannel()
        powerSaveController.restoreServiceState()
        // 修复进程死后状态矛盾：服务重启时 isFirstLocation=true，但 isLocationClientStopped 可能被恢复为 true
        // 导致首次定位回调进入 handleStoppedState 分支，发起冲突的临时检查请求
        if (powerSaveController.isLocationClientStopped) {
            AppLog.w(TAG, "检测到进程死后状态矛盾：重置 isLocationClientStopped=false")
            powerSaveController.isLocationClientStopped = false
        }
        providerManager.init()
        (application as? TrackMasterApplication)?.appPreferences?.sharedPreferences
            ?.registerOnSharedPreferenceChangeListener(prefsListener)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { accel ->
            sensorManager?.registerListener(accelListener, accel, SensorManager.SENSOR_DELAY_NORMAL)
            AppLog.d(TAG, "加速度传感器已注册")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppLog.d(TAG, "定位服务启动")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, createNotification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, createNotification())
        }
        startLocationWithFastFirst()
        powerSaveController.startPeriodicCheck()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    /**
     * 取消 AlarmManager 周期唤醒。
     *
     * 只在用户主动停止追踪时调用（Application.stopLocationService）。
     * **不要**在 onDestroy() 里调用：服务被系统回收时同样会走 onDestroy，
     * 那时取消 Alarm 会让主服务永远无法被唤醒恢复。
     */
    fun cancelWakeupAlarm() {
        powerSaveController.cancelWakeupAlarm()
    }

    override fun onDestroy() {
        AppLog.d(TAG, "定位服务销毁")
        powerSaveController.stopPeriodicCheck()
        providerManager.stop()
        providerManager.removeCallback()
        providerManager.destroy()
        (application as? TrackMasterApplication)?.appPreferences?.sharedPreferences
            ?.unregisterOnSharedPreferenceChangeListener(prefsListener)
        sensorManager?.unregisterListener(accelListener)
        super.onDestroy()
        AppLog.d(TAG, "加速度传感器已注销")
    }

    // ============================================================
    // 通知管理
    // ============================================================

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "地理围栏后台定位服务"
                setShowBadge(false)
                setSound(null, null)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
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
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, createNotification())
    }

    // ============================================================
    // 定位控制
    // ============================================================

    private fun startLocationWithFastFirst() {
        try {
            firstLocationRetryCount = 0
            serviceState = serviceState.copy(isRunning = true, isFirstLocation = true)
            providerManager.startFastFirst()
            AppLog.d(TAG, "启动快速首次定位")
        } catch (e: Exception) {
            AppLog.e(TAG, "启动定位失败", e)
            serviceState = serviceState.copy(isRunning = false)
        }
    }

    fun setPowerMode(mode: PowerMode) {
        val engine = (application as? TrackMasterApplication)?.engine
        engine?.setPowerMode(mode)

        if (powerSaveController.isLocationClientStopped) {
            AppLog.d(TAG, "电源模式切换: 定位已停止，触发临时检查")
            powerSaveController.isTemporaryCheck = true
            powerSaveController.lastResumeCheckTime = System.currentTimeMillis()
            providerManager.temporaryResumeForCheck()
        } else {
            powerSaveController.consecutiveNotProcessCount = 0
            val engineInterval = engine?.lastComputedIntervalMs ?: 60_000L
            lastAppliedGpsIntervalMs = engineInterval
            providerManager.setInterval(engineInterval)
            AppLog.d(TAG, "电源模式切换: 定位运行中，重置省电计数器，同步GPS间隔到${engineInterval}ms")
        }
    }

    fun addStateListener(listener: (LocationServiceState) -> Unit) {
        stateListeners.add(listener)
        listener(serviceState)
    }

    fun removeStateListener(listener: (LocationServiceState) -> Unit) {
        stateListeners.remove(listener)
    }

    fun isRunning(): Boolean = powerSaveController.isRunning()

    fun refreshLocation() {
        providerManager.refresh()
    }

    fun scheduleNextWakeup() = powerSaveController.scheduleNextWakeup()

    fun performWakeupLocation() = powerSaveController.performWakeupLocation()

    fun onLocationPermissionGranted() {
        AppLog.d(TAG, "=== onLocationPermissionGranted() 被调用 ===")
        if (serviceState.isRunning) {
            AppLog.d(TAG, "onLocationPermissionGranted: 定位已在运行，跳过")
            return
        }
        startLocationWithFastFirst()
    }

    // ============================================================
    // 定位回调
    // ============================================================

    fun onLocationError(errorCode: Int, errorInfo: String) {
        AppLog.e(TAG, "定位错误: $errorInfo, 错误码: $errorCode")
        if (serviceState.isFirstLocation) {
            if (firstLocationRetryCount < MAX_FIRST_LOCATION_RETRIES) {
                firstLocationRetryCount++
                AppLog.d(TAG, "首次定位失败，重试第${firstLocationRetryCount}次")
                providerManager.startFastFirst()
            } else {
                AppLog.d(TAG, "定位失败次数过多，切换到连续定位模式")
                serviceState = serviceState.copy(isFirstLocation = false)
                providerManager.stop()
                providerManager.startContinuous(60_000L)
            }
        }
    }

    fun onLocationChanged(location: AMapLocation?) {
        location ?: return

        if (location.errorCode != 0) {
            AppLog.e(TAG, "定位错误: ${location.errorInfo}, 错误码: ${location.errorCode}")
            if (serviceState.isFirstLocation) {
                if (firstLocationRetryCount < MAX_FIRST_LOCATION_RETRIES) {
                    firstLocationRetryCount++
                    AppLog.d(TAG, "首次定位失败，重试第${firstLocationRetryCount}次")
                    providerManager.startFastFirst()
                } else {
                    AppLog.d(TAG, "GPS定位失败次数过多，切换到连续定位模式")
                    serviceState = serviceState.copy(isFirstLocation = false)
                    providerManager.stop()
                    providerManager.startContinuous(60_000L)
                }
            } else {
                AppLog.d(TAG, "连续定位模式下定位失败，等待下次定时调度")
                if (powerSaveController.isTemporaryCheck) {
                    powerSaveController.isTemporaryCheck = false
                    powerSaveController.temporaryCheckCompleted = false
                    AppLog.d(TAG, "省电: 临时检查GPS失败，重置标志，继续停止+调度Alarm")
                    powerSaveController.isLocationClientStopped = true
                    powerSaveController.lastResumeCheckTime = System.currentTimeMillis()
                    providerManager.stop()
                    powerSaveController.scheduleNextWakeup()
                }
            }
            return
        }

        val isValidCoordinate = location.latitude != 0.0 && location.longitude != 0.0
        if (!isValidCoordinate) {
            AppLog.w(TAG, "坐标无效 (0,0)，跳过此次定位")
            if (serviceState.isFirstLocation && firstLocationRetryCount < MAX_FIRST_LOCATION_RETRIES) {
                firstLocationRetryCount++
                providerManager.startFastFirst()
            }
            return
        }

        AppLog.d(TAG, "定位成功: 精度=${location.accuracy}m, 速度=${location.speed}m/s, " +
                "来源=${if (location.locationType == 1) "GPS" else if (location.locationType == 2) "前次" else "网络"}")

        val app = application as? TrackMasterApplication
        val shouldProcess = app?.checkGeoFences(toLocation(location), accelerometerData) ?: false

        val engineInterval = app?.engine?.lastComputedIntervalMs ?: 60_000L
        val matchedFences = app?.currentMatchedFences ?: emptyList()
        val isInFence = matchedFences.isNotEmpty()

        // 省电核心：每次定位回调都同步 GPS 硬件间隔到 SmartScheduler 的计算值
        // 之前只在 shouldProcess=false 时更新，导致 GPS 间隔永远滞后一个阶梯
        if (!powerSaveController.isLocationClientStopped && !serviceState.isFirstLocation) {
            if (engineInterval != lastAppliedGpsIntervalMs) {
                providerManager.setInterval(engineInterval)
                lastAppliedGpsIntervalMs = engineInterval
                AppLog.d(TAG, "GPS间隔同步: ${engineInterval}ms (level=${app?.engine?.getScheduler()?.getLevel()})")
            }
        }

        if (shouldProcess) {
            val currentFenceName = if (isInFence) matchedFences.firstOrNull()?.name else null

            serviceState = serviceState.copy(
                lastLocation = toLocation(location),
                lastUpdateTime = Date(),
                locationCount = serviceState.locationCount + 1,
                isInsideFence = isInFence,
                currentFenceName = currentFenceName
            )

            AppLog.d(TAG, "省电检查: shouldProcess=$shouldProcess, isInFence=$isInFence, isStopped=${powerSaveController.isLocationClientStopped}")

            powerSaveController.updatePowerSavingState(
                shouldProcess = shouldProcess,
                engineIntervalMs = engineInterval,
                isInFence = isInFence,
            )
        } else {
            AppLog.d(TAG, "Throttled回调（不更新UI）: shouldProcess=$shouldProcess, isStopped=${powerSaveController.isLocationClientStopped}")

            powerSaveController.updatePowerSavingState(
                shouldProcess = false,
                engineIntervalMs = engineInterval,
                isInFence = isInFence,
            )
        }

        updateNotification()

        if (serviceState.isFirstLocation) {
            firstLocationRetryCount = 0
            AppLog.d(TAG, "首次定位成功，处理持久化状态恢复")
            (application as? TrackMasterApplication)?.handleFirstLocationAfterRestore(toLocation(location))

            val initialInterval = app?.engine?.lastComputedIntervalMs ?: 60_000L
            lastAppliedGpsIntervalMs = initialInterval
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                providerManager.startContinuous(initialInterval)
                serviceState = serviceState.copy(isFirstLocation = false)
            }, 1000)
        }
    }

    /** 将 AMapLocation 转换为统一的 Location 类型 */
    private fun toLocation(aMapLocation: AMapLocation): Location {
        val locationType = when (aMapLocation.locationType) {
            1 -> LocationType.GPS
            2 -> LocationType.NETWORK
            else -> LocationType.UNKNOWN
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
