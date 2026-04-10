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
import kotlin.math.abs

// ============================================================
// 位置历史记录
// ============================================================

data class LocationHistoryRecord(
    val point: GeoPoint,
    val timestamp: Long,
    val accuracy: Float,
    val speed: Float?
)

// ============================================================
// 配置
// ============================================================

data class ThrottleConfig(
    /** 漂移过滤阈值（米），accuracy > 此值丢弃 */
    val driftThresholdM: Double = 80.0,
    /** 静止判定：距离阈值倍率（精度半径 × 此值） */
    val stationaryAccuracyMultiplier: Double = 2.0,
    /** 静止判定：最小距离阈值（米），越大越容易判定静止 */
    val stationaryMinDistanceM: Double = 100.0,
    /** 静止判定：加速度方差阈值，越大越容易判定静止 */
    val stationaryAccelVariance: Double = 0.5,
    /** 静止判定：速度阈值（米/秒），越大越容易判定静止 */
    val stationarySpeedThreshold: Double = 1.5,
    /** 静止判定：时间窗口大小（定位点数） */
    val stationaryWindowSize: Int = 5,
    /** 静止判定：需要在窗口内有多少个点满足条件才判定为静止 */
    val stationaryMinPoints: Int = 3,
    /** 阶梯降频各阶段（静止分钟 → 间隔ms） */
    val throttleSteps: List<ThrottleStep> = DEFAULT_THROTTLE_STEPS,
    /** 立即重置距离阈值（米），位移超过此值立即重置降频 */
    val resetDistanceM: Double = 200.0,
    /** 边界 Buffer 区宽度（米），省电模式下只有非常接近围栏边界才提频 */
    val boundaryBufferM: Double = 10.0,
    /** SLC 兜底位移阈值（米） */
    val slcDriftM: Double = 300.0,
    /** 基础定位间隔（ms） */
    val baseIntervalMs: Long = 30_000L,
)

data class ThrottleStep(
    /** 静止分钟后生效 */
    val afterMin: Int,
    /** 对应定位间隔（ms） */
    val intervalMs: Long,
)

/**
 * 均衡模式降频阶梯
 * 适用于 BALANCED 模式
 * 优化：更快的降频，更长的间隔
 */
val BALANCED_THROTTLE_STEPS = listOf(
    ThrottleStep(afterMin = 0, intervalMs = 30_000L),     // 0-1min: 30s/次
    ThrottleStep(afterMin = 1, intervalMs = 90_000L),      // 1-3min: 90s/次
    ThrottleStep(afterMin = 3, intervalMs = 180_000L),     // 3-5min: 3min/次
    ThrottleStep(afterMin = 5, intervalMs = 300_000L),     // 5-10min: 5min/次
    ThrottleStep(afterMin = 10, intervalMs = 480_000L),     // 10-20min: 8min/次
    ThrottleStep(afterMin = 20, intervalMs = 600_000L),     // 20-40min: 10min/次
    ThrottleStep(afterMin = 40, intervalMs = 900_000L),    // 40-60min: 15min/次
    ThrottleStep(afterMin = 60, intervalMs = 1200_000L),   // ≥60min: 20min/次
)

/**
 * 省电模式降频阶梯（优化版）
 * 更激进的降频，适合睡觉/长时间静止场景
 */
val POWERSAVE_THROTTLE_STEPS = listOf(
    ThrottleStep(afterMin = 0, intervalMs = 30_000L),      // 0-1min: 30s/次
    ThrottleStep(afterMin = 1, intervalMs = 120_000L),     // 1-3min: 2min/次
    ThrottleStep(afterMin = 3, intervalMs = 300_000L),      // 3-5min: 5min/次
    ThrottleStep(afterMin = 5, intervalMs = 480_000L),     // 5-10min: 8min/次
    ThrottleStep(afterMin = 10, intervalMs = 600_000L),    // 10-20min: 10min/次
    ThrottleStep(afterMin = 20, intervalMs = 900_000L),    // 20-40min: 15min/次
    ThrottleStep(afterMin = 40, intervalMs = 1200_000L),   // 40-60min: 20min/次
    ThrottleStep(afterMin = 60, intervalMs = 1800_000L),   // ≥60min: 30min/次
)

