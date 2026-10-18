package com.trackmaster.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import com.trackmaster.app.utils.AppLog

/**
 * 开机自启接收器
 *
 * 手机重启（或国内 ROM 的快速开机）后，AlarmManager 中已注册的定时唤醒会全部丢失，
 * 主服务也不会自动恢复。这里监听系统开机广播，在用户此前开启过追踪时重新拉起定位服务。
 *
 * 使用 goAsync() + WakeLock 保证在后台被冷启动时也有足够时间完成 startForegroundService()，
 * 与 WakeupReceiver 保持一致的写法。
 */
class BootCompletedReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "BootCompletedReceiver"
        private const val WAKE_LOCK_TIMEOUT_MS = 30_000L
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON"
        ) {
            return
        }

        AppLog.d(TAG, "收到开机广播: $action，准备恢复定位服务")

        val app = context.applicationContext as? TrackMasterApplication
        if (app == null) {
            AppLog.e(TAG, "无法获取 TrackMasterApplication，跳过开机恢复")
            return
        }

        // 用户主动停止过追踪时，不要因为开机就偷偷把服务拉起来
        if (!isTrackingEnabledByUser(app)) {
            AppLog.d(TAG, "用户未开启追踪，跳过开机恢复")
            return
        }

        val pendingResult = goAsync()
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TrackMaster:BootCompleted")
        wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS)

        try {
            val serviceIntent = Intent(context, GeoFenceLocationService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            AppLog.d(TAG, "开机恢复：已启动定位服务")
        } catch (e: Exception) {
            AppLog.e(TAG, "开机恢复：启动定位服务失败", e)
        } finally {
            // Release resources after service has had time to start
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                try { wakeLock.release() } catch (_: Exception) {}
                pendingResult.finish()
            }, 10_000L)
        }
    }

    /**
     * 判断用户是否开启过追踪
     *
     * 与 WakeupService 采用同一套判断标准：
     * 1) appPreferences.isOnboardingCompleted：完成过引导，说明用户已走到"开始使用"这一步；
     * 2) persistentStateManager.getLastAppCloseTime()：应用正常退出时写过时间戳，证明用户实际使用过。
     *
     * 二者同时满足才认为用户开启过追踪，避免全新安装未使用的 App 被开机广播拉起。
     */
    private fun isTrackingEnabledByUser(app: TrackMasterApplication): Boolean {
        val onboardingCompleted = app.appPreferences.isOnboardingCompleted
        val hasUsedApp = app.persistentStateManager.getLastAppCloseTime() != null
        AppLog.d(TAG, "追踪开关判断: onboardingCompleted=$onboardingCompleted, hasUsedApp=$hasUsedApp")
        return onboardingCompleted && hasUsedApp
    }
}
