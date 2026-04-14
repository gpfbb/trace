package com.trackmaster.app.utils

import android.util.Log
import com.trackmaster.app.utils.GeoMath.HysteresisConfig
import com.trackmaster.app.core.PowerMode
import kotlin.math.max
import kotlin.math.min

/**
 * 动态缓冲区管理器
 *
 * 根据以下因素动态调整围栏缓冲区大小：
 * 1. 定位精度 - 精度越低，缓冲区越大
 * 2. 移动速度 - 速度越高，缓冲区越大
 * 3. 电源模式 - 省电模式下缓冲区更大
 * 4. 历史平滑 - 避免缓冲区大小剧烈波动
 */
class DynamicBufferManager {
    companion object {
        private const val TAG = "DynamicBufferManager"

        // 默认配置
        private const val DEFAULT_BASE_BUFFER = 10.0 // 米
        private const val MAX_BUFFER = 100.0 // 米
        private const val MIN_BUFFER = 2.0 // 米

        // 精度乘数：定位精度每增加1米，缓冲区增加多少米
        private const val ACCURACY_MULTIPLIER = 1.5

        // 速度乘数：速度每增加1m/s，缓冲区增加多少米
        private const val SPEED_MULTIPLIER = 2.0

        // 平滑因子：指数移动平均的alpha值 (0.0-1.0)
        private const val SMOOTHING_ALPHA = 0.3

        // 历史记录最大大小
        private const val MAX_HISTORY_SIZE = 10

        // 电量模式乘数
        private val POWER_MODE_MULTIPLIERS = mapOf(
            PowerMode.BALANCED to 1.0,     // 均衡模式：默认
            PowerMode.POWERSAVE to 1.5     // 省电模式：较大缓冲区
        )
    }

    // 缓冲区状态
    data class BufferState(
        val bufferSizeM: Double,
        val hysteresisConfig: HysteresisConfig,
        val factors: BufferFactors
    )

    // 影响缓冲区的各个因素
    data class BufferFactors(
        val accuracyFactor: Double,
        val speedFactor: Double,
        val powerModeFactor: Double,
        val smoothedBuffer: Double
    )

    // 当前状态
    private var currentBufferSize: Double = DEFAULT_BASE_BUFFER
    private var currentHysteresisConfig: HysteresisConfig = HysteresisConfig()
    private var currentPowerMode: PowerMode = PowerMode.BALANCED

    // 历史数据用于平滑
    private val bufferHistory = mutableListOf<Double>()

    // 统计信息
    private var updateCount: Int = 0

    /**
     * 更新缓冲区配置
     *
     * @param accuracyM 定位精度（米），越高表示精度越差
     * @param speedMps 速度（米/秒），可为null
     * @param powerMode 当前电源模式
     * @return 更新后的缓冲区状态
     */
    fun update(
        accuracyM: Double,
        speedMps: Double? = null,
        powerMode: PowerMode = PowerMode.BALANCED
    ): BufferState {
        updateCount++
        currentPowerMode = powerMode

        // 计算各因素贡献
        val accuracyFactor = calculateAccuracyFactor(accuracyM)
        val speedFactor = calculateSpeedFactor(speedMps)
        val powerModeFactor = POWER_MODE_MULTIPLIERS[powerMode] ?: 1.0

        // 计算原始缓冲区大小
        val rawBuffer = DEFAULT_BASE_BUFFER * accuracyFactor * speedFactor * powerModeFactor

        // 应用平滑
        val smoothedBuffer = applySmoothing(rawBuffer)

        // 限制范围
        val finalBuffer = smoothedBuffer.coerceIn(MIN_BUFFER, MAX_BUFFER)

        // 计算滞回配置
        val hysteresisConfig = computeDynamicHysteresis(finalBuffer)

        // 更新状态
        currentBufferSize = finalBuffer
        currentHysteresisConfig = hysteresisConfig

        // 记录统计（每50次更新记录一次）
        if (updateCount % 50 == 0) {
            Log.d(TAG, "缓冲区统计: 更新次数=$updateCount, 当前缓冲区=${"%.1f".format(finalBuffer)}m, " +
                    "精度因子=${"%.2f".format(accuracyFactor)}, 速度因子=${"%.2f".format(speedFactor)}, " +
                    "电量因子=${"%.2f".format(powerModeFactor)}")
        }

        return BufferState(
            bufferSizeM = finalBuffer,
            hysteresisConfig = hysteresisConfig,
            factors = BufferFactors(
                accuracyFactor = accuracyFactor,
                speedFactor = speedFactor,
                powerModeFactor = powerModeFactor,
                smoothedBuffer = smoothedBuffer
            )
        )
    }