/**
 * 默认降频阶梯（兼容旧版本）
 */
val DEFAULT_THROTTLE_STEPS = POWERSAVE_THROTTLE_STEPS

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
    val stats: PowerSavingStats = PowerSavingStats(),
) {
    var config = config
        private set
    
    fun updateConfig(newConfig: ThrottleConfig) {
        val bufferChanged = newConfig.boundaryBufferM != config.boundaryBufferM
        this.config = newConfig

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

    // ---- 加速度窗口 ----
    private val accelBuffer = ArrayDeque<AccelerometerData>()
    private val ACCEL_WINDOW = 10

    // ---- 位置历史窗口 ----
    private val locationHistory = ArrayDeque<LocationHistoryRecord>()

    // ---- 加速度分量缓冲区（分别存储x、y、z轴）----
    private val accelXBuffer = ArrayDeque<Double>()
    private val accelYBuffer = ArrayDeque<Double>()
    private val accelZBuffer = ArrayDeque<Double>()

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
     * 根据当前电源模式获取对应的降频阶梯
     */
    private fun getCurrentThrottleSteps(): List<ThrottleStep> {
        return when (mode) {
            PowerMode.PERFORMANCE -> {
                // 性能模式：固定 5 秒间隔（极快响应）
                listOf(ThrottleStep(afterMin = 0, intervalMs = 5_000L))
            }
            PowerMode.BALANCED -> BALANCED_THROTTLE_STEPS
            PowerMode.POWERSAVE -> POWERSAVE_THROTTLE_STEPS
        }
    }

    // ============================================================
    // 公开 API
    // ============================================================

    /** 设置电源模式 */
    fun setMode(mode: PowerMode) {
        this.mode = mode
        // 切换模式时重置降频状态
        // PERFORMANCE模式需要立即响应，所以重置到最高频率
        // 其他模式根据静止状态自适应降频
        resetThrottle()
    }

    fun getMode(): PowerMode = mode

    /**
     * 核心评估入口：每收到一个 LocationFix 调用此方法
     */
    fun evaluate(fix: LocationFix, accel: AccelerometerData? = null): ScheduleDecision {
        val ts = fix.timestamp
        val decision: ScheduleDecision

        // ---- 性能模式：不降频（但仍更新状态，避免切换模式时使用陈旧数据） ----
        if (mode == PowerMode.PERFORMANCE) {
            lastProcessedTs = ts
            lastPoint = GeoPoint(fix.lat, fix.lng)
            stationarySinceTs = 0L
            accumulatedStationaryMs = 0L
            level = 0
            decision = ScheduleDecision(
                shouldProcess = true,
                level = 0,
                nextIntervalMs = config.baseIntervalMs,
                inBoundaryBuffer = false,
            )
        } else {
            // ---- 漂移过滤 ----
            if (fix.accuracy > config.driftThresholdM) {
                decision = decision(false)
            } else {
                val currentPoint = GeoPoint(fix.lat, fix.lng)

                // ---- 降频检查：是否到了该处理的时间 ----
                val interval = getIntervalForLevel()
                if (lastProcessedTs > 0 && ts - lastProcessedTs < interval) {
                    decision = decision(false)
                } else {
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

                    val historyRecord = LocationHistoryRecord(
                        point = currentPoint,
                        timestamp = ts,
                        accuracy = fix.accuracy,
                        speed = fix.speed
                    )
                    locationHistory.addLast(historyRecord)
                    if (locationHistory.size > config.stationaryWindowSize) {
                        locationHistory.removeFirst()
                    }

                    decision = decision(true, inBuffer)
                }
            }
        }

        // 记录统计信息
        stats.recordLocation(
            timestamp = ts,
            powerMode = mode,
            shouldProcess = decision.shouldProcess,
            level = decision.level,
            intervalMs = decision.nextIntervalMs,
            inBoundaryBuffer = decision.inBoundaryBuffer
        )

        return decision
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
        accelXBuffer.clear()
        accelYBuffer.clear()
        accelZBuffer.clear()
        locationHistory.clear()
        lastPoint = null
        stationarySinceTs = 0L
        accumulatedStationaryMs = 0L
        level = 0
    }

    // ============================================================
    // 内部方法
    // ============================================================

    /**
     * 多源静止判定（优化版）
     * 多维度判定：
     * 1. 距离阈值（动态计算，考虑精度）
     * 2. 加速度方差（分别计算各轴的方差）
     * 3. 速度判定（使用位置历史计算速度）
     * 4. 时间窗口平滑判定（需要多个点同时满足条件）
     */
    private fun checkStationary(distanceM: Double, accuracyM: Float, accel: AccelerometerData?): Boolean {
        val distThreshold = computeDynamicDistanceThreshold(accuracyM)
        val currentDistOk = distanceM < distThreshold

        val speedOk = checkSpeedInWindow()

        val accelOk = if (accel != null) {
            checkAccelerometer(accel)
        } else {
            true
        }

        val windowOk = checkLocationWindow(distThreshold)

        val singlePointOk = currentDistOk && speedOk && accelOk

        return if (locationHistory.size >= config.stationaryMinPoints) {
            windowOk
        } else {
            singlePointOk
        }
    }

    /**
     * 动态计算距离阈值
     * 根据精度动态调整，同时考虑历史精度的平滑值
     */
    private fun computeDynamicDistanceThreshold(accuracyM: Float): Double {
        val baseThreshold = maxOf(
            accuracyM * config.stationaryAccuracyMultiplier,
            config.stationaryMinDistanceM
        )
        
        if (locationHistory.size >= 3) {
            val avgAccuracy = locationHistory.map { it.accuracy }.average()
            val smoothedThreshold = maxOf(
                avgAccuracy * config.stationaryAccuracyMultiplier,
                config.stationaryMinDistanceM
            )
            return maxOf(baseThreshold, smoothedThreshold)
        }
        
        return baseThreshold
    }

    /**
     * 检查速度判定（结合位置历史计算）
     */
    private fun checkSpeedInWindow(): Boolean {
        if (locationHistory.size < 2) return true

        val recentRecords = locationHistory.takeLast(3)
        if (recentRecords.size < 2) return true

        var totalSpeed = 0.0
        var count = 0

        for (i in 1 until recentRecords.size) {
            val prev = recentRecords[i - 1]
            val curr = recentRecords[i]
            val timeDiff = (curr.timestamp - prev.timestamp)

            if (timeDiff > 0) {
                val distance = GeoMath.haversine(prev.point, curr.point)
                val speed = distance / (timeDiff / 1000.0)
                totalSpeed += speed
                count++
            }
        }

        if (count == 0) return true

        val avgSpeed = totalSpeed / count
        return avgSpeed < config.stationarySpeedThreshold
    }

    /**
     * 检查加速度（优化版，分别计算各轴的方差
     */
    private fun checkAccelerometer(accel: AccelerometerData): Boolean {
        accelBuffer.addLast(accel)
        if (accelBuffer.size > ACCEL_WINDOW) accelBuffer.removeFirst()

        accelXBuffer.addLast(accel.x.toDouble())
        if (accelXBuffer.size > ACCEL_WINDOW) accelXBuffer.removeFirst()

        accelYBuffer.addLast(accel.y.toDouble())
        if (accelYBuffer.size > ACCEL_WINDOW) accelYBuffer.removeFirst()

        accelZBuffer.addLast(accel.z.toDouble())
        if (accelZBuffer.size > ACCEL_WINDOW) accelZBuffer.removeFirst()

        // 需要足够的样本才能计算方差
        if (accelBuffer.size < ACCEL_WINDOW) return true

        val varianceX = computeSingleAxisVariance(accelXBuffer)
        val varianceY = computeSingleAxisVariance(accelYBuffer)
        val varianceZ = computeSingleAxisVariance(accelZBuffer)

        val maxVariance = maxOf(varianceX, varianceY, varianceZ)
        val avgVariance = (varianceX + varianceY + varianceZ) / 3.0

        return maxVariance < config.stationaryAccelVariance * 1.5 &&
               avgVariance < config.stationaryAccelVariance
    }

    /**
     * 计算单轴加速度方差
     */
    private fun computeSingleAxisVariance(buffer: List<Double>): Double {
        if (buffer.size < 2) return 0.0
        val n = buffer.size
        val mean = buffer.sum() / n
        return buffer.sumOf { (it - mean) * (it - mean) } / n
    }

    /**
     * 检查位置窗口内的点是否都满足距离条件
     */
    private fun checkLocationWindow(distThreshold: Double): Boolean {
        if (locationHistory.size < config.stationaryMinPoints) return true

        val recentRecords = locationHistory.toList()
        val centerPoint = computeWindowCenter(recentRecords)

        var okCount = 0

        for (record in recentRecords) {
            val distance = GeoMath.haversine(centerPoint, record.point)
            val adjustedThreshold = maxOf(
                distThreshold, record.accuracy * config.stationaryAccuracyMultiplier)
            if (distance < adjustedThreshold) {
                okCount++
            }
        }

        return okCount >= config.stationaryMinPoints
    }

    /**
     * 计算窗口内的中心点
     */
    private fun computeWindowCenter(records: List<LocationHistoryRecord>): GeoPoint {
        val latSum = records.sumOf { it.point.lat }
        val lngSum = records.sumOf { it.point.lng }
        val count = records.size
        return GeoPoint(latSum / count, lngSum / count)
    }

    /**
     * 计算加速度方差（保留原方法，向后兼容）
     */
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
        val steps = getCurrentThrottleSteps()
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
        val steps = getCurrentThrottleSteps()
        return steps[level.coerceAtMost(steps.size - 1)].intervalMs
    }

    /** 计算扩展的 BBox（基于围栏中心纬度） */
    private fun computeExpandedBBox(bbox: BBox, bufferM: Double): BBox {
        // 使用 BBox 中心纬度进行计算，使扩展 BBox 固定
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
        // 清空最近检查点缓存，因为扩展 BBox 可能已变化
        lastBoundaryCheckPoint = null
        lastBoundaryCheckResult = false
    }

    /** 检查点是否在任何叶子围栏的边界 Buffer 区内 */
    private fun checkBoundaryBuffer(point: GeoPoint): Boolean {
        val bufferM = config.boundaryBufferM

        // 如果 bufferM 变化，重新预计算所有扩展 BBox
        if (bufferM != lastBufferM) {
            precomputeExpandedBBoxes(bufferM)
        }

        // 如果点距离上次检查的点很近，直接复用上次结果
        lastBoundaryCheckPoint?.let { lastPoint ->
            if (GeoMath.haversine(point, lastPoint) < 5.0) {
                return lastBoundaryCheckResult
            }
        }

        for (leaf in leavesByDepth) {
            val bbox = bboxCache[leaf.id] ?: continue

            // 围栏内不算 Buffer
            if (GeoMath.pointInPolygon(point.lat, point.lng, leaf.polygon)) continue

            // 获取预计算的扩展 BBox（应该已存在）
            val expanded = expandedBBoxCache[leaf.id] ?: computeExpandedBBox(bbox, bufferM)

            if (GeoMath.pointInBBox(point.lat, point.lng, expanded)) {
                lastBoundaryCheckPoint = point
                lastBoundaryCheckResult = true
                return true
            }
        }

        lastBoundaryCheckPoint = point
        lastBoundaryCheckResult = false
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
