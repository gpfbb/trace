package com.trackmaster.app

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.util.*

/**
 * 持久化状态管理器
 *
 * 用于管理应用被清理后台后需要恢复的状态：
 * - 当前所在围栏ID
 * - 进入该围栏的时间戳
 * - 上次离开围栏的时间戳
 * - 上次应用关闭时间
 */
class PersistentStateManager(context: Context) {
    companion object {
        private const val TAG = "PersistentStateManager"
        private const val PREFS_NAME = "PersistentState"

        // 持久化键
        private const val KEY_CURRENT_FENCE_ID = "current_fence_id"
        private const val KEY_CURRENT_FENCE_NAME = "current_fence_name"
        private const val KEY_CURRENT_FENCE_ENTER_TIME = "current_fence_enter_time"
        private const val KEY_LAST_EXIT_FENCE_ID = "last_exit_fence_id"
        private const val KEY_LAST_EXIT_FENCE_NAME = "last_exit_fence_name"
        private const val KEY_LAST_EXIT_TIME = "last_exit_time"
        private const val KEY_LAST_APP_CLOSE_TIME = "last_app_close_time"
        private const val KEY_LAST_LOCATION_LAT = "last_location_lat"
        private const val KEY_LAST_LOCATION_LNG = "last_location_lng"
        private const val KEY_LAST_LOCATION_TIME = "last_location_time"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 当前所在围栏状态
     */
    data class CurrentFenceState(
        val fenceId: String,
        val fenceName: String,
        val enterTime: Date
    )

    /**
     * 上次离开围栏状态
     */
    data class LastExitState(
        val fenceId: String,
        val fenceName: String,
        val exitTime: Date
    )

    /**
     * 上次位置信息
     */
    data class LastLocationState(
        val latitude: Double,
        val longitude: Double,
        val time: Date
    )

    /**
     * 保存当前所在围栏状态
     */
    fun saveCurrentFenceState(fenceId: String, fenceName: String, enterTime: Date) {
        // 【P0修复】崩溃恢复数据必须同步写入，apply()异步可能导致crash后数据丢失
        prefs.edit()
            .putString(KEY_CURRENT_FENCE_ID, fenceId)
            .putString(KEY_CURRENT_FENCE_NAME, fenceName)
            .putLong(KEY_CURRENT_FENCE_ENTER_TIME, enterTime.time)
            .commit()
        Log.d(TAG, "保存当前围栏状态: $fenceName, 进入时间=$enterTime")
    }

    /**
     * 获取当前所在围栏状态
     */
    fun getCurrentFenceState(): CurrentFenceState? {
        val fenceId = prefs.getString(KEY_CURRENT_FENCE_ID, null) ?: return null
        val fenceName = prefs.getString(KEY_CURRENT_FENCE_NAME, null) ?: return null
        val enterTime = prefs.getLong(KEY_CURRENT_FENCE_ENTER_TIME, 0L)
        if (enterTime == 0L) return null

        return CurrentFenceState(fenceId, fenceName, Date(enterTime))
    }

    /**
     * 清除当前所在围栏状态
     */
    fun clearCurrentFenceState() {
        // 【P0修复】崩溃恢复数据必须同步写入
        prefs.edit()
            .remove(KEY_CURRENT_FENCE_ID)
            .remove(KEY_CURRENT_FENCE_NAME)
            .remove(KEY_CURRENT_FENCE_ENTER_TIME)
            .commit()
        Log.d(TAG, "清除当前围栏状态")
    }

    /**
     * 保存上次离开围栏状态
     */
    fun saveLastExitState(fenceId: String, fenceName: String, exitTime: Date) {
        // 【P0修复】崩溃恢复数据必须同步写入
        prefs.edit()
            .putString(KEY_LAST_EXIT_FENCE_ID, fenceId)
            .putString(KEY_LAST_EXIT_FENCE_NAME, fenceName)
            .putLong(KEY_LAST_EXIT_TIME, exitTime.time)
            .commit()
        Log.d(TAG, "保存上次离开围栏状态: $fenceName, 离开时间=$exitTime")
    }

    /**
     * 获取上次离开围栏状态
     */
    fun getLastExitState(): LastExitState? {
        val fenceId = prefs.getString(KEY_LAST_EXIT_FENCE_ID, null) ?: return null
        val fenceName = prefs.getString(KEY_LAST_EXIT_FENCE_NAME, null) ?: return null
        val exitTime = prefs.getLong(KEY_LAST_EXIT_TIME, 0L)
        if (exitTime == 0L) return null

        return LastExitState(fenceId, fenceName, Date(exitTime))
    }

    /**
     * 保存上次应用关闭时间
     */
    fun saveLastAppCloseTime(time: Date) {
        // 【P2修复】使用commit()同步写入，避免apply()异步导致crash后数据丢失
        val success = prefs.edit()
            .putLong(KEY_LAST_APP_CLOSE_TIME, time.time)
            .commit()
        if (!success) {
            Log.e(TAG, "saveLastAppCloseTime: commit() failed")
        }
        Log.d(TAG, "保存上次应用关闭时间: $time")
    }

    /**
     * 获取上次应用关闭时间
     */
    fun getLastAppCloseTime(): Date? {
        val time = prefs.getLong(KEY_LAST_APP_CLOSE_TIME, 0L)
        return if (time > 0L) Date(time) else null
    }

    /**
     * 保存上次位置信息
     */
    fun saveLastLocation(latitude: Double, longitude: Double, time: Date) {
        // 【P0修复】崩溃恢复数据必须同步写入
        // P7-C-BUG007：使用 doubleToRawLongBits 存储 double，避免 Float 精度丢失
        prefs.edit()
            .putLong(KEY_LAST_LOCATION_LAT, java.lang.Double.doubleToRawLongBits(latitude))
            .putLong(KEY_LAST_LOCATION_LNG, java.lang.Double.doubleToRawLongBits(longitude))
            .putLong(KEY_LAST_LOCATION_TIME, time.time)
            .commit()
        Log.d(TAG, "保存上次位置: ($latitude, $longitude), 时间=$time")
    }

    /**
     * 获取上次位置信息
     */
    fun getLastLocation(): LastLocationState? {
        // 【Bug修复】P7-C-BUG007：使用 longBitsToDouble 读取 double，避免 Float 精度丢失
        // 【兼容修复】：旧数据以 Float 存储，getLong 遇到 Float 类型会抛 ClassCastException，需兼容读取
        val latBits: Long
        val lngBits: Long
        try {
            latBits = prefs.getLong(KEY_LAST_LOCATION_LAT, java.lang.Double.doubleToRawLongBits(Double.NaN))
            lngBits = prefs.getLong(KEY_LAST_LOCATION_LNG, java.lang.Double.doubleToRawLongBits(Double.NaN))
        } catch (e: ClassCastException) {
            // 旧数据是 Float 类型，迁移读取并转为 Double 后重写
            val latFloat = prefs.getFloat(KEY_LAST_LOCATION_LAT, Float.NaN)
            val lngFloat = prefs.getFloat(KEY_LAST_LOCATION_LNG, Float.NaN)
            val lat = latFloat.toDouble()
            val lng = lngFloat.toDouble()
            val time = prefs.getLong(KEY_LAST_LOCATION_TIME, 0L)
            if (lat.isNaN() || lng.isNaN() || time == 0L) return null
            // 立即以新格式(Long)重写，避免下次再走此兼容路径
            saveLastLocation(lat, lng, Date(time))
            return LastLocationState(lat, lng, Date(time))
        }

        val time = prefs.getLong(KEY_LAST_LOCATION_TIME, 0L)
        val lat = java.lang.Double.longBitsToDouble(latBits)
        val lng = java.lang.Double.longBitsToDouble(lngBits)
        if (lat.isNaN() || lng.isNaN() || time == 0L) return null

        return LastLocationState(lat, lng, Date(time))
    }


    /**
     * 清除所有状态
     */
    fun clearAll() {
        prefs.edit().clear().apply()
        Log.d(TAG, "清除所有持久化状态")
    }
}