    /**
     * 根据定位精度计算缓冲区因子
     * 精度越低（值越大），缓冲区越大
     */
    private fun calculateAccuracyFactor(accuracyM: Double): Double {
        // 将精度映射到[0.5, 3.0]范围内
        // 精度0-5米: 因子0.5-1.0
        // 精度5-50米: 因子1.0-2.0
        // 精度>50米: 因子2.0-3.0
        return when {
            accuracyM <= 0.0 -> 0.5
            accuracyM <= 5.0 -> 0.5 + (accuracyM / 5.0) * 0.5
            accuracyM <= 50.0 -> 1.0 + (accuracyM / 50.0)
            else -> min(3.0, 2.0 + (accuracyM - 50.0) / 100.0)
        }
    }

    /**
     * 根据速度计算缓冲区因子
     * 速度越高，缓冲区越大（避免错过快速移动的围栏）
     */
    private fun calculateSpeedFactor(speedMps: Double?): Double {
        if (speedMps == null || speedMps <= 0.0) return 1.0

        // 速度映射：
        // 0-1 m/s (0-3.6 km/h): 因子1.0（步行）
        // 1-5 m/s (3.6-18 km/h): 因子1.0-1.5（慢跑/骑行）
        // 5-20 m/s (18-72 km/h): 因子1.5-2.5（车辆）
        // >20 m/s (>72 km/h): 因子2.5（高速）
        return when {
            speedMps <= 1.0 -> 1.0
            speedMps <= 5.0 -> 1.0 + (speedMps - 1.0) / 4.0 * 0.5
            speedMps <= 20.0 -> 1.5 + (speedMps - 5.0) / 15.0 * 1.0
            else -> min(3.0, 2.5 + (speedMps - 20.0) / 30.0)
        }
    }

    /**
     * 应用指数移动平均平滑
     */
    private fun applySmoothing(newValue: Double): Double {
        if (bufferHistory.isEmpty()) {
            bufferHistory.add(newValue)
            return newValue
        }

        // 指数移动平均: EMA = alpha * newValue + (1 - alpha) * previousEMA
        val previous = bufferHistory.last()
        val smoothed = SMOOTHING_ALPHA * newValue + (1 - SMOOTHING_ALPHA) * previous

        bufferHistory.add(smoothed)

        // 限制历史记录大小
        if (bufferHistory.size > MAX_HISTORY_SIZE) {
            bufferHistory.removeAt(0)
        }

        return smoothed
    }

    /**
     * 根据缓冲区大小计算滞回配置
     * 缓冲区越大，滞回阈值也相应增大
     */
    private fun computeDynamicHysteresis(bufferM: Double): HysteresisConfig {
        // 基础滞回配置
        val base = HysteresisConfig(
            outerEnterM = 5.0,
            outerExitM = 10.0,
            innerEnterM = 5.0,
            innerExitM = 10.0
        )

        // 根据缓冲区大小缩放滞回阈值
        val scale = bufferM / DEFAULT_BASE_BUFFER

        return HysteresisConfig(
            outerEnterM = base.outerEnterM * scale,
            outerExitM = base.outerExitM * scale,
            innerEnterM = base.innerEnterM * scale,
            innerExitM = base.innerExitM * scale
        )
    }

    /**
     * 获取当前缓冲区状态
     */
    fun getCurrentState(): BufferState = BufferState(
        bufferSizeM = currentBufferSize,
        hysteresisConfig = currentHysteresisConfig,
        factors = BufferFactors(
            accuracyFactor = 0.0,
            speedFactor = 0.0,
            powerModeFactor = 1.0,
            smoothedBuffer = currentBufferSize
        )
    )

    /**
     * 重置缓冲区状态
     */
    fun reset() {
        currentBufferSize = DEFAULT_BASE_BUFFER
        currentHysteresisConfig = HysteresisConfig()
        bufferHistory.clear()
        updateCount = 0
        Log.d(TAG, "缓冲区管理器已重置")
    }

    /**
     * 获取统计信息
     */
    data class Stats(
        val updateCount: Int,
        val currentBufferM: Double,
        val bufferHistory: List<Double>,
        val powerMode: PowerMode
    )

    fun getStats(): Stats = Stats(
        updateCount = updateCount,
        currentBufferM = currentBufferSize,
        bufferHistory = bufferHistory.toList(),
        powerMode = currentPowerMode
    )
}