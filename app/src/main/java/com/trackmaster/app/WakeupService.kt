package com.trackmaster.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.trackmaster.app.utils.AppLog
import androidx.core.app.NotificationCompat

class WakeupService : Service() {
    companion object {
        private const val TAG = "WakeupService"
        private const val NOTIFICATION_ID = 1002
        private const val CHANNEL_ID = "wakeup_channel"
        const val ACTION_WAKEUP = "com.trackmaster.app.ACTION_LOCATION_WAKEUP"
        private const val WAKELOCK_TIMEOUT = 30000L // 30秒唤醒锁

        private const val CHECK_INTERVAL_MS = 5 * 60 * 1000L // 5分钟

        // 等待"已绑定进程"完成绑定的重试参数（进程刚恢复时的竞态窗口）
        private const val BIND_RETRY_MAX = 3
        private const val BIND_RETRY_DELAY_MS = 500L
        // 重新拉起主服务后的重试参数
        private const val RESTART_RETRY_MAX = 3
        private const val RESTART_RETRY_DELAY_MS = 1000L
        // 拉起主服务后，等待其完成 onCreate/onStartCommand/onBind 的时间
        private const val RESTART_WAIT_MS = 1500L
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var bindRetryCount = 0 // 等待已绑定进程完成绑定的重试计数
    private var restartAttempted = false // 是否已尝试重新拉起主服务
    private var postRestartRetryCount = 0 // 拉起主服务后继续等待绑定的重试计数
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        AppLog.d(TAG, "WakeupService 创建")
        acquireWakeLock()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppLog.d(TAG, "WakeupService 启动，执行唤醒检查")

        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        performWakeupCheck()

        return START_NOT_STICKY
    }

    private fun performWakeupCheck() {
        val app = application as? TrackMasterApplication
        if (app == null) {
            AppLog.e(TAG, "无法获取 TrackMasterApplication")
            stopSelfSafely()
            return
        }

        val locationService = app.getLocationService()
        if (locationService == null) {
            handleServiceNotBound(app)
            return
        }

        if (locationService.isRunning()) {
            AppLog.d(TAG, "定位服务正在运行，重新调度 Alarm")
            locationService.scheduleNextWakeup()
        } else {
            AppLog.d(TAG, "定位服务已停止，尝试恢复定位")
            locationService.performWakeupLocation()
            locationService.scheduleNextWakeup()
        }
        stopSelfSafely()
    }

