package com.trackmaster.app.throttle

import com.trackmaster.app.core.AccelerometerData
import com.trackmaster.app.core.ActivityResult
import com.trackmaster.app.core.ActivityType
import com.trackmaster.app.core.BBox
import com.trackmaster.app.core.GeoMath
import com.trackmaster.app.core.GeoPoint
import com.trackmaster.app.core.Geofence
import com.trackmaster.app.core.LocationFix
import com.trackmaster.app.core.PowerMode
import com.trackmaster.app.core.buildFenceTree
import kotlin.math.cos
import kotlin.math.PI
import kotlin.math.sqrt

// ============================================================
// 配置
// ============================================================

data class ThrottleConfig(
    /** 漂移过滤阈值（米），accuracy > 此值丢弃 */
    val driftThresholdM: Double = 50.0,
    /** 静止判定：距离阈值倍率（精度半径 × 此值） */
    val stationaryAccuracyMultiplier: Double = 1.2,
    /** 静止判定：最小距离阈值（米） */
    val stationaryMinDistanceM: Double = 30.0,
    /** 静止判定：加速度方差阈值 */
    val stationaryAccelVariance: Double = 0.15,
    /** 阶梯降频各阶段（静止分钟 → 间隔ms） */
    val throttleSteps: List<ThrottleStep> = DEFAULT_THROTTLE_STEPS,
    /** 立即重置距离阈值（米） */
    val resetDistanceM: Double = 50.0,
    /** 边界 Buffer 区宽度（米），省电模式下临时提频 */
    val boundaryBufferM: Double = 200.0,
    /** SLC 兜底位移阈值（米） */
    val slcDriftM: Double = 300.0,
    /** 基础定位间隔（ms） */
    val baseIntervalMs: Long = 5_000L,
)

data class ThrottleStep(
    /** 静止分钟后生效 */
    val afterMin: Int,
    /** 对应定位间隔（ms） */
    val intervalMs: Long,
)

val DEFAULT_THROTTLE_STEPS = listOf(
    ThrottleStep(afterMin = 0, intervalMs = 5_000L),     // 0-10min: 原频率
    ThrottleStep(afterMin = 10, intervalMs = 120_000L),   // 10-30min: 2min/次
    ThrottleStep(afterMin = 30, intervalMs = 240_000L),   // 30-60min: 4min/次
    ThrottleStep(afterMin = 60, intervalMs = 480_000L),   // ≥60min: 8min/次
)

// ============================================================
// 输出
// ============================================================

data class ScheduleDecision(
    /** 是否应处理此次定位 */
    val shouldProcess: Boolean,
    /** 当前降频等级 0-3 */
    val level: Int,
    /** 下次应请求定位的间隔（ms） */
    val nextIntervalMs: Long,
    /** 是否处于边界 Buffer 区（省电模式提频） */
    val inBoundaryBuffer: Boolean,
)

// ============================================================
// SmartScheduler
// ============================================================

/**
 * 智能定位降频调度器
 *
 * 使用 timestamp（事件时间）而非 System.currentTimeMillis() 计算，
 * 避免息屏后 OS 合并计时导致逻辑错乱。
 *
 * 多源静止判定：距离 + 加速度方差综合判断
 * 阶梯降频：静止时间越长，定位间隔越大
 * 立即重置：位移/屏幕/活动识别触发恢复
 * 三模式兼容：performance / balanced / powersave
 */
