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

class WakeupService : Service() {
    companion object {
        private const val TAG = "WakeupService"
        private const val NOTIFICATION_ID = 1002
        private const val CHANNEL_ID = "wakeup_channel"
        const val ACTION_WAKEUP = "com.trackmaster.app.ACTION_LOCATION_WAKEUP"
        private const val WAKELOCK_TIMEOUT = 30000L // 30秒唤醒锁

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

        performWakeupCheck()

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

        if (locationService.isRunning()) {
            Log.d(TAG, "定位服务正在运行，重新调度 Alarm")
            locationService.scheduleNextWakeup()
            return
        }

        Log.d(TAG, "定位服务已停止，尝试恢复定位")
        locationService.performWakeupLocation()
        locationService.scheduleNextWakeup()
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
