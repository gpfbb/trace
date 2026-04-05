package com.trackmaster.app.architecture.common.utils

import com.trackmaster.app.architecture.domain.models.*
import android.util.Log

/**
 * 智能调度器配置
 */
class ThrottleConfig {
    var minIntervalMs: Long = 1000L
    var maxIntervalMs: Long = 60000L
    var initialIntervalMs: Long = 5000L
    var movingIntervalMs: Long = 3000L
    var stationaryIntervalMs: Long = 15000L
    var accuracyThreshold: Float = 50f
    var significantDistanceM: Double = 50.0
}

/**
 * 调度决策结果
 */
class SchedulerDecision(
    val shouldProcess: Boolean,
    val nextIntervalMs: Long
)

/**
 * 智能调度器 - 自适应定位频率
 *
 * 核心策略：
 * 1. 静止时降低频率（15秒）
 * 2. 移动时提高频率（3秒）
 * 3. 基于精度动态调整
 * 4. 基于显著位置变化触发
 */
class SmartScheduler(
    private val fences: List<Geofence>,
    private val config: ThrottleConfig
) {
    companion object {
        private const val TAG = "SmartScheduler"
    }

    private var lastFix: LocationFix? = null
    private var lastProcessTime: Long = 0
    private var currentIntervalMs: Long = config.initialIntervalMs
    private var isMoving: Boolean = false
    private var lastActivity: ActivityType = ActivityType.STILL

    /**
     * 评估是否应处理当前定位更新
     */
    fun evaluate(fix: LocationFix, accel: AccelerometerData? = null): SchedulerDecision {
        val now = System.currentTimeMillis()
        val timeSinceLast = now - lastProcessTime

        // 检查时间间隔
        if (timeSinceLast < currentIntervalMs) {
            return SchedulerDecision(false, currentIntervalMs - timeSinceLast)
        }

        // 检查精度
        if (fix.accuracy > config.accuracyThreshold) {
            return SchedulerDecision(false, config.minIntervalMs)
        }

        // 分析移动状态
        analyzeMotionState(fix, accel)

        // 调整间隔
        adjustInterval()

        // 更新状态
        lastFix = fix
        lastProcessTime = now

        return SchedulerDecision(true, currentIntervalMs)
    }

    /**
     * 分析移动状态
     */
    private fun analyzeMotionState(fix: LocationFix, accel: AccelerometerData? = null) {
        // 基于加速度数据判断移动状态
        if (accel != null) {
            val magnitude = Math.sqrt((accel.x * accel.x + accel.y * accel.y + accel.z * accel.z).toDouble())
            isMoving = magnitude > 1.5 // 简单的阈值判断
        } else if (lastFix != null) {
            // 基于位置变化判断
            val distance = GeoMath.haversine(
                lastFix!!.lat, lastFix!!.lng, fix.lat, fix.lng
            )
            isMoving = distance > 10.0 // 10米以上视为移动
        }
    }

    /**
     * 调整定位间隔
     */
    private fun adjustInterval() {
        currentIntervalMs = if (isMoving) {
            config.movingIntervalMs
        } else {
            config.stationaryIntervalMs
        }

        // 确保间隔在合理范围内
        currentIntervalMs = currentIntervalMs.coerceIn(config.minIntervalMs, config.maxIntervalMs)
    }

    /**
     * 活动识别回调
     */
    fun onActivityDetected(activity: ActivityResult) {
        lastActivity = activity.type
        isMoving = when (activity.type) {
            ActivityType.STILL -> false
            ActivityType.WALKING, ActivityType.RUNNING, ActivityType.DRIVING, ActivityType.CYCLING -> true
            else -> isMoving
        }
        adjustInterval()
    }

    /**
     * 屏幕状态变更
     */
    fun onScreenStateChanged(isOn: Boolean) {
        if (isOn) {
            // 屏幕开启时，使用较短间隔
            currentIntervalMs = config.initialIntervalMs
        } else {
            // 屏幕关闭时，使用较长间隔
            currentIntervalMs = config.stationaryIntervalMs
        }
    }

    /**
     * 显著位置变化回调
     */
    fun onSignificantLocationChange(distanceM: Double) {
        if (distanceM > config.significantDistanceM) {
            // 显著位置变化，立即处理
            lastProcessTime = 0
        }
    }

    /**
     * 设置电源模式
     */
    fun setMode(mode: PowerMode) {
        currentIntervalMs = when (mode) {
            PowerMode.PERFORMANCE -> config.minIntervalMs
            PowerMode.BALANCED -> config.initialIntervalMs
            PowerMode.POWERSAVE -> config.maxIntervalMs
        }
    }

    /**
     * 是否应该注册显著位置变化监听器
     */
    fun shouldRegisterSLC(): Boolean {
        return currentIntervalMs >= config.stationaryIntervalMs
    }

    /**
     * 是否应该注销显著位置变化监听器
     */
    fun shouldUnregisterSLC(): Boolean {
        return currentIntervalMs < config.stationaryIntervalMs
    }

    /**
     * 资源释放
     */
    fun dispose() {
        // 清理资源
        lastFix = null
        lastProcessTime = 0
    }
}