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
import java.util.Date
import java.util.concurrent.CopyOnWriteArrayList

data class LocationServiceState(
    val isRunning: Boolean = false,
    val schedulerLevel: Int = 0,
    val lastLocation: AMapLocation? = null,
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
        private const val FIRST_LOCATION_TIMEOUT = 20000L // 首次定位超时20秒，给GPS更多时间
        // 极限省电：连续 N 次不需要处理就停止定位
        private const val STOP_THRESHOLD = 3
        // 停止定位后，每 N 分钟检查一次是否需要恢复
        private const val CHECK_INTERVAL_WHEN_STOPPED = 5 // 5分钟

        // AlarmManager 相关
        const val ACTION_WAKEUP = "com.trackmaster.app.ACTION_LOCATION_WAKEUP"
        private const val ALARM_REQUEST_CODE = 1001
        // 唤醒间隔：5分钟（与 CHECK_INTERVAL_WHEN_STOPPED 一致）
        private const val WAKEUP_INTERVAL_MS = CHECK_INTERVAL_WHEN_STOPPED * 60 * 1000L
    }

    private val binder = LocalBinder()
    private var locationClient: AMapLocationClient? = null
    private var locationOption: AMapLocationClientOption? = null
    private val stateListeners = CopyOnWriteArrayList<(LocationServiceState) -> Unit>()

    // 首次定位重试计数
    private var firstLocationRetryCount = 0
    private val MAX_FIRST_LOCATION_RETRIES = 5  // 增加重试次数，给GPS更多时间

    // 省电控制
    private var consecutiveNotProcessCount = 0  // 连续不需要处理的次数
    private var isLocationClientStopped = false // 定位是否已停止
    private var lastResumeCheckTime = 0L       // 上次恢复检查的时间
    // 标记是否是临时检查恢复（临时检查后需要判断是否真正恢复）
    private var isTemporaryCheck = false

    // 用于定时检查是否需要恢复定位
    private val powerSaveHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val powerSaveCheckRunnable = object : Runnable {
        override fun run() {
            if (isLocationClientStopped) {
                val now = System.currentTimeMillis()
                if (now - lastResumeCheckTime >= CHECK_INTERVAL_WHEN_STOPPED * 60 * 1000L) {
                    Log.d(TAG, "定时检查：尝试恢复定位")
                    lastResumeCheckTime = now
                    // 暂时恢复定位获取一次位置，然后根据结果决定是否继续
                    temporaryResumeForCheck()
                }
            }
            // 继续定时检查
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
        // 启动省电定时检查
        powerSaveHandler.post(powerSaveCheckRunnable)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "定位服务销毁")
        // 清理定时器
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
            // 使用高精度模式，让GPS快速锁定
            locationMode = AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
            isOnceLocation = true
            isNeedAddress = false // 首次定位不需要地址信息，加快速度
            isWifiScan = true
            isSensorEnable = true // 保持传感器
            httpTimeOut = FIRST_LOCATION_TIMEOUT
        }
        locationClient?.setLocationOption(locationOption)
        Log.d(TAG, "配置快速首次定位模式")
    }

    private fun updateLocationOptionsForContinuous(intervalMs: Long = 5000L) {
        locationOption = AMapLocationClientOption().apply {
            // 保持高精度模式保证准确性
            locationMode = AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
            isOnceLocation = false
            isNeedAddress = false  // 省电：不解析地址
            isWifiScan = true
            isSensorEnable = true  // 保持传感器用于运动检测
            interval = intervalMs   // 由 SmartScheduler 的 nextIntervalMs 控制
            // AMap SDK: httpTimeOut 应大于 interval，建议 interval * 3
            httpTimeOut = maxOf(intervalMs * 3, 10000L)
        }
        locationClient?.setLocationOption(locationOption)
        Log.d(TAG, "配置连续定位模式，间隔: ${intervalMs}ms, httpTimeOut: ${maxOf(intervalMs * 3, 10000L)}ms")
    }

    /**
     * 启动快速首次定位，成功后切换到连续定位
     */
    private fun startLocationWithFastFirst() {
        try {
            firstLocationRetryCount = 0 // 重置重试计数
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

    /** 设置电源模式（转发给 Engine，并触发立即重新评估） */
    fun setPowerMode(mode: com.trackmaster.app.core.PowerMode) {
        val engine = (application as? TrackMasterApplication)?.engine
        engine?.setPowerMode(mode)

        // 强制重新评估定位策略
        // 如果定位客户端已停止（由于省电），立即触发一次临时检查
        if (isLocationClientStopped) {
            Log.d(TAG, "电源模式切换: 定位已停止，触发临时检查")
            isTemporaryCheck = true
            lastResumeCheckTime = System.currentTimeMillis()
            temporaryResumeForCheck()
        } else {
            // 如果定位正在运行，重置省电计数器以立即应用新模式
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
            // 先停止旧定位，确保状态干净
            locationClient?.stopLocation()

            // 使用 Handler 延迟后再启动，避免状态切换冲突
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                try {
                    // 使用省电模式，间隔由 SmartScheduler 的基础间隔决定
                    updateLocationOptionsForContinuous(baseIntervalMs)
                    locationClient?.startLocation()
                    serviceState = serviceState.copy(isFirstLocation = false)
                    Log.d(TAG, "切换到连续定位模式，基础间隔: ${baseIntervalMs}ms")
                } catch (e: Exception) {
                    Log.e(TAG, "启动连续定位失败", e)
                }
            }, 100) // 100ms 延迟确保停止完成
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

    /**
     * 极限省电：根据 SmartScheduler 的决策动态停止/恢复定位
     * 当连续多次不需要处理时，停止定位客户端以节省电量
     * 当需要恢复时，重新启动定位
     */
    private fun updatePowerSavingState(shouldProcess: Boolean) {
        val now = System.currentTimeMillis()

        // 处理临时检查后的结果
        if (isTemporaryCheck) {
            isTemporaryCheck = false
            if (shouldProcess) {
                // 需要处理，恢复正常连续定位
                Log.d(TAG, "省电: 临时检查需要处理，恢复定位")
                isLocationClientStopped = false
                consecutiveNotProcessCount = 0
                // 取消 Alarm 唤醒（恢复正常后不再需要）
                cancelWakeupAlarm()
                resumeLocationWithNormalMode()
            } else {
                // 不需要处理，继续停止
                Log.d(TAG, "省电: 临时检查不需要处理，继续停止，调度 Alarm")
                isLocationClientStopped = true
                consecutiveNotProcessCount = 0
                lastResumeCheckTime = now
                stopLocation()
                // 调度 AlarmManager 定期唤醒
                scheduleNextWakeup()
            }
            return
        }

        if (isLocationClientStopped) {
            // 定位已停止，每5分钟检查一次是否需要恢复
            if (now - lastResumeCheckTime >= CHECK_INTERVAL_WHEN_STOPPED * 60 * 1000L) {
                Log.d(TAG, "省电: 定时检查，临时恢复定位")
                lastResumeCheckTime = now
                isTemporaryCheck = true
                resumeLocationWithNormalMode()
            }
        } else {
            // 定位运行中
            if (shouldProcess) {
                // 需要处理，重置计数器
                consecutiveNotProcessCount = 0
                // 取消 Alarm 唤醒（恢复正常后不再需要）
                cancelWakeupAlarm()
                Log.d(TAG, "省电: 需要处理，重置计数")
            } else {
                // 不需要处理，增加计数器
                consecutiveNotProcessCount++
                Log.d(TAG, "省电: 不需要处理 ($consecutiveNotProcessCount/$STOP_THRESHOLD)")

                if (consecutiveNotProcessCount >= STOP_THRESHOLD) {
                    // 连续 N 次不需要处理，停止定位
                    Log.d(TAG, "省电: 连续${consecutiveNotProcessCount}次不需要处理，停止定位，调度 Alarm")
                    isLocationClientStopped = true
                    lastResumeCheckTime = now
                    stopLocation()
                    // 调度 AlarmManager 定期唤醒
                    scheduleNextWakeup()
                }
            }
        }
    }

    /**
     * 调度下一次 AlarmManager 唤醒
     * 用于在定位服务停止后，定期唤醒检查是否需要恢复
     */
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

            // 使用 ELAPSED_REALTIME_WAKEUP，系统休眠时也能唤醒
            val triggerTime = SystemClock.elapsedRealtime() + WAKEUP_INTERVAL_MS

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // 使用 setExactAndAllowWhileIdle 确保在 Doze 模式下也能唤醒
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

    /**
     * 取消待执行的 AlarmManager 唤醒
     */
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

    /**
     * 供 WakeupService 调用：执行一次唤醒定位检查
     */
    fun performWakeupLocation() {
        Log.d(TAG, "执行唤醒定位检查")
        isTemporaryCheck = true
        lastResumeCheckTime = System.currentTimeMillis()
        temporaryResumeForCheck()
    }

    /**
     * 返回定位服务是否正在运行（定位客户端是否活跃）
     */
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

    /**
     * 恢复正常连续定位模式
     */
    private fun resumeLocationWithNormalMode() {
        try {
            // 恢复正常连续定位模式
            updateLocationOptionsForContinuous(30_000L) // 30秒基础间隔
            locationClient?.startLocation()
            serviceState = serviceState.copy(isRunning = true, isFirstLocation = false)
            Log.d(TAG, "恢复正常定位模式")
        } catch (e: Exception) {
            Log.e(TAG, "恢复正常定位失败", e)
        }
    }

    /**
     * 临时恢复定位以获取一次位置，用于省电模式下的定期检查
     * 获取后将根据结果决定是否继续
     */
    private fun temporaryResumeForCheck() {
        try {
            isTemporaryCheck = true
            // 使用一次性定位，省电
            locationOption = AMapLocationClientOption().apply {
                locationMode = AMapLocationClientOption.AMapLocationMode.Battery_Saving
                isOnceLocation = true
                isNeedAddress = false
                isWifiScan = true
                isSensorEnable = true
                httpTimeOut = 5000L // 5秒超时
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
            // 首次定位失败处理
            if (serviceState.isFirstLocation) {
                if (firstLocationRetryCount < MAX_FIRST_LOCATION_RETRIES) {
                    firstLocationRetryCount++
                    Log.d(TAG, "首次定位失败，重试第${firstLocationRetryCount}次")
                    // 重新启动定位，使用同样的配置重试
                    locationClient?.startLocation()
                } else {
                    // GPS失败次数过多，切换到连续定位模式
                    // 使用更长的间隔和电池节省模式，给 GPS 更多时间
                    Log.d(TAG, "GPS定位失败次数过多，切换到连续定位模式")
                    serviceState = serviceState.copy(isFirstLocation = false)
                    // 切换到连续定位前先停止再重启，确保状态正确
                    locationClient?.stopLocation()
                    startContinuousLocationWithSmartInterval(60_000L) // 使用60秒间隔省电
                }
            }
            return
        }

        // 检查坐标有效性（0,0 是无效位置）
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

        // 更新省电统计 - 记录每个定位请求，并获取处理决策
        val app = application as? TrackMasterApplication
        val shouldProcess = app?.checkGeoFences(location) ?: false

        // 同步检查是否在围栏内（用于省电决策和UI显示）
        val fix = com.trackmaster.app.core.LocationFix(
            lat = location.latitude,
            lng = location.longitude,
            accuracy = location.accuracy,
            timestamp = location.time
        )
        val isInFence = app?.engine?.isLocationInAnyFence(fix) == true

        // 获取当前命中的围栏名称
        val currentFenceName = if (isInFence) {
            val matchedIds = app?.engine?.currentMatchedFenceIds ?: emptyList()
            val firstMatchId = matchedIds.firstOrNull()
            if (firstMatchId != null) {
                app?.geoFences?.find { it.id == firstMatchId }?.name
            } else null
        } else null

        // 更新服务状态（包含围栏信息）
        serviceState = serviceState.copy(
            lastLocation = location,
            lastUpdateTime = Date(),
            locationCount = serviceState.locationCount + 1,
            isInsideFence = isInFence,
            currentFenceName = currentFenceName
        )

        Log.d(TAG, "省电检查: shouldProcess=$shouldProcess, isInFence=$isInFence, isStopped=$isLocationClientStopped")

        // 极限省电逻辑：根据 shouldProcess 和围栏状态决定是否停止/恢复定位
        // 关键：只有在围栏外且不需要处理时才停止定位
        val shouldStopForPowerSave = shouldProcess.not() && isInFence.not()
        updatePowerSavingState(shouldStopForPowerSave)

        // 更新通知显示最新位置信息
        updateNotification()

        // 如果是首次定位成功，处理持久化状态恢复
        if (serviceState.isFirstLocation) {
            firstLocationRetryCount = 0 // 重置重试计数
            Log.d(TAG, "首次定位成功，处理持久化状态恢复")
            (application as? TrackMasterApplication)?.handleFirstLocationAfterRestore(location)

            // 切换到连续定位模式，但使用省电设置
            // 间隔设为 30 秒作为基础值，SmartScheduler 会评估是否需要提频
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                startContinuousLocationWithSmartInterval(30_000L)
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
