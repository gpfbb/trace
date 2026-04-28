package com.trackmaster.app.utils

import com.trackmaster.app.core.AccelerometerData
import com.trackmaster.app.core.ActivityResult
import com.trackmaster.app.core.ActivityType
import com.trackmaster.app.core.BBox
import com.trackmaster.app.core.GeoPoint
import com.trackmaster.app.core.Geofence
import com.trackmaster.app.core.LocationFix
import com.trackmaster.app.core.PowerMode
import com.trackmaster.app.utils.geometry.haversine
import com.trackmaster.app.utils.geometry.pointInBBox
import com.trackmaster.app.utils.geometry.pointInPolygon
import com.trackmaster.app.core.buildFenceTree
import com.trackmaster.app.throttle.LocationHistoryRecord
import com.trackmaster.app.throttle.ScheduleDecision
import com.trackmaster.app.throttle.ThrottleConfig
import com.trackmaster.app.throttle.ThrottleStep
import com.trackmaster.app.throttle.BALANCED_THROTTLE_STEPS
import com.trackmaster.app.throttle.BALANCED_INSIDE_FENCE_THROTTLE_STEPS
import com.trackmaster.app.throttle.POWERSAVE_THROTTLE_STEPS
import com.trackmaster.app.throttle.POWERSAVE_INSIDE_FENCE_THROTTLE_STEPS
import com.trackmaster.app.utils.scheduler.StationaryDetector
import kotlin.math.cos
import kotlin.math.PI

// ============================================================
// SmartScheduler
// ============================================================

/**
 * 智能定位降频调度器
 *
 * 使用 timestamp（事件时间）而非 System.currentTimeMillis() 计算，
 * 避免息屏后 OS 合并计时导致逻辑错乱。
 *
 * 多源静止判定：距离 + 加速度方差综合判断（委托给 StationaryDetector）
 * 阶梯降频：静止时间越长，定位间隔越大
 * 立即重置：位移/屏幕/活动识别触发恢复
 * 三模式兼容：balanced / powersave
 */
