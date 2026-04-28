package com.trackmaster.app.throttle

import com.trackmaster.app.core.GeoPoint

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
    val driftThresholdM: Double = 30.0,
    /** 静止判定：距离阈值倍率（精度半径 × 此值），越大越容易判定静止 */
    val stationaryAccuracyMultiplier: Double = 4.0,
    /** 静止判定：最小距离阈值（米），越大越容易判定静止 */
    val stationaryMinDistanceM: Double = 30.0,
    /** 静止判定：加速度方差阈值，越大越容易判定静止 */
    val stationaryAccelVariance: Double = 3.0,
    /** 静止判定：速度阈值（米/秒），越大越容易判定静止 */
    val stationarySpeedThreshold: Double = 1.5,
    /** 静止判定：时间窗口大小（定位点数），越小越容易判定静止 */
    val stationaryWindowSize: Int = 5,
    /** 静止判定：需要在窗口内有多少个点满足条件才判定为静止，越小越容易 */
    val stationaryMinPoints: Int = 3,
    /** 阶梯降频各阶段（静止分钟 → 间隔ms） */
    val throttleSteps: List<ThrottleStep> = DEFAULT_THROTTLE_STEPS,
    /** 立即重置距离阈值（米），位移超过此值立即重置降频 */
    val resetDistanceM: Double = 30.0,
    /** 边界 Buffer 区宽度（米），接近围栏边界时自动提频，小围栏用户减小缓冲 */
    val boundaryBufferM: Double = 5.0,
    /** SLC 兜底位移阈值（米） */
    val slcDriftM: Double = 300.0,
    /** 基础定位间隔（ms） */
    val baseIntervalMs: Long = 60_000L,
    /** 连续移动确认次数：连续N次移动信号才确认用户真的在移动（排除GPS噪声） */
    val consecutiveMovementThreshold: Int = 3,
)

data class ThrottleStep(
    /** 静止分钟后生效 */
    val afterMin: Int,
    /** 对应定位间隔（ms） */
    val intervalMs: Long,
)

/**
 * 均衡模式降频阶梯（优化版）
 * 适用于 BALANCED 模式，围栏外/非静止时
 */
val BALANCED_THROTTLE_STEPS = listOf(
    ThrottleStep(afterMin = 0, intervalMs = 60_000L),     // 0-1min: 1min/次（起步60s）
    ThrottleStep(afterMin = 1, intervalMs = 120_000L),   // 1-3min: 2min/次
    ThrottleStep(afterMin = 3, intervalMs = 180_000L),  // 3-5min: 3min/次
    ThrottleStep(afterMin = 5, intervalMs = 300_000L),  // ≥5min: 5min/次
)

/**
 * 均衡模式降频阶梯（围栏内激进版）
 * 适用于 BALANCED 模式，围栏内且静止时
 * 围栏内已确认安全，允许更激进降频，最长10min/次
 */
val BALANCED_INSIDE_FENCE_THROTTLE_STEPS = listOf(
    ThrottleStep(afterMin = 0, intervalMs = 60_000L),     // 0-1min: 1min/次（起步60s）
    ThrottleStep(afterMin = 1, intervalMs = 120_000L),    // 1-3min: 2min/次
    ThrottleStep(afterMin = 3, intervalMs = 180_000L),   // 3-5min: 3min/次
    ThrottleStep(afterMin = 5, intervalMs = 300_000L),   // 5-10min: 5min/次
    ThrottleStep(afterMin = 10, intervalMs = 600_000L),  // ≥10min: 10min/次
)

/**
 * 省电模式降频阶梯（优化版）
 * 更激进的降频，适合睡觉/长时间静止场景
 * 用户要求：省电模式一分钟一次起步
 */
val POWERSAVE_THROTTLE_STEPS = listOf(
    ThrottleStep(afterMin = 0, intervalMs = 60_000L),      // 0-1min: 60s/次（1次/分）
    ThrottleStep(afterMin = 1, intervalMs = 120_000L),     // 1-3min: 2min/次
    ThrottleStep(afterMin = 3, intervalMs = 300_000L),      // 3-5min: 5min/次
    ThrottleStep(afterMin = 5, intervalMs = 480_000L),     // 5-10min: 8min/次
    ThrottleStep(afterMin = 10, intervalMs = 600_000L),    // 10-20min: 10min/次
    ThrottleStep(afterMin = 20, intervalMs = 900_000L),     // 20-40min: 15min/次
    ThrottleStep(afterMin = 40, intervalMs = 1200_000L),    // 40-60min: 20min/次
    ThrottleStep(afterMin = 60, intervalMs = 1800_000L),    // ≥60min: 30min/次
)

/**
 * 省电模式降频阶梯（围栏内激进版）
 * 围栏内静止时更激进降频，最长30min/次
 */
val POWERSAVE_INSIDE_FENCE_THROTTLE_STEPS = listOf(
    ThrottleStep(afterMin = 0, intervalMs = 60_000L),       // 0-1min: 1min/次
    ThrottleStep(afterMin = 1, intervalMs = 180_000L),      // 1-3min: 3min/次
    ThrottleStep(afterMin = 3, intervalMs = 300_000L),       // 3-5min: 5min/次
    ThrottleStep(afterMin = 5, intervalMs = 600_000L),       // 5-10min: 10min/次
    ThrottleStep(afterMin = 10, intervalMs = 900_000L),      // 10-20min: 15min/次
    ThrottleStep(afterMin = 20, intervalMs = 1200_000L),     // 20-30min: 20min/次
    ThrottleStep(afterMin = 30, intervalMs = 1800_000L),     // ≥30min: 30min/次
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
    /** 是否在围栏内（用于决定是否使用围栏内激进降频） */
    val isInsideFence: Boolean,
)
