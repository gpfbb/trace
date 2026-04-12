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
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * 被 AlarmManager 唤醒后执行的轻量服务
 * 负责：
 * 1. 获取一次位置
 * 2. 检查是否需要恢复主定位服务
 * 3. 调度下次 Alarm
 */
class WakeupService : Service() {
    companion object {
        private const val TAG = "WakeupService"
        private const val NOTIFICATION_ID = 1002
        private const val CHANNEL_ID = "wakeup_channel"
        private const val CHANNEL_NAME = "唤醒检查服务"
        const val ACTION_WAKEUP = "com.trackmaster.app.ACTION_LOCATION_WAKEUP"
        private const val WAKELOCK_TIMEOUT = 30000L // 30秒唤醒锁

        // 唤醒后定位间隔（毫秒）
        private const val CHECK_INTERVAL_MS = 5 * 60 * 1000L // 5分钟
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "WakeupService 创建")
        acquireWakeLock()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "WakeupService 启动，执行唤醒检查")

        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // 在主线程执行检查（轻量操作）
        performWakeupCheck()

        // 完成任务后停止，最多运行30秒
        android.os.Handler(mainLooper).postDelayed({
            Log.d(TAG, "WakeupService 检查完成，停止")
            releaseWakeLock()
            stopSelf()
        }, 5000)

        return START_NOT_STICKY
    }

    private fun performWakeupCheck() {
        val app = application as? TrackMasterApplication
        if (app == null) {
            Log.e(TAG, "无法获取 TrackMasterApplication")
            return
        }

        val locationService = app.getLocationService()
        if (locationService == null) {
            Log.e(TAG, "无法获取 LocationService")
            return
        }

        // 检查定位服务是否还在运行
        if (locationService.isRunning()) {
            Log.d(TAG, "定位服务正在运行，重新调度 Alarm")
            locationService.scheduleNextWakeup()
            return
        }

        // 定位服务已停止，尝试恢复
        Log.d(TAG, "定位服务已停止，尝试恢复定位")

        // 通知主服务执行一次定位检查
        locationService.performWakeupLocation()

        // 重新调度下次 Alarm（保持5分钟周期）
        locationService.scheduleNextWakeup()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "定期唤醒检查定位服务"
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
            .setContentTitle("定位检查中")
            .setContentText("定期检查位置状态...")
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
        Log.d(TAG, "获取 WakeLock")
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
            Log.d(TAG, "释放 WakeLock")
        }
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
