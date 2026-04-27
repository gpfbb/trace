package com.trackmaster.app.utils.geometry

import com.trackmaster.app.core.Geofence
import com.trackmaster.app.core.GeoFenceType
import com.trackmaster.app.core.GeoPoint

// ============================================================
// 围栏检测结果状态
// ============================================================

/**
 * 围栏检测结果状态
 */
enum class FenceState {
    INSIDE,
    OUTSIDE,
    OUTER_BUFFER,
    INNER_BUFFER
}

/**
 * 内/外 Buffer 区的滞回检测配置
 * 更精细的控制，支持内/外不同的缓冲区大小
 */
data class HysteresisConfig(
    val outerEnterM: Double = 5.0,
    val outerExitM: Double = 10.0,
    val innerEnterM: Double = 5.0,
    val innerExitM: Double = 10.0
)

/**
 * 基于定位精度动态计算缓冲区大小
 * @param accuracyM 定位精度（米）
 * @param baseBufferM 基础缓冲区大小（米）
 * @param accuracyMultiplier 精度倍率（默认 1.0）
 * @return 动态调整后的缓冲区大小
 */
fun computeDynamicBuffer(
    accuracyM: Double,
    baseBufferM: Double = 10.0,
    accuracyMultiplier: Double = 1.0
): Double {
    return baseBufferM + (accuracyM * accuracyMultiplier).coerceIn(0.0, 50.0)
}

/**
 * 基于定位精度动态计算滞回配置
 * @param accuracyM 定位精度（米）
 * @param baseConfig 基础配置
 * @param accuracyMultiplier 精度倍率（默认 1.0）
 * @return 动态调整后的滞回配置
 */
fun computeDynamicHysteresisConfig(
    accuracyM: Double,
    baseConfig: HysteresisConfig = HysteresisConfig(),
    accuracyMultiplier: Double = 1.0
): HysteresisConfig {
    val dynamicAdjustment = (accuracyM * accuracyMultiplier).coerceIn(0.0, 30.0)

    return HysteresisConfig(
        outerEnterM = baseConfig.outerEnterM + dynamicAdjustment * 0.5,
        outerExitM = baseConfig.outerExitM + dynamicAdjustment,
        innerEnterM = baseConfig.innerEnterM + dynamicAdjustment * 0.5,
        innerExitM = baseConfig.innerExitM + dynamicAdjustment
    )
}

// ============================================================
// 带 Buffer 区的围栏检测
// ============================================================

/**
 * 带 Buffer 区的围栏检测
 * @param bufferM 缓冲区大小（米）
 * @return 围栏状态
 */
fun pointInFenceWithBuffer(point: GeoPoint, fence: Geofence, bufferM: Double): FenceState {
    val distance = distanceToFenceBoundary(point, fence)

    return when {
        distance < -bufferM -> FenceState.INSIDE
        distance in -bufferM..0.0 -> FenceState.INNER_BUFFER
        distance in 0.0..bufferM -> FenceState.OUTER_BUFFER
        else -> FenceState.OUTSIDE
    }
}

/**
 * 扩展的 pointInFence 函数，支持 Buffer 区
 * @param bufferM 缓冲区大小（米）
 * @param considerBufferAsInside 是否将缓冲区视为"内部"
 */
fun pointInFence(
    point: GeoPoint,
    fence: Geofence,
    bufferM: Double = 0.0,
    considerBufferAsInside: Boolean = false
): Boolean {
    if (bufferM <= 0.0) {
        return pointInFence(point, fence)
    }

    val state = pointInFenceWithBuffer(point, fence, bufferM)

    return if (considerBufferAsInside) {
        state == FenceState.INSIDE ||
        state == FenceState.INNER_BUFFER ||
        state == FenceState.OUTER_BUFFER
    } else {
        state == FenceState.INSIDE
    }
}

/**
 * 带滞回机制（Hysteresis）的围栏检测器
 * 避免边界抖动：进入缓冲区需要更小的距离，离开需要更大的距离
 *
 * @param enterBufferM 进入时的缓冲区（较小）
 * @param exitBufferM 离开时的缓冲区（较大，应 > enterBufferM）
 * @param previousState 前一次的检测状态
 */
fun pointInFenceWithHysteresis(
    point: GeoPoint,
    fence: Geofence,
    enterBufferM: Double,
    exitBufferM: Double,
    previousState: Boolean
): Boolean {
    require(exitBufferM >= enterBufferM) {
        "exitBufferM must be >= enterBufferM for hysteresis to work"
    }

    val distance = distanceToFenceBoundary(point, fence)

    return if (previousState) {
        distance <= exitBufferM
    } else {
        distance <= enterBufferM
    }
}

/**
 * 精细化的滞回检测，支持内外缓冲区独立配置
 */
fun pointInFenceWithInnerOuterHysteresis(
    point: GeoPoint,
    fence: Geofence,
    config: HysteresisConfig = HysteresisConfig(),
    previousState: FenceState = FenceState.OUTSIDE
): FenceState {
    val distance = distanceToFenceBoundary(point, fence)

    return when (previousState) {
        FenceState.OUTSIDE -> {
            when {
                distance <= -config.outerEnterM -> FenceState.INSIDE
                distance <= config.outerEnterM -> FenceState.OUTER_BUFFER
                else -> FenceState.OUTSIDE
            }
        }
        FenceState.OUTER_BUFFER -> {
            when {
                distance <= -config.outerEnterM -> FenceState.INSIDE
                distance > config.outerExitM -> FenceState.OUTSIDE
                else -> FenceState.OUTER_BUFFER
            }
        }
        FenceState.INNER_BUFFER -> {
            when {
                distance <= -config.innerExitM -> FenceState.INSIDE
                distance > config.innerExitM -> FenceState.OUTSIDE
                else -> FenceState.INNER_BUFFER
            }
        }
        FenceState.INSIDE -> {
            when {
                distance > config.innerExitM -> FenceState.OUTSIDE
                distance > 0.0 -> FenceState.OUTER_BUFFER
                distance > -config.innerEnterM -> FenceState.INNER_BUFFER
                else -> FenceState.INSIDE
            }
        }
    }
}
