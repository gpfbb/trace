package com.trackmaster.app.locationservice

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.SystemClock
import com.trackmaster.app.WakeupReceiver
import com.trackmaster.app.utils.AppLog

/**
 * 省电控制器 — 省电状态机、AlarmManager 调度、状态持久化
 */
class PowerSaveController(
    private val context: Context,
) {
    companion object {
        private const val TAG = "PowerSaveController"
        private const val STOP_THRESHOLD = 5
        private const val THROTTLE_THRESHOLD = 2
        private const val CHECK_INTERVAL_WHEN_STOPPED = 8

        const val ACTION_WAKEUP = "com.trackmaster.app.ACTION_LOCATION_WAKEUP"
        private const val ALARM_REQUEST_CODE = 1001
        private const val WAKEUP_INTERVAL_MS = CHECK_INTERVAL_WHEN_STOPPED * 60 * 1000L

        private const val PREFS_SERVICE_STATE = "GeoFenceLocationServiceState"
        private const val KEY_IS_LOCATION_CLIENT_STOPPED = "is_location_client_stopped"
        private const val KEY_LAST_RESUME_CHECK_TIME = "last_resume_check_time"
        private const val KEY_CONSECUTIVE_NOT_PROCESS_COUNT = "consecutive_not_process_count"
    }

    /** 定位提供商控制接口 */
    interface LocationProviderControl {
        fun stop()
        fun resumeWithNormalMode()
        fun temporaryResumeForCheck()
        fun setThrottledInterval(intervalMs: Long, forceGps: Boolean)
    }

    var providerControl: LocationProviderControl? = null

    // ---- 省电状态 ----
    @Volatile var isLocationClientStopped = false
    @Volatile var lastResumeCheckTime = 0L
    @Volatile var consecutiveNotProcessCount = 0
    @Volatile var isTemporaryCheck = false
    @Volatile var temporaryCheckCompleted = false

    private val serviceStatePrefs: SharedPreferences =
        context.getSharedPreferences(PREFS_SERVICE_STATE, Context.MODE_PRIVATE)

    // ---- 定时检查 Handler ----
    private val powerSaveHandler = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile private var periodicCheckStopped = false
    private val powerSaveCheckRunnable = object : Runnable {
        override fun run() {
            if (periodicCheckStopped) return
            if (isLocationClientStopped) {
                val now = System.currentTimeMillis()
                if (now - lastResumeCheckTime >= CHECK_INTERVAL_WHEN_STOPPED * 60 * 1000L) {
                    AppLog.d(TAG, "定时检查：尝试恢复定位")
                    lastResumeCheckTime = now
                    isTemporaryCheck = true
                    temporaryCheckCompleted = false
                    saveServiceState()
                    providerControl?.temporaryResumeForCheck()
                }
            }
            if (!periodicCheckStopped) {
                powerSaveHandler.postDelayed(this, CHECK_INTERVAL_WHEN_STOPPED * 60 * 1000L)
            }
        }
    }

    fun startPeriodicCheck() {
        periodicCheckStopped = false
        powerSaveHandler.post(powerSaveCheckRunnable)
    }

    fun stopPeriodicCheck() {
        periodicCheckStopped = true
        powerSaveHandler.removeCallbacks(powerSaveCheckRunnable)
    }

    fun saveServiceState() {
        serviceStatePrefs.edit()
            .putBoolean(KEY_IS_LOCATION_CLIENT_STOPPED, isLocationClientStopped)
            .putLong(KEY_LAST_RESUME_CHECK_TIME, lastResumeCheckTime)
            .putInt(KEY_CONSECUTIVE_NOT_PROCESS_COUNT, consecutiveNotProcessCount)
            .commit()
    }

    fun restoreServiceState() {
        isLocationClientStopped = serviceStatePrefs.getBoolean(KEY_IS_LOCATION_CLIENT_STOPPED, false)
        lastResumeCheckTime = serviceStatePrefs.getLong(KEY_LAST_RESUME_CHECK_TIME, 0L)
        consecutiveNotProcessCount = serviceStatePrefs.getInt(KEY_CONSECUTIVE_NOT_PROCESS_COUNT, 0)
        AppLog.d(TAG, "恢复服务状态: isStopped=$isLocationClientStopped, lastResumeCheck=$lastResumeCheckTime, consecutiveNotProcess=$consecutiveNotProcessCount")
    }

    fun isRunning(): Boolean = !isLocationClientStopped

    fun performWakeupLocation() {
        AppLog.d(TAG, "执行唤醒定位检查")
        isTemporaryCheck = true
        lastResumeCheckTime = System.currentTimeMillis()
        providerControl?.temporaryResumeForCheck()
    }

    /**
     * 省电状态机主入口
     * @param shouldProcess 引擎是否处理了此次定位
     * @param engineIntervalMs 引擎计算的下次定位间隔
     * @param engineIntervalMs 引擎计算的下次定位间隔（保留以便调用方统一传参，
     *   间隔的应用已移至 GeoFenceLocationService）
     */
    fun updatePowerSavingState(
        shouldProcess: Boolean,
        engineIntervalMs: Long = 60_000L,
    ) {
        val now = System.currentTimeMillis()

        if (isTemporaryCheck) {
            handleTemporaryCheckResult(shouldProcess)
            return
        }

        if (isLocationClientStopped) {
            handleStoppedState(now)
        } else {
            handleRunningState(shouldProcess)
        }
    }

    private fun handleTemporaryCheckResult(shouldProcess: Boolean) {
        isTemporaryCheck = false
        temporaryCheckCompleted = true
        if (shouldProcess) {
            AppLog.d(TAG, "省电: 临时检查需要处理，恢复定位")
            isLocationClientStopped = false
            consecutiveNotProcessCount = 0
            cancelWakeupAlarm()
            providerControl?.resumeWithNormalMode()
        } else {
            AppLog.d(TAG, "省电: 临时检查不需要处理，继续停止，调度 Alarm")
            isLocationClientStopped = true
            consecutiveNotProcessCount = 0
            lastResumeCheckTime = System.currentTimeMillis()
            providerControl?.stop()
            scheduleNextWakeup()
        }
        saveServiceState()
    }

    private fun handleStoppedState(now: Long) {
        if (now - lastResumeCheckTime >= CHECK_INTERVAL_WHEN_STOPPED * 60 * 1000L) {
            AppLog.d(TAG, "省电: 定时检查，临时恢复定位")
            lastResumeCheckTime = now
            isTemporaryCheck = true
            temporaryCheckCompleted = false
            saveServiceState()
            providerControl?.temporaryResumeForCheck()
        }
    }

    // 降频分支已移除，间隔决策统一由 GeoFenceLocationService 负责；
    // 这里只根据"引擎是否处理了本次定位"决定计数与停止/唤醒。
    private fun handleRunningState(shouldProcess: Boolean) {
        if (shouldProcess) {
            consecutiveNotProcessCount = 0
            cancelWakeupAlarm()
            AppLog.d(TAG, "省电: 引擎处理了定位，重置计数器")
            saveServiceState()
        } else {
            consecutiveNotProcessCount++
            AppLog.d(TAG, "省电: 引擎未处理 ($consecutiveNotProcessCount/$STOP_THRESHOLD)")

            if (consecutiveNotProcessCount >= STOP_THRESHOLD) {
                AppLog.d(TAG, "省电: 连续${consecutiveNotProcessCount}次未处理，停止GPS，调度Alarm")
                isLocationClientStopped = true
                lastResumeCheckTime = System.currentTimeMillis()
                providerControl?.stop()
                scheduleNextWakeup()
                saveServiceState()
            }
        }
    }

    fun scheduleNextWakeup() {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, WakeupReceiver::class.java).apply {
                action = ACTION_WAKEUP
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
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
            AppLog.d(TAG, "已调度 AlarmManager 唤醒，${WAKEUP_INTERVAL_MS / 1000 / 60}分钟后")
        } catch (e: Exception) {
            AppLog.e(TAG, "调度 Alarm 失败", e)
        }
    }

    fun cancelWakeupAlarm() {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, WakeupReceiver::class.java).apply {
                action = ACTION_WAKEUP
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                ALARM_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
            AppLog.d(TAG, "已取消 AlarmManager 唤醒")
        } catch (e: Exception) {
            AppLog.e(TAG, "取消 Alarm 失败", e)
        }
    }
}
