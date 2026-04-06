package com.trackmaster.app.core

import android.util.Log

data class KalmanFilterConfig(
    val processNoisePosition: Double = 1.0,
    val processNoiseVelocity: Double = 0.1,
    val observationNoise: Double = 3.0,
    val initialUncertaintyPosition: Double = 10.0,
    val initialUncertaintyVelocity: Double = 1.0
)

data class FilteredLocation(
    val lat: Double,
    val lng: Double,
    val velX: Double,
    val velY: Double,
    val timestamp: Long,
    val uncertainty: Double
)

class KalmanFilter(
    private val config: KalmanFilterConfig = KalmanFilterConfig()
) {
    companion object {
        private const val TAG = "KalmanFilter"
        private const val EARTH_RADIUS_M = 6_371_000.0
    }

    private var isInitialized = false

    private lateinit var stateX: DoubleArray
    private lateinit var covarianceP: Array<DoubleArray>

    private var lastTimestamp: Long = 0L

    private val F = Array(4) { DoubleArray(4) }
    private val H = Array(2) { DoubleArray(4) }
    private val Q = Array(4) { DoubleArray(4) }
    private val R = Array(2) { DoubleArray(2) }

    init {
        for (i in 0..3) {
            F[i][i] = 1.0
        }

        H[0][0] = 1.0
        H[1][1] = 1.0

        R[0][0] = config.observationNoise
        R[1][1] = config.observationNoise
    }

    fun updateConfig(newConfig: KalmanFilterConfig) {
        R[0][0] = newConfig.observationNoise
        R[1][1] = newConfig.observationNoise
        Log.d(TAG, "滤波器配置已更新")
    }

    fun reset() {
        isInitialized = false
        Log.d(TAG, "滤波器已重置")
    }

    fun process(
        location: LocationFix,
        accel: AccelerometerData? = null
    ): FilteredLocation {
        val startTime = System.nanoTime()

        if (!isInitialized) {
            initialize(location)
        }

        val dt = (location.timestamp - lastTimestamp) / 1000.0
        lastTimestamp = location.timestamp

        predict(dt, accel)
        update(location)

        val uncertainty = calculateUncertainty()

        val result = FilteredLocation(
            lat = stateX[0],
            lng = stateX[1],
            velX = stateX[2],
            velY = stateX[3],
            timestamp = location.timestamp,
            uncertainty = uncertainty
        )

        val durationMs = (System.nanoTime() - startTime) / 1_000_000.0
        if (durationMs > 50) {
            Log.w(TAG, "滤波延迟过高: ${String.format("%.2f", durationMs)}ms")
        }

        return result
    }

    private fun initialize(location: LocationFix) {
        stateX = doubleArrayOf(
            location.lat,
            location.lng,
            0.0,
            0.0
        )

        covarianceP = Array(4) { DoubleArray(4) }
        covarianceP[0][0] = config.initialUncertaintyPosition
        covarianceP[1][1] = config.initialUncertaintyPosition
        covarianceP[2][2] = config.initialUncertaintyVelocity
        covarianceP[3][3] = config.initialUncertaintyVelocity

        lastTimestamp = location.timestamp
        isInitialized = true

        Log.d(TAG, "滤波器初始化完成: (${location.lat}, ${location.lng})")
    }

    private fun predict(dt: Double, accel: AccelerometerData?) {
        F[0][2] = dt
        F[1][3] = dt

        val accelX = accel?.x?.toDouble() ?: 0.0
        val accelY = accel?.y?.toDouble() ?: 0.0

        stateX[0] += stateX[2] * dt + 0.5 * accelX * dt * dt
        stateX[1] += stateX[3] * dt + 0.5 * accelY * dt * dt
        stateX[2] += accelX * dt
        stateX[3] += accelY * dt

        val dtSq = dt * dt
        val dtCu = dtSq * dt
        val dtQu = dtCu * dt

        Q[0][0] = config.processNoisePosition * dtQu / 4.0
        Q[0][2] = config.processNoisePosition * dtCu / 2.0
        Q[1][1] = config.processNoisePosition * dtQu / 4.0
        Q[1][3] = config.processNoisePosition * dtCu / 2.0
        Q[2][0] = config.processNoisePosition * dtCu / 2.0
        Q[2][2] = config.processNoisePosition * dtSq
        Q[3][1] = config.processNoisePosition * dtCu / 2.0
        Q[3][3] = config.processNoisePosition * dtSq

        Q[0][0] += config.processNoiseVelocity * dtCu / 3.0
        Q[0][2] += config.processNoiseVelocity * dtSq / 2.0
        Q[1][1] += config.processNoiseVelocity * dtCu / 3.0
        Q[1][3] += config.processNoiseVelocity * dtSq / 2.0
        Q[2][0] += config.processNoiseVelocity * dtSq / 2.0
        Q[2][2] += config.processNoiseVelocity * dt
        Q[3][1] += config.processNoiseVelocity * dtSq / 2.0
        Q[3][3] += config.processNoiseVelocity * dt

        val tempP = multiplyMatrix(covarianceP, transposeMatrix(F))
        val FP = multiplyMatrix(F, tempP)
        covarianceP = addMatrix(FP, Q)
    }

    private fun update(location: LocationFix) {
        val z = doubleArrayOf(location.lat, location.lng)

        val y = subtractVector(z, multiplyMatrixVector(H, stateX))

        val Ht = transposeMatrix(H)
        val PHt = multiplyMatrix(covarianceP, Ht)
        val S = addMatrix(multiplyMatrix(H, PHt), R)

        val K = multiplyMatrix(PHt, invertMatrix2x2(S))

        stateX = addVector(stateX, multiplyMatrixVector(K, y))

        val I = Array(4) { DoubleArray(4) }
        for (i in 0..3) I[i][i] = 1.0
        val KH = multiplyMatrix(K, H)
        val IminusKH = subtractMatrix(I, KH)
        covarianceP = multiplyMatrix(IminusKH, covarianceP)
    }

    private fun calculateUncertainty(): Double {
        return Math.sqrt(covarianceP[0][0] + covarianceP[1][1])
    }

    private fun multiplyMatrix(a: Array<DoubleArray>, b: Array<DoubleArray>): Array<DoubleArray> {
        val m = a.size
        val n = b[0].size
        val p = b.size
        val result = Array(m) { DoubleArray(n) }
        for (i in 0 until m) {
            for (j in 0 until n) {
                for (k in 0 until p) {
                    result[i][j] += a[i][k] * b[k][j]
                }
            }
        }
        return result
    }

    private fun multiplyMatrixVector(matrix: Array<DoubleArray>, vector: DoubleArray): DoubleArray {
        val m = matrix.size
        val n = vector.size
        val result = DoubleArray(m)
        for (i in 0 until m) {
            for (j in 0 until n) {
                result[i] += matrix[i][j] * vector[j]
            }
        }
        return result
    }

    private fun transposeMatrix(matrix: Array<DoubleArray>): Array<DoubleArray> {
        val m = matrix.size
        val n = matrix[0].size
        val result = Array(n) { DoubleArray(m) }
        for (i in 0 until m) {
            for (j in 0 until n) {
                result[j][i] = matrix[i][j]
            }
        }
        return result
    }

    private fun addMatrix(a: Array<DoubleArray>, b: Array<DoubleArray>): Array<DoubleArray> {
        val m = a.size
        val n = a[0].size
        val result = Array(m) { DoubleArray(n) }
        for (i in 0 until m) {
            for (j in 0 until n) {
                result[i][j] = a[i][j] + b[i][j]
            }
        }
        return result
    }

    private fun subtractMatrix(a: Array<DoubleArray>, b: Array<DoubleArray>): Array<DoubleArray> {
        val m = a.size
        val n = a[0].size
        val result = Array(m) { DoubleArray(n) }
        for (i in 0 until m) {
            for (j in 0 until n) {
                result[i][j] = a[i][j] - b[i][j]
            }
        }
        return result
    }

    private fun addVector(a: DoubleArray, b: DoubleArray): DoubleArray {
        val result = DoubleArray(a.size)
        for (i in a.indices) {
            result[i] = a[i] + b[i]
        }
        return result
    }

    private fun subtractVector(a: DoubleArray, b: DoubleArray): DoubleArray {
        val result = DoubleArray(a.size)
        for (i in a.indices) {
            result[i] = a[i] - b[i]
        }
        return result
    }

    private fun invertMatrix2x2(matrix: Array<DoubleArray>): Array<DoubleArray> {
        val det = matrix[0][0] * matrix[1][1] - matrix[0][1] * matrix[1][0]
        if (Math.abs(det) < 1e-10) {
            Log.w(TAG, "矩阵行列式接近零，使用伪逆")
            return Array(2) { DoubleArray(2) }
        }
        val invDet = 1.0 / det
        return arrayOf(
            doubleArrayOf(matrix[1][1] * invDet, -matrix[0][1] * invDet),
            doubleArrayOf(-matrix[1][0] * invDet, matrix[0][0] * invDet)
        )
    }

    fun getCurrentState(): FilteredLocation? {
        if (!isInitialized) return null
        return FilteredLocation(
            lat = stateX[0],
            lng = stateX[1],
            velX = stateX[2],
            velY = stateX[3],
            timestamp = lastTimestamp,
            uncertainty = calculateUncertainty()
        )
    }
}
