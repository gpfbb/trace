package com.trackmaster.app

import android.app.AlarmManager
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
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.amap.api.location.AMapLocation
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import com.amap.api.location.AMapLocationListener
import com.trackmaster.app.locationservice.Location
import com.trackmaster.app.locationservice.LocationType
import com.trackmaster.app.locationservice.LocationCallback
import com.trackmaster.app.locationservice.LocationProvider
import com.trackmaster.app.locationservice.google.GoogleLocationProvider
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

class GeoFenceLocationService : Service(), AMapLocationListener {

    companion object {
        private const val TAG = "GeoFenceLocationService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "geofence_location_channel"
        private const val CHANNEL_NAME = "地理围栏定位服务"
        private const val FIRST_LOCATION_TIMEOUT = 20000L
        private const val STOP_THRESHOLD = 5
        private const val CHECK_INTERVAL_WHEN_STOPPED = 8

        const val ACTION_WAKEUP = "com.trackmaster.app.ACTION_LOCATION_WAKEUP"
        private const val ALARM_REQUEST_CODE = 1001
        private const val WAKEUP_INTERVAL_MS = CHECK_INTERVAL_WHEN_STOPPED * 60 * 1000L

        // AMap 定位类型常量（与 AMapLocation.locationType 对应）
        private const val AMAP_TYPE_GPS = 1
        private const val AMAP_TYPE_NETWORK = 2
    }

    private val binder = LocalBinder()

    // 保留旧的 AMapLocationClient 引用用于兼容
    private var locationClient: AMapLocationClient? = null
    private var locationProvider: LocationProvider? = null
    private var locationOption: AMapLocationClientOption? = null
    private var isUsingGoogleProvider = false

