package com.trackmaster.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import com.trackmaster.app.utils.AppLog

/**
 * AlarmManager 唤醒接收器
 * 使用 goAsync() 确保在 Doze 模式下 Service 能可靠启动
 */
class WakeupReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "WakeupReceiver"
        private const val WAKE_LOCK_TIMEOUT_MS = 30_000L
        const val ACTION_WAKEUP = "com.trackmaster.app.ACTION_LOCATION_WAKEUP"
        const val EXTRA_FENCE_ID = "fence_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_WAKEUP) return

        AppLog.d(TAG, "AlarmManager 唤醒! 启动 WakeupService")

        val pendingResult = goAsync()
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TrackMaster:WakeupReceiver")
        wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS)

        val serviceIntent = Intent(context, WakeupService::class.java).apply {
            action = ACTION_WAKEUP
        }
        context.startForegroundService(serviceIntent)

        // Release resources after service has had time to start
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            try { wakeLock.release() } catch (_: Exception) {}
            pendingResult.finish()
        }, 4_000L)
    }
}
