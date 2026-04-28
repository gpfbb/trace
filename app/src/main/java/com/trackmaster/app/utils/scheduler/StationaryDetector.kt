package com.trackmaster.app.utils.scheduler

import com.trackmaster.app.core.AccelerometerData
import com.trackmaster.app.core.GeoPoint
import com.trackmaster.app.throttle.LocationHistoryRecord
import com.trackmaster.app.throttle.ThrottleConfig
import com.trackmaster.app.utils.geometry.haversine
import kotlin.math.sqrt

/**
 * 多源静止检测器
 *
 * 多维度判定：
 * 1. 距离阈值（动态计算，考虑精度）
 * 2. 加速度方差（分别计算各轴的方差）
 * 3. 速度判定（使用位置历史计算速度）
 * 4. 时间窗口平滑判定（需要多个点同时满足条件）
 */
class StationaryDetector(
    private var config: ThrottleConfig
) {
    companion object {
        private const val ACCEL_WINDOW = 10
    }

    // ---- 位置历史窗口 ----
    private val locationHistory = ArrayDeque<LocationHistoryRecord>()

    // ---- 加速度分量缓冲区（分别存储x、y、z轴）----
    private val accelXBuffer = ArrayDeque<Double>()
    private val accelYBuffer = ArrayDeque<Double>()
    private val accelZBuffer = ArrayDeque<Double>()

    fun updateConfig(newConfig: ThrottleConfig) {
        this.config = newConfig
    }

    /**
     * 添加位置记录到历史窗口
     */
    fun addRecord(record: LocationHistoryRecord) {
        locationHistory.addLast(record)
        if (locationHistory.size > config.stationaryWindowSize) {
            locationHistory.removeFirst()
        }
    }

    /**
     * 多源静止判定
     * @param currentPoint 当前定位点
     * @param distanceM 与上一有效点的距离（米）
     * @param accuracyM 当前定位精度（米）
     * @param accel 加速度计数据（可为null）
     * @return true 如果判定为静止
     */
    fun isStationary(currentPoint: GeoPoint, distanceM: Double, accuracyM: Float, accel: AccelerometerData?): Boolean {
        val distThreshold = computeDynamicDistanceThreshold(accuracyM)
        val currentDistOk = distanceM < distThreshold

        val speedOk = checkSpeedInWindow()

        val accelOk = if (accel != null) {
            checkAccelerometer(accel)
        } else {
            true
        }

        val windowOk = if (locationHistory.size >= config.stationaryMinPoints) {
            val centerPoint = computeWindowCenter(locationHistory.toList())
            val distToCenter = haversine(centerPoint, currentPoint)
            distToCenter < distThreshold
        } else {
            true
        }

        val singlePointOk = currentDistOk && speedOk && accelOk

        return if (locationHistory.size >= config.stationaryMinPoints) {
            windowOk
        } else {
            singlePointOk
        }
    }

    /**
     * 重置所有缓冲区和状态
     */
    fun reset() {
        accelXBuffer.clear()
        accelYBuffer.clear()
        accelZBuffer.clear()
        locationHistory.clear()
    }

    // ============================================================
    // 内部方法
    // ============================================================

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

        val recentRecords = locationHistory.toList().takeLast(3)
        if (recentRecords.size < 2) return true

        var totalSpeed = 0.0
        var count = 0

        for (i in 1 until recentRecords.size) {
            val prev = recentRecords[i - 1]
            val curr = recentRecords[i]
            val timeDiff = (curr.timestamp - prev.timestamp)

            if (timeDiff > 0) {
                val distance = haversine(prev.point, curr.point)
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
     * 检查加速度（分别计算各轴的方差）
     */
    private fun checkAccelerometer(accel: AccelerometerData): Boolean {
        accelXBuffer.addLast(accel.x.toDouble())
        if (accelXBuffer.size > ACCEL_WINDOW) accelXBuffer.removeFirst()
        accelYBuffer.addLast(accel.y.toDouble())
        if (accelYBuffer.size > ACCEL_WINDOW) accelYBuffer.removeFirst()
        accelZBuffer.addLast(accel.z.toDouble())
        if (accelZBuffer.size > ACCEL_WINDOW) accelZBuffer.removeFirst()

        if (accelXBuffer.size < ACCEL_WINDOW) return true

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
     * 计算窗口内的中心点
     */
    private fun computeWindowCenter(records: List<LocationHistoryRecord>): GeoPoint {
        if (records.isEmpty()) return GeoPoint(0.0, 0.0)
        val latSum = records.sumOf { it.point.lat }
        val lngSum = records.sumOf { it.point.lng }
        val count = records.size
        return GeoPoint(latSum / count, lngSum / count)
    }
}