class SmartScheduler(
    fences: List<Geofence>,
    config: ThrottleConfig = ThrottleConfig(),
) {
    private val config = config

    private val fenceMap: Map<String, Geofence>
    private val leavesByDepth: List<Geofence>
    private val bboxCache = mutableMapOf<String, BBox>()

    // ---- 状态 ----
    private var mode: PowerMode = PowerMode.BALANCED
    private var level: Int = 0

    /** 上次处理的定位时间戳（事件时间） */
    private var lastProcessedTs = 0L
    /** 首次进入静止状态的时间戳 */
    private var stationarySinceTs = 0L
    /** 上一次定位坐标 */
    private var lastPoint: GeoPoint? = null
    /** 累计静止时间（ms） */
    private var accumulatedStationaryMs = 0L
    /** 是否注册了 SLC 兜底 */
    private var slcRegistered = false

    // ---- 加速度窗口 ----
    private val accelBuffer = ArrayDeque<AccelerometerData>()
    private val ACCEL_WINDOW = 10

    init {
        val tree = buildFenceTree(fences)
        fenceMap = tree.fenceMap
        leavesByDepth = tree.leavesByDepth

        for (f in leavesByDepth) {
            f.bbox?.let { bboxCache[f.id] = it }
        }
    }

    // ============================================================
    // 公开 API
    // ============================================================

    /** 设置电源模式 */
    fun setMode(mode: PowerMode) {
        this.mode = mode
        if (mode == PowerMode.PERFORMANCE) {
            resetThrottle()
        }
    }

    fun getMode(): PowerMode = mode

    /**
     * 核心评估入口：每收到一个 LocationFix 调用此方法
     */
    fun evaluate(fix: LocationFix, accel: AccelerometerData? = null): ScheduleDecision {
        val ts = fix.timestamp

        // ---- 性能模式：不降频 ----
        if (mode == PowerMode.PERFORMANCE) {
            lastProcessedTs = ts
            lastPoint = GeoPoint(fix.lat, fix.lng)
            return ScheduleDecision(
                shouldProcess = true,
                level = 0,
                nextIntervalMs = config.baseIntervalMs,
                inBoundaryBuffer = false,
            )
        }

        // ---- 漂移过滤 ----
        if (fix.accuracy > config.driftThresholdM) {
            return decision(false)
        }

        val currentPoint = GeoPoint(fix.lat, fix.lng)

        // ---- 降频检查：是否到了该处理的时间 ----
        val interval = getIntervalForLevel()
        if (lastProcessedTs > 0 && ts - lastProcessedTs < interval) {
            return decision(false)
        }

        // ---- 多源静止判定 ----
        val distance = lastPoint?.let { GeoMath.haversine(it, currentPoint) } ?: Double.MAX_VALUE
        val isStationary = checkStationary(distance, fix.accuracy, accel)

        // ---- 边界 Buffer 检测（省电模式） ----
        val inBuffer = mode == PowerMode.POWERSAVE && checkBoundaryBuffer(currentPoint)

        // ---- 立即重置条件检查 ----
        val shouldReset = distance > config.resetDistanceM

        if (shouldReset) {
            resetThrottle()
        } else if (isStationary && !inBuffer) {
            if (stationarySinceTs == 0L) {
                stationarySinceTs = ts
            }
            accumulatedStationaryMs = ts - stationarySinceTs
            updateThrottleLevel()
        } else if (!isStationary) {
            stationarySinceTs = 0L
            accumulatedStationaryMs = 0L
            level = 0
        }

        // 省电模式下边界 Buffer 区临时提频
        if (inBuffer && level > 0) {
            level = 0
        }

        lastProcessedTs = ts
        lastPoint = currentPoint

        return decision(true, inBuffer)
    }

    /** 屏幕状态变更回调，屏幕点亮时立即重置降频 */
    fun onScreenStateChanged(isOn: Boolean) {
        if (isOn) resetThrottle()
    }

    /** 活动识别回调，WALKING/DRIVING/CYCLING 时立即重置 */
    fun onActivityDetected(activity: ActivityResult) {
        if (activity.type == ActivityType.WALKING ||
            activity.type == ActivityType.DRIVING ||
            activity.type == ActivityType.CYCLING) {
            resetThrottle()
        }
    }

    /** SLC 系统回调，位移 > slcDriftM 时唤醒 */
    fun onSignificantLocationChange(distanceM: Double) {
        if (distanceM > config.slcDriftM) {
            resetThrottle()
        }
    }

    /** 获取当前降频等级 */
    fun getLevel(): Int = level

    /** 是否应注册 SLC 兜底 */
    fun shouldRegisterSLC(): Boolean = level >= 2 && !slcRegistered

    /** 标记 SLC 已注册 */
    fun markSLCRegistered() { slcRegistered = true }

    /** 是否应注销 SLC */
    fun shouldUnregisterSLC(): Boolean = level == 0 && slcRegistered

    /** 标记 SLC 已注销 */
    fun markSLCUnregistered() { slcRegistered = false }

    /** 清理资源 */
    fun dispose() {
        accelBuffer.clear()
        lastPoint = null
        stationarySinceTs = 0L
        accumulatedStationaryMs = 0L
        level = 0
    }

    // ============================================================
    // 内部方法
    // ============================================================

    /**
     * 多源静止判定
     * isStationary = (距离 < max(精度半径×1.2, 30)) && (加速度方差 < 0.15)
     */
    private fun checkStationary(distanceM: Double, accuracyM: Float, accel: AccelerometerData?): Boolean {
        val distThreshold = maxOf(
            accuracyM * config.stationaryAccuracyMultiplier,
            config.stationaryMinDistanceM,
        )
        val distOk = distanceM < distThreshold

        if (accel == null) return distOk

        accelBuffer.addLast(accel)
        if (accelBuffer.size > ACCEL_WINDOW) accelBuffer.removeFirst()

        val variance = computeAccelVariance()
        return distOk && variance < config.stationaryAccelVariance
    }

    /** 计算加速度方差（滑动窗口） */
    private fun computeAccelVariance(): Double {
        if (accelBuffer.size < 3) return 0.0
        val n = accelBuffer.size
        val magnitudes = accelBuffer.map { a ->
            sqrt((a.x * a.x + a.y * a.y + a.z * a.z).toDouble())
        }
        val mean = magnitudes.sum() / n
        return magnitudes.sumOf { (it - mean) * (it - mean) } / n
    }

    /** 更新降频阶梯等级 */
    private fun updateThrottleLevel() {
        val steps = config.throttleSteps
        var newLevel = 0
        for (i in steps.indices.reversed()) {
            if (accumulatedStationaryMs >= steps[i].afterMin * 60_000L) {
                newLevel = i
                break
            }
        }
        level = newLevel
    }

    /** 获取当前等级对应的定位间隔 */
    private fun getIntervalForLevel(): Long {
        val steps = config.throttleSteps
        return steps[level.coerceAtMost(steps.size - 1)].intervalMs
    }

    /** 检查点是否在任何叶子围栏的边界 Buffer 区内 */
    private fun checkBoundaryBuffer(point: GeoPoint): Boolean {
        for (leaf in leavesByDepth) {
            val bbox = bboxCache[leaf.id] ?: continue

            // 围栏内不算 Buffer
            if (GeoMath.pointInPolygon(point.lat, point.lng, leaf.polygon)) continue

            // 检查是否在 Buffer 区（bbox 扩展 bufferM）
            val dLat = config.boundaryBufferM / 111_320.0
            val dLng = config.boundaryBufferM / (111_320.0 * cos(point.lat * PI / 180.0))
            val expanded = BBox(
                bbox.minLng - dLng, bbox.minLat - dLat,
                bbox.maxLng + dLng, bbox.maxLat + dLat,
            )

            if (GeoMath.pointInBBox(point.lat, point.lng, expanded)) return true
        }
        return false
    }

    /** 重置降频状态 */
    private fun resetThrottle() {
        level = 0
        stationarySinceTs = 0L
        accumulatedStationaryMs = 0L
    }

    /** 构造调度决策 */
    private fun decision(shouldProcess: Boolean, inBuffer: Boolean = false): ScheduleDecision =
        ScheduleDecision(
            shouldProcess = shouldProcess,
            level = level,
            nextIntervalMs = getIntervalForLevel(),
            inBoundaryBuffer = inBuffer,
        )
}