class SmartScheduler(
    fences: List<Geofence>,
    config: ThrottleConfig = ThrottleConfig(),
) {
    var config = config
        private set

    fun updateConfig(newConfig: ThrottleConfig) {
        val bufferChanged = newConfig.boundaryBufferM != config.boundaryBufferM
        this.config = newConfig
        stationaryDetector.updateConfig(newConfig)

        if (bufferChanged) {
            // 边界缓冲区大小变化，重新预计算扩展 BBox
            precomputeExpandedBBoxes(newConfig.boundaryBufferM)
        }
    }

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
    /** 连续移动计数器：单次GPS跳点不能判断为真移动，需要连续多次才确认 */
    private var consecutiveMovementCount = 0

    // ---- 静止检测器 ----
    private val stationaryDetector = StationaryDetector(config)

    // ---- 边界 Buffer 检测缓存 ----
    private var lastBoundaryCheckPoint: GeoPoint? = null
    private var lastBoundaryCheckResult: Boolean = false
    private val expandedBBoxCache = mutableMapOf<String, BBox>()
    private var lastBufferM: Double = 0.0

    init {
        val tree = buildFenceTree(fences)
        fenceMap = tree.fenceMap
        leavesByDepth = tree.leavesByDepth

        for (f in leavesByDepth) {
            f.bbox?.let { bboxCache[f.id] = it }
        }

        // 预计算扩展 BBox
        precomputeExpandedBBoxes(config.boundaryBufferM)
    }

    /**
     * 根据当前电源模式和围栏状态获取对应的降频阶梯
     * @param isInsideFence 是否在围栏内（围栏内静止时可用更激进的降频）
     */
    private fun getCurrentThrottleSteps(isInsideFence: Boolean): List<ThrottleStep> {
        return when (mode) {
            PowerMode.BALANCED -> {
                if (isInsideFence) BALANCED_INSIDE_FENCE_THROTTLE_STEPS
                else BALANCED_THROTTLE_STEPS
            }
            PowerMode.POWERSAVE -> {
                if (isInsideFence) POWERSAVE_INSIDE_FENCE_THROTTLE_STEPS
                else POWERSAVE_THROTTLE_STEPS
            }
        }
    }

    // ============================================================
    // 公开 API
    // ============================================================

    /** 设置电源模式 */
    fun setMode(mode: PowerMode) {
        this.mode = mode
        // 切换模式时重置降频状态，根据静止状态自适应降频
        resetThrottle()
    }

    fun getMode(): PowerMode = mode

    /**
     * 核心评估入口：每收到一个 LocationFix 调用此方法
     */
    fun evaluate(fix: LocationFix, accel: AccelerometerData? = null): ScheduleDecision {
        val ts = fix.timestamp

        // ---- 漂移过滤 ----
        if (fix.accuracy > config.driftThresholdM) {
            return decision(false)
        }

        val currentPoint = GeoPoint(fix.lat, fix.lng)

        // ---- 是否在围栏内（用于围栏内激进降频） ----
        val isInsideFence = checkFenceInside(currentPoint)

        // ---- 降频检查：是否到了该处理的时间 ----
        val interval = getIntervalForLevel(isInsideFence)
        if (lastProcessedTs > 0 && ts - lastProcessedTs < interval) {
            return decision(false)
        }

        // ---- 多源静止判定（委托给 StationaryDetector） ----
        val distance = lastPoint?.let { haversine(it, currentPoint) } ?: Double.MAX_VALUE
        val isStationary = stationaryDetector.isStationary(currentPoint, distance, fix.accuracy, accel)

        // ---- 边界 Buffer 检测已禁用（用户要求删除边界算法，干扰围栏进入） ----
        val inBuffer = false

        // ---- 立即重置条件检查 ----
        val hasMovementSignal = distance > config.resetDistanceM && !isStationary
        if (hasMovementSignal) {
            consecutiveMovementCount++
            if (consecutiveMovementCount >= config.consecutiveMovementThreshold) {
                resetThrottle()
            }
        } else {
            consecutiveMovementCount = 0
            if (isStationary) {
                if (stationarySinceTs == 0L) {
                    stationarySinceTs = ts
                }
                accumulatedStationaryMs = ts - stationarySinceTs
                updateThrottleLevel(isInsideFence)
            } else {
                if (distance > config.resetDistanceM) {
                    stationarySinceTs = 0L
                    accumulatedStationaryMs = 0L
                    level = 0
                }
            }
        }

        lastProcessedTs = ts
        lastPoint = currentPoint

        val historyRecord = LocationHistoryRecord(
            point = currentPoint,
            timestamp = ts,
            accuracy = fix.accuracy,
            speed = fix.speed
        )
        stationaryDetector.addRecord(historyRecord)

        return decision(true, inBuffer, isInsideFence)
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

    /** 是否应注册 SLC 兜底（level>=1即静止1分钟即可注册，兜底>300m位移） */
    fun shouldRegisterSLC(): Boolean = level >= 1 && !slcRegistered

    /** 标记 SLC 已注册 */
    fun markSLCRegistered() { slcRegistered = true }

    /** 是否应注销 SLC */
    fun shouldUnregisterSLC(): Boolean = level == 0 && slcRegistered

    /** 标记 SLC 已注销 */
    fun markSLCUnregistered() { slcRegistered = false }

    /** 清理资源 */
    fun dispose() {
        stationaryDetector.reset()
        lastPoint = null
        stationarySinceTs = 0L
        accumulatedStationaryMs = 0L
        level = 0
        consecutiveMovementCount = 0
    }

    // ============================================================
    // 内部方法
    // ============================================================

    /** 更新降频阶梯等级
     * @param isInsideFence 是否在围栏内（围栏内静止时使用更激进的降频阶梯）
     */
    private fun updateThrottleLevel(isInsideFence: Boolean) {
        val steps = getCurrentThrottleSteps(isInsideFence)
        var newLevel = 0
        for (i in steps.indices.reversed()) {
            if (accumulatedStationaryMs >= steps[i].afterMin * 60_000L) {
                newLevel = i
                break
            }
        }
        level = newLevel
    }

    /** 获取当前等级对应的定位间隔
     * @param isInsideFence 是否在围栏内（围栏内静止时使用更激进的降频阶梯）
     */
    private fun getIntervalForLevel(isInsideFence: Boolean): Long {
        val steps = getCurrentThrottleSteps(isInsideFence)
        if (steps.isEmpty()) return 60_000L
        return steps[level.coerceAtMost(steps.size - 1)].intervalMs
    }

    /** 计算扩展的 BBox（基于围栏中心纬度） */
    private fun computeExpandedBBox(bbox: BBox, bufferM: Double): BBox {
        val centerLat = (bbox.minLat + bbox.maxLat) / 2.0
        val dLat = bufferM / 111_320.0
        val dLng = bufferM / (111_320.0 * cos(centerLat * PI / 180.0))
        return BBox(
            bbox.minLng - dLng, bbox.minLat - dLat,
            bbox.maxLng + dLng, bbox.maxLat + dLat,
        )
    }

    /** 预计算所有叶子围栏的扩展 BBox */
    private fun precomputeExpandedBBoxes(bufferM: Double) {
        expandedBBoxCache.clear()
        for (leaf in leavesByDepth) {
            val bbox = bboxCache[leaf.id] ?: continue
            expandedBBoxCache[leaf.id] = computeExpandedBBox(bbox, bufferM)
        }
        lastBufferM = bufferM
        lastBoundaryCheckPoint = null
        lastBoundaryCheckResult = false
    }

    /** 检查点是否在任何叶子围栏的边界 Buffer 区内 */
    private fun checkBoundaryBuffer(point: GeoPoint): Boolean {
        lastBoundaryCheckPoint?.let { lastPoint ->
            if (haversine(point, lastPoint) < 5.0) {
                return lastBoundaryCheckResult
            }
        }

        for (leaf in leavesByDepth) {
            val bbox = bboxCache[leaf.id] ?: continue

            val fenceRadiusM = computeFenceRadiusM(leaf)
            val bufferM = minOf(config.boundaryBufferM, fenceRadiusM * 0.3)

            val expanded = expandedBBoxCache[leaf.id] ?: computeExpandedBBox(bbox, bufferM)

            if (pointInBBox(point.lat, point.lng, expanded)) {
                if (!pointInPolygon(point.lat, point.lng, leaf.polygon)) {
                    lastBoundaryCheckPoint = point
                    lastBoundaryCheckResult = true
                    return true
                }
            }
        }

        lastBoundaryCheckPoint = point
        lastBoundaryCheckResult = false
        return false
    }

    /**
     * 计算围栏的"特征半径"（米）
     * 用于动态buffer计算
     */
    private fun computeFenceRadiusM(fence: Geofence): Double {
        return when (fence.type) {
            com.trackmaster.app.core.GeoFenceType.CIRCLE -> fence.radius ?: 10.0
            com.trackmaster.app.core.GeoFenceType.POLYGON -> {
                if (fence.polygon.size < 3) return 10.0
                val centerLat = fence.polygon.map { it.lat }.average()
                val centerLng = fence.polygon.map { it.lng }.average()
                val center = GeoPoint(centerLat, centerLng)
                fence.polygon.maxOf { haversine(center, it) }.coerceAtLeast(5.0)
            }
        }
    }

    /**
     * 检查点是否在围栏内（精确检测，无缓冲区）
     * 用于决定围栏内激进降频策略
     */
    private fun checkFenceInside(point: GeoPoint): Boolean {
        for (leaf in leavesByDepth) {
            val bbox = bboxCache[leaf.id] ?: continue
            if (!pointInBBox(point.lat, point.lng, bbox)) continue
            if (pointInPolygon(point.lat, point.lng, leaf.polygon)) {
                return true
            }
        }
        return false
    }

    /** 重置降频状态 */
    private fun resetThrottle() {
        level = 0
        stationarySinceTs = 0L
        accumulatedStationaryMs = 0L
        slcRegistered = false
        lastProcessedTs = 0L
        consecutiveMovementCount = 0
        stationaryDetector.reset()
        lastPoint = null
        lastBoundaryCheckPoint = null
        lastBoundaryCheckResult = false
    }

    /** 构造调度决策 */
    private fun decision(shouldProcess: Boolean, inBuffer: Boolean = false, isInsideFence: Boolean = false): ScheduleDecision =
        ScheduleDecision(
            shouldProcess = shouldProcess,
            level = level,
            nextIntervalMs = getIntervalForLevel(isInsideFence),
            inBoundaryBuffer = inBuffer,
            isInsideFence = isInsideFence,
        )
}