    private val stateListeners = CopyOnWriteArrayList<(LocationServiceState) -> Unit>()

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "map_provider") {
            Log.d(TAG, "检测到 mapProvider 变化，重新初始化定位客户端")
            reinitLocationClient()
        }
    }

    private var firstLocationRetryCount = 0
    private val MAX_FIRST_LOCATION_RETRIES = 5

    private var consecutiveNotProcessCount = 0
    private var isLocationClientStopped = false
    private var lastResumeCheckTime = 0L
    private var isTemporaryCheck = false
    private var appCallback: LocationCallback? = null
    /** 临时检查是否已完成单次定位（避免连续启动） */
    private var temporaryCheckCompleted = false

    // ============================================================
    // 加速度传感器（用于静止检测）
    // ============================================================
    private var sensorManager: SensorManager? = null
    private var accelerometerData: AccelerometerData? = null
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
        // 监听 MapProvider 切换
        (application as? TrackMasterApplication)?.appPreferences?.sharedPreferences
            ?.registerOnSharedPreferenceChangeListener(prefsListener)
        // 【Bug修复】注册加速度传感器，用于静止检测（GPS速度不可靠）
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { accel ->
            sensorManager?.registerListener(accelListener, accel, SensorManager.SENSOR_DELAY_NORMAL)
            Log.d(TAG, "加速度传感器已注册")
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
        // 移除定位回调，防止泄漏
        locationProvider?.removeLocationCallback()
        if (isUsingGoogleProvider) {
            (locationProvider as? GoogleLocationProvider)?.destroy()
        } else {
            locationClient?.onDestroy()
        }
        (application as? TrackMasterApplication)?.appPreferences?.sharedPreferences
            ?.unregisterOnSharedPreferenceChangeListener(prefsListener)
        // 注销加速度传感器
        sensorManager?.unregisterListener(accelListener)
        Log.d(TAG, "加速度传感器已注销")
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
        val app = application as? TrackMasterApplication
        val mapProvider = app?.appPreferences?.mapProvider ?: MapProvider.AMAP
        isUsingGoogleProvider = (mapProvider == MapProvider.GOOGLE)

        if (isUsingGoogleProvider) {
            // Google Fused Location Provider（全球精度）
            try {
                locationProvider = GoogleLocationProvider(applicationContext)
                appCallback = object : LocationCallback {
                    override fun onLocationChanged(location: Location) {
                        // 将 unified Location 转换为 AMapLocation，复用现有的 onLocationChanged 处理逻辑
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
                        this@GeoFenceLocationService.onLocationChanged(aMapLoc)
                    }
                    override fun onLocationError(errorCode: Int, errorInfo: String) {
                        this@GeoFenceLocationService.onLocationError(errorCode, errorInfo)
                    }
                }
                val cb = appCallback
                val provider = locationProvider
                if (cb != null && provider != null) {
                    provider.setLocationCallback(cb)
                }
                Log.d(TAG, "初始化 Google Fused Location Provider")
            } catch (e: Exception) {
                Log.e(TAG, "初始化 Google 定位客户端失败，降级到 AMap", e)
                initAmapClient()
            }
        } else {
            // 高德定位
            initAmapClient()
        }
    }

    private fun reinitLocationClient() {
        Log.d(TAG, "重新初始化定位客户端")
        // 停止旧 provider
        stopLocation()
        if (isUsingGoogleProvider) {
            (locationProvider as? GoogleLocationProvider)?.destroy()
        } else {
            locationClient?.stopLocation()
        }
        locationProvider = null
        locationClient = null
        // 重新初始化
        initLocationClient()
        // 重新启动定位
        startLocationWithFastFirst()
    }

    private fun initAmapClient() {
        isUsingGoogleProvider = false
        try {
            AMapLocationClient(applicationContext).apply {
                // 隐私合规：必须在 AMapLocationClient 创建后、setLocationListener 之前调用
                AMapLocationClient.updatePrivacyShow(applicationContext, true, true)
                AMapLocationClient.updatePrivacyAgree(applicationContext, true)
            }.also { locationClient = it }
            locationClient?.setLocationListener(this)
            Log.d(TAG, "初始化 AMap 定位客户端（隐私合规已调用）")
        } catch (e: Exception) {
            Log.e(TAG, "初始化定位客户端失败", e)
        }
    }

    /**
     * 当权限授予时调用（来自 UI 层权限回调）
     * 如果之前因权限未授予 Google provider 启动失败，在这里重试
     */
    fun onLocationPermissionGranted() {
        Log.d(TAG, "=== onLocationPermissionGranted() 被调用 ===")
        if (serviceState.isRunning) {
            Log.d(TAG, "onLocationPermissionGranted: 定位已在运行，跳过")
            return
        }
        // 重试启动定位
        startLocationWithFastFirst()
    }

    /**
     * 处理 Google Provider 定位错误（来自 LocationCallback）
     */
    fun onLocationError(errorCode: Int, errorInfo: String) {
        Log.e(TAG, "Google定位错误: $errorInfo, 错误码: $errorCode")
        if (serviceState.isFirstLocation) {
            if (firstLocationRetryCount < MAX_FIRST_LOCATION_RETRIES) {
                firstLocationRetryCount++
                Log.d(TAG, "Google首次定位失败，重试第${firstLocationRetryCount}次")
                if (isUsingGoogleProvider) {
                    locationProvider?.start()
                } else {
                    locationClient?.startLocation()
                }
            } else {
                Log.d(TAG, "Google定位失败次数过多，切换到连续定位模式")
                serviceState = serviceState.copy(isFirstLocation = false)
                if (isUsingGoogleProvider) {
                    locationProvider?.stop()
                } else {
                    locationClient?.stopLocation()
                }
                startContinuousLocationWithSmartInterval(60_000L)
            }
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

    private fun updateLocationOptionsForContinuous(intervalMs: Long) {
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
        // 【Bug修复】setOption后需要stop+start才能让新interval生效
        locationClient?.stopLocation()
        locationClient?.startLocation()
        Log.d(TAG, "配置连续定位模式，间隔: ${intervalMs}ms, httpTimeOut: ${maxOf(intervalMs * 3, 10000L)}ms")
    }

    private fun startLocationWithFastFirst() {
        try {
            firstLocationRetryCount = 0
            serviceState = serviceState.copy(isRunning = true, isFirstLocation = true)
            if (isUsingGoogleProvider) {
                locationProvider?.start()
            } else {
                // 【P1修复】先stop再设置option+start，确保isOnceLocation=true模式正确切换
                // 防止service restart时旧配置残留导致定位模式混乱
                locationClient?.stopLocation()
                updateLocationOptionsForFastFirst()
                locationClient?.startLocation()
            }
            Log.d(TAG, "启动快速首次定位, provider=${if (isUsingGoogleProvider) "Google" else "AMap"}")
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
            // 【Bug修复】切换电源模式后同步 GPS interval
            // 新模式 level=0 的起始间隔为 60s，立即同步到 GPS provider
            // 避免引擎已切换模式但 GPS 仍以旧间隔运行
            val newIntervalMs = 60_000L
            if (isUsingGoogleProvider) {
                locationProvider?.setInterval(newIntervalMs)
            } else {
                updateLocationOptionsForContinuous(newIntervalMs)
            }
            Log.d(TAG, "电源模式切换: 定位运行中，重置省电计数器，同步GPS间隔到${newIntervalMs}ms")
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
        startContinuousLocationWithSmartInterval(60_000L)
    }

    private fun startContinuousLocationWithSmartInterval(baseIntervalMs: Long) {
        try {
            if (isUsingGoogleProvider) {
                locationProvider?.stop()
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    try {
                        // 【Bug修复】Google provider 需要显式调用 setInterval 使 GPS 节流生效
                        locationProvider?.setInterval(baseIntervalMs)
                        locationProvider?.start()
                        serviceState = serviceState.copy(isFirstLocation = false)
                        Log.d(TAG, "切换到 Google 连续定位模式，间隔: ${baseIntervalMs}ms")
                    } catch (e: Exception) {
                        Log.e(TAG, "启动 Google 连续定位失败", e)
                    }
                }, 100)
            } else {
                locationClient?.stopLocation()
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    try {
                        updateLocationOptionsForContinuous(baseIntervalMs)
                        locationClient?.startLocation()
                        serviceState = serviceState.copy(isFirstLocation = false)
                        Log.d(TAG, "切换到 AMap 连续定位模式，基础间隔: ${baseIntervalMs}ms")
                    } catch (e: Exception) {
                        Log.e(TAG, "启动连续定位失败", e)
                    }
                }, 100)
            }
        } catch (e: Exception) {
            Log.e(TAG, "停止定位失败", e)
        }
    }

    private fun stopLocation() {
        try {
            if (isUsingGoogleProvider) {
                locationProvider?.stop()
            } else {
                locationClient?.stopLocation()
            }
            serviceState = serviceState.copy(isRunning = false)
            Log.d(TAG, "定位已停止")
        } catch (e: Exception) {
            Log.e(TAG, "停止定位失败", e)
        }
    }

    private fun updatePowerSavingState(shouldProcess: Boolean, engineIntervalMs: Long = 60_000L) {
        val now = System.currentTimeMillis()

        if (isTemporaryCheck) {
            isTemporaryCheck = false
            temporaryCheckCompleted = true
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
                temporaryCheckCompleted = false
                temporaryResumeForCheck()
            }
        } else {
            if (shouldProcess) {
                // 【Bug修复】只有离开围栏时才重置计数器
                // 围栏内processed：保持counter值，让围栏内throttling状态锁定
                if (serviceState.isInsideFence) {
                    // 围栏内processed fix：保持counter，不要重置到0
                    // 这样下次throttled cycle不需要重新从0开始累加
                    Log.d(TAG, "省电: 围栏内处理，重置计数(保持throttled状态)")
                } else {
                    consecutiveNotProcessCount = 0
                    cancelWakeupAlarm()
                    Log.d(TAG, "省电: 需要处理，重置计数")
                }
            } else {
                consecutiveNotProcessCount++
                Log.d(TAG, "省电: 不需要处理 ($consecutiveNotProcessCount/$STOP_THRESHOLD)")

                // 【Bug修复】围栏内阈值：先降频（2次），STOP_THRESHOLD=5 对所有场景生效（5次后停止GPS）
                // 围栏内用户对定位精度要求低，2次throttled即可触发降频
                // 但无论围栏内外，STOP_THRESHOLD=5 统一触发停止GPS（防止围栏内静止时GPS永不停止）
                val INSIDE_FENCE_THRESHOLD = 2
                if (consecutiveNotProcessCount >= INSIDE_FENCE_THRESHOLD && serviceState.isInsideFence) {
                    // 【Bug修复】使用 engine.lastComputedIntervalMs 而非硬编码 60s
                    // engine 已根据 stationary 检测计算出合适的下次间隔，直接使用
                    val engineInterval = engineIntervalMs
                    Log.d(TAG, "省电: 围栏内连续${consecutiveNotProcessCount}次不处理，降频到${engineInterval}ms/次继续监控")
                    // 【Bug修复】Google provider 也需要 setInterval，否则降频不生效
                    if (isUsingGoogleProvider) {
                        locationProvider?.setInterval(engineInterval)
                    } else {
                        updateLocationOptionsForContinuous(engineInterval)
                    }
                    if (!serviceState.isRunning) {
                        locationClient?.startLocation()
                        serviceState = serviceState.copy(isRunning = true)
                    }
                }
                // STOP_THRESHOLD=5 对所有场景统一生效（围栏内/外均适用）
                if (consecutiveNotProcessCount >= STOP_THRESHOLD) {
                    Log.d(TAG, "省电: 连续${consecutiveNotProcessCount}次不处理，停止GPS，调度Alarm")
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
            if (isUsingGoogleProvider) {
                locationProvider?.start()
            } else {
                locationOption?.let { locationClient?.setLocationOption(it) }
                locationClient?.startLocation()
            }
            serviceState = serviceState.copy(isRunning = true)
            Log.d(TAG, "定位已恢复")
        } catch (e: Exception) {
            Log.e(TAG, "恢复定位失败", e)
        }
    }

    private fun resumeLocationWithNormalMode() {
        try {
            if (isUsingGoogleProvider) {
                locationProvider?.start()
            } else {
                updateLocationOptionsForContinuous(60_000L)
                locationClient?.startLocation()
            }
            serviceState = serviceState.copy(isRunning = true, isFirstLocation = false)
            Log.d(TAG, "恢复正常定位模式")
        } catch (e: Exception) {
            Log.e(TAG, "恢复正常定位失败", e)
        }
    }

    private fun temporaryResumeForCheck() {
        try {
            if (isUsingGoogleProvider) {
                // 【Bug修复】显式设置高精度模式，确保临时检查使用GPS精度
                // 防止服务停止前切换到省电模式，导致临时检查精度不足
                (locationProvider as? GoogleLocationProvider)?.setLocationMode(
                    com.trackmaster.app.locationservice.LocationMode.HIGH_ACCURACY
                )
                locationProvider?.start()
                // 【P0修复】flag必须在start()成功后才设置，避免start()失败后状态不一致
                isTemporaryCheck = true
            } else {
                // 【P1修复】先stop再设置option+start，确保isOnceLocation=true切换生效
                // 否则AMap SDK可能仍使用之前的连续定位配置，导致临时检查变成连续高频定位
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
                // 【P0修复】flag必须在start()成功后才设置，避免start()失败后状态不一致
                isTemporaryCheck = true
            }
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
                    if (isUsingGoogleProvider) {
                        locationProvider?.start()
                    } else {
                        locationClient?.startLocation()
                    }
                } else {
                    Log.d(TAG, "GPS定位失败次数过多，切换到连续定位模式")
                    serviceState = serviceState.copy(isFirstLocation = false)
                    if (isUsingGoogleProvider) {
                        locationProvider?.stop()
                    } else {
                        locationClient?.stopLocation()
                    }
                    startContinuousLocationWithSmartInterval(60_000L)
                }
            }
            else {
                // BUG-006 FIX: 连续定位模式：已有定时调度，不额外重试
                Log.d(TAG, "连续定位模式下定位失败，等待下次定时调度")
                // 【Bug修复】GPS 失败时重置临时检查标志，避免状态机卡住
                // 如果此次失败属于临时检查，flags 必须重置让睡眠循环恢复
                if (isTemporaryCheck) {
                    isTemporaryCheck = false
                    temporaryCheckCompleted = false
                    Log.d(TAG, "省电: 临时检查GPS失败，重置标志，继续停止+调度Alarm")
                    isLocationClientStopped = true
                    lastResumeCheckTime = System.currentTimeMillis()
                    stopLocation()
                    scheduleNextWakeup()
                }
            }
            return
        }

        val isValidCoordinate = location.latitude != 0.0 && location.longitude != 0.0
        if (!isValidCoordinate) {
            Log.w(TAG, "坐标无效 (0,0)，跳过此次定位")
            if (serviceState.isFirstLocation && firstLocationRetryCount < MAX_FIRST_LOCATION_RETRIES) {
                firstLocationRetryCount++
                if (isUsingGoogleProvider) {
                    locationProvider?.start()
                } else {
                    locationClient?.startLocation()
                }
            }
            return
        }

        Log.d(TAG, "定位成功: 精度=${location.accuracy}m, 速度=${location.speed}m/s, " +
                "来源=${if (location.locationType == AMAP_TYPE_GPS) "GPS" else if (location.locationType == AMAP_TYPE_NETWORK) "前次" else "网络"}")

        val app = application as? TrackMasterApplication
        // 【Bug修复】传递加速度数据，使 SmartScheduler 静止判定使用加速度数据
        // 避免仅依赖 GPS 速度（GPS 噪声导致 0.5m/s 阈值频繁触发，阻止降频）
        val shouldProcess = app?.checkGeoFences(toLocation(location), accelerometerData) ?: false

        // 【Bug修复】只有 shouldProcess=true 时才更新 serviceState 和围栏状态
        // shouldProcess=false 意味着 SmartScheduler 认为这是GPS噪声或无需处理的定位
        // 如果每次回调都更新 serviceState，前台UI会每5秒刷新一次，失去throttle意义
        if (shouldProcess) {
            // 【Bug修复】使用 currentMatchedFences 而非 engine.currentMatchedFenceIds
            // currentMatchedFences 在 checkGeoFences 中已通过 checkFenceMatch(fix) 同步更新
            // engine.currentMatchedFenceIds 是上一次 onLocationUpdate 的结果，存在延迟
            val matchedFences = app?.currentMatchedFences ?: emptyList()
            val isInFence = matchedFences.isNotEmpty()

            val currentFenceName = if (isInFence) {
                matchedFences.firstOrNull()?.name
            } else null

            serviceState = serviceState.copy(
                lastLocation = toLocation(location),
                lastUpdateTime = Date(),
                locationCount = serviceState.locationCount + 1,
                isInsideFence = isInFence,
                currentFenceName = currentFenceName
            )

            Log.d(TAG, "省电检查: shouldProcess=$shouldProcess, isInFence=$isInFence, isStopped=$isLocationClientStopped")

            val shouldStopForPowerSave = isInFence.not()
            val engineInterval = app?.engine?.lastComputedIntervalMs ?: 60_000L
            updatePowerSavingState(shouldStopForPowerSave, engineInterval)
        } else {
            // Throttled回调：仍然调用省电state更新（用于计数累加），但不更新UI状态
            Log.d(TAG, "Throttled回调（不更新UI）: shouldProcess=$shouldProcess, isStopped=$isLocationClientStopped")
            // 【修复】重新从 currentMatchedFences 获取围栏状态
            val matchedFences = app?.currentMatchedFences ?: emptyList()
            val isInFence = matchedFences.isNotEmpty()
            val shouldStopForPowerSave = isInFence.not()
            // 【Bug修复】Google provider: throttled 回调时也需要更新 interval
            // 否则 GPS 一直以 5s 运行，不受 SmartScheduler 节流控制
            if (isUsingGoogleProvider) {
                val nextInterval = app?.engine?.lastComputedIntervalMs ?: 60_000L
                locationProvider?.setInterval(nextInterval)
            }
            val engineInterval = app?.engine?.lastComputedIntervalMs ?: 60_000L
            updatePowerSavingState(shouldStopForPowerSave, engineInterval)
        }

        updateNotification()

        if (serviceState.isFirstLocation) {
            firstLocationRetryCount = 0
            Log.d(TAG, "首次定位成功，处理持久化状态恢复")
            (application as? TrackMasterApplication)?.handleFirstLocationAfterRestore(toLocation(location))

            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                startContinuousLocationWithSmartInterval(60_000L)
            }, 1000)
        }
    }

    fun refreshLocation() {
        try {
            if (isUsingGoogleProvider) {
                locationProvider?.stop()
                locationProvider?.start()
            } else {
                locationClient?.stopLocation()
                locationClient?.startLocation()
            }
            Log.d(TAG, "手动刷新位置")
        } catch (e: Exception) {
            Log.e(TAG, "刷新位置失败", e)
        }
    }

    /** 将 AMapLocation 转换为统一的 Location 类型 */
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
}