    /**
     * 定位服务引用为 null 的处理
     *
     * 只有两种情况会让这里拿不到引用：
     * 1) 主服务进程刚被系统拉起，onBind() 还没跑完（真正的竞态窗口）→ 短间隔重试即可
     * 2) 进程已经被系统杀掉，Application 里的 locationService 字段恒为 null → 重试多少次都没用
     *
     * 对第 2 种情况，以前是重试 3 次后直接放弃，导致 Alarm 每 8 分钟准时触发却永远拉不回主服务。
     * 现在重试耗尽时改为重新拉起主服务（优先走 app.startLocationService()，内部即 startForegroundService + bindService），
     * 等它完成 onCreate/onBind 后再重试一次检查。
     */
    private fun handleServiceNotBound(app: TrackMasterApplication) {
        if (bindRetryCount < BIND_RETRY_MAX) {
            // 情况 1：等待已绑定进程完成 onBind()
            bindRetryCount++
            AppLog.w(TAG, "LocationService 尚未绑定，${bindRetryCount}/$BIND_RETRY_MAX 次重试...")
            handler.postDelayed({ performWakeupCheck() }, BIND_RETRY_DELAY_MS)
            return
        }

        if (!restartAttempted) {
            // 情况 2：重试耗尽，进程大概率已被杀，尝试重新拉起主服务
            restartAttempted = true

            if (!isTrackingEnabledByUser(app)) {
                // 用户主动停止过追踪，不要被 Alarm 偷偷拉起来
                AppLog.d(TAG, "用户未开启追踪，跳过重新拉起定位服务")
                stopSelfSafely()
                return
            }

            AppLog.e(TAG, "重试耗尽仍无法获取 LocationService，判定主服务进程已被杀，重新拉起")
            val restarted = try {
                // 复用 Application 的启动逻辑：内部在 Android O+ 上走 startForegroundService()
                // 同时会 bindService，这样拉起后 app.getLocationService() 才能尽快拿到引用，
                // 本次唤醒检查才有机会继续执行（scheduleNextWakeup 等）
                app.startLocationService()
                true
            } catch (e: Exception) {
                AppLog.e(TAG, "通过 startLocationService 拉起失败，回退为直接 startForegroundService", e)
                try {
                    startForegroundService(Intent(this, GeoFenceLocationService::class.java))
                    true
                } catch (e2: Exception) {
                    AppLog.e(TAG, "重新拉起定位服务失败", e2)
                    false
                }
            }

            if (!restarted) {
                stopSelfSafely()
                return
            }
            AppLog.d(TAG, "已重新拉起定位服务，${RESTART_WAIT_MS}ms 后重试绑定")

            // 给主服务一点时间完成 onCreate/onStartCommand/onBind，避免拉起瞬间又拿不到引用
            handler.postDelayed({
                if (app.getLocationService() != null) {
                    performWakeupCheck()
                } else {
                    postRestartRetry()
                }
            }, RESTART_WAIT_MS)
            return
        }

        postRestartRetry()
    }

    /** 拉起主服务后仍未拿到引用时的兜底重试 */
    private fun postRestartRetry() {
        if (postRestartRetryCount < RESTART_RETRY_MAX) {
            postRestartRetryCount++
            AppLog.w(TAG, "主服务已拉起但尚未绑定，${postRestartRetryCount}/$RESTART_RETRY_MAX 次重试...")
            handler.postDelayed({ performWakeupCheck() }, RESTART_RETRY_DELAY_MS)
        } else {
            AppLog.e(TAG, "重新拉起定位服务后仍无法绑定，放弃本次唤醒")
            stopSelfSafely()
        }
    }

    /**
     * 判断用户是否开启过追踪
     *
     * 项目里没有显式的"追踪开关"（服务只在 MainActivity 拿到定位权限后由 app.startLocationService() 拉起，
     * 也没有 UI 提供关闭入口），因此退而求其次用两个现有标志做组合判断：
     * 1) appPreferences.isOnboardingCompleted：完成过引导，说明用户已经走到"开始使用"这一步；
     * 2) persistentStateManager.getLastAppCloseTime()：应用正常退出时写过时间戳，证明用户实际使用过。
     *
     * 二者同时满足才认为用户开启过追踪。默认值都是 false/0，全新安装未使用的 App 不会被 Alarm 拉起。
     */
    private fun isTrackingEnabledByUser(app: TrackMasterApplication): Boolean {
        val onboardingCompleted = app.appPreferences.isOnboardingCompleted
        val hasUsedApp = app.persistentStateManager.getLastAppCloseTime() != null
        AppLog.d(TAG, "追踪开关判断: onboardingCompleted=$onboardingCompleted, hasUsedApp=$hasUsedApp")
        return onboardingCompleted && hasUsedApp
    }

    private fun stopSelfSafely() {
        handler.postDelayed({
            releaseWakeLock()
            stopSelf()
        }, 3000)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_location_checking),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_location_checking_desc)
                setShowBadge(false)
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): android.app.Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_location_checking))
            .setContentText(getString(R.string.notification_location_checking_desc))
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "TrackMaster:WakeupService"
        ).apply {
            acquire(WAKELOCK_TIMEOUT)
        }
        AppLog.d(TAG, "获取 WakeLock")
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
            AppLog.d(TAG, "释放 WakeLock")
        }
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
