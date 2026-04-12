package com.trackmaster.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * AlarmManager 唤醒接收器
 * 当系统被 Doze/低电量模式杀死后，AlarmManager 能在设定时间唤醒进程
 */
class WakeupReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "WakeupReceiver"
        const val ACTION_WAKEUP = "com.trackmaster.app.ACTION_LOCATION_WAKEUP"
        const val EXTRA_FENCE_ID = "fence_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_WAKEUP) return

        Log.d(TAG, "AlarmManager 唤醒! 启动 WakeupService")

        val serviceIntent = Intent(context, WakeupService::class.java).apply {
            action = ACTION_WAKEUP
        }
        context.startForegroundService(serviceIntent)
    }
}
