package com.trackmaster.app.core

import android.util.Log
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

enum class DriftType {
    SUDDEN,
    GRADUAL,
    NONE
}

data class DriftPoint(
    val location: LocationFix,
    val driftType: DriftType,
    val confidence: Float,
    val velocityDeviation: Float? = null,
    val accelerationDeviation: Float? = null,
    val distanceDeviation: Float? = null
)

data class TrajectoryWindow(
    val points: List<LocationFix>,
    val avgSpeed: Float,
    val avgAcceleration: Float,
    val maxDeviation: Float
)

class DriftDetector(
    private val windowSize: Int = 10,
    private val suddenDriftThresholdMeters: Double = 50.0,
    private val gradualDriftThresholdMetersPerSecond: Double = 2.0,
    private val velocityThreshold: Float = 30f,
    private val accelerationThreshold: Float = 10f
) {
    companion object {
        private const val TAG = "DriftDetector"
    }

    private val locationHistory = mutableListOf<LocationFix>()
    private val driftPoints = mutableListOf<DriftPoint>()

    fun addLocation(location: LocationFix): DriftPoint? {
        locationHistory.add(location)
        
        if (locationHistory.size > windowSize * 2) {
            locationHistory.removeAt(0)
        }

        if (locationHistory.size < 2) {
            return null
        }

        val driftPoint = detectDrift(location)
        if (driftPoint.driftType != DriftType.NONE) {
            driftPoints.add(driftPoint)
            Log.d(TAG, "检测到漂移: ${driftPoint.driftType}, 置信度: ${driftPoint.confidence}")
        }
        
        return driftPoint
    }

    private fun detectDrift(currentLocation: LocationFix): DriftPoint {
        val prevLocation = locationHistory[locationHistory.size - 2]
        
        val distance = GeoMath.haversine(
            prevLocation.lat, prevLocation.lng,
            currentLocation.lat, currentLocation.lng
        )
        
        val timeDiff = (currentLocation.timestamp - prevLocation.timestamp) / 1000.0
        val velocity = if (timeDiff > 0) distance / timeDiff else 0.0
        
        val velocityDeviation = calculateVelocityDeviation(velocity.toFloat())
        val accelerationDeviation = calculateAccelerationDeviation(currentLocation, prevLocation)
        val distanceDeviation = calculateDistanceDeviation(distance)

        val suddenDrift = detectSuddenDrift(distance, velocity, timeDiff)
        val gradualDrift = detectGradualDrift()

        return when {
            suddenDrift.first -> DriftPoint(
                location = currentLocation,
                driftType = DriftType.SUDDEN,
                confidence = suddenDrift.second,
                velocityDeviation = velocityDeviation,
                accelerationDeviation = accelerationDeviation,
                distanceDeviation = distance.toFloat()
            )
            gradualDrift.first -> DriftPoint(
                location = currentLocation,
                driftType = DriftType.GRADUAL,
                confidence = gradualDrift.second,
                velocityDeviation = velocityDeviation,
                accelerationDeviation = accelerationDeviation,
                distanceDeviation = distance.toFloat()
            )
            else -> DriftPoint(
                location = currentLocation,
                driftType = DriftType.NONE,
                confidence = 0f
            )
        }
    }

    private fun detectSuddenDrift(distance: Double, velocity: Double, timeDiff: Double): Pair<Boolean, Float> {
        if (distance > suddenDriftThresholdMeters) {
            val confidence = minOf(1.0f, (distance / suddenDriftThresholdMeters).toFloat())
            return Pair(true, confidence)
        }
        
        if (velocity > velocityThreshold && timeDiff < 5.0) {
            val confidence = minOf(1.0f, (velocity / velocityThreshold).toFloat())
            return Pair(true, confidence)
        }
        
        return Pair(false, 0f)
    }

    private fun detectGradualDrift(): Pair<Boolean, Float> {
        if (locationHistory.size < windowSize) {
            return Pair(false, 0f)
        }

        val recentPoints = locationHistory.takeLast(windowSize)
        val avgDistance = calculateAverageDistance(recentPoints)
        val distanceTrend = calculateDistanceTrend(recentPoints)

        if (avgDistance > gradualDriftThresholdMetersPerSecond * 2 && distanceTrend > 0) {
            val confidence = minOf(1.0f, (avgDistance / (gradualDriftThresholdMetersPerSecond * 3)).toFloat())
            return Pair(true, confidence)
        }

        return Pair(false, 0f)
    }

    private fun calculateVelocityDeviation(currentVelocity: Float): Float {
        if (locationHistory.size < 3) return 0f
        
        val recentVelocities = mutableListOf<Float>()
        for (i in 1 until locationHistory.size - 1) {
            val prev = locationHistory[i - 1]
            val curr = locationHistory[i]
            val dist = GeoMath.haversine(prev.lat, prev.lng, curr.lat, curr.lng)
            val timeDiff = (curr.timestamp - prev.timestamp) / 1000.0
            if (timeDiff > 0) {
                recentVelocities.add((dist / timeDiff).toFloat())
            }
        }
        
        if (recentVelocities.isEmpty()) return 0f
        
        val avgVelocity = recentVelocities.average().toFloat()
        return abs(currentVelocity - avgVelocity)
    }

    private fun calculateAccelerationDeviation(current: LocationFix, prev: LocationFix): Float {
        if (locationHistory.size < 3) return 0f
        
        val prevPrev = locationHistory[locationHistory.size - 3]
        val dist1 = GeoMath.haversine(prevPrev.lat, prevPrev.lng, prev.lat, prev.lng)
        val time1 = (prev.timestamp - prevPrev.timestamp) / 1000.0
        val velocity1 = if (time1 > 0) dist1 / time1 else 0.0
        
        val dist2 = GeoMath.haversine(prev.lat, prev.lng, current.lat, current.lng)
        val time2 = (current.timestamp - prev.timestamp) / 1000.0
        val velocity2 = if (time2 > 0) dist2 / time2 else 0.0
        
        val acceleration = if (time2 > 0) (velocity2 - velocity1) / time2 else 0.0
        return abs(acceleration.toFloat())
    }

    private fun calculateDistanceDeviation(currentDistance: Double): Float {
        if (locationHistory.size < windowSize) return 0f
        
        val recentDistances = mutableListOf<Double>()
        for (i in 1 until locationHistory.size - 1) {
            val prev = locationHistory[i - 1]
            val curr = locationHistory[i]
            recentDistances.add(GeoMath.haversine(prev.lat, prev.lng, curr.lat, curr.lng))
        }
        
        if (recentDistances.isEmpty()) return 0f
        
        val avgDistance = recentDistances.average()
        return abs(currentDistance - avgDistance).toFloat()
    }

    private fun calculateAverageDistance(points: List<LocationFix>): Double {
        if (points.size < 2) return 0.0
        
        var totalDistance = 0.0
        for (i in 1 until points.size) {
            totalDistance += GeoMath.haversine(
                points[i - 1].lat, points[i - 1].lng,
                points[i].lat, points[i].lng
            )
        }
        
        return totalDistance / (points.size - 1)
    }

    private fun calculateDistanceTrend(points: List<LocationFix>): Double {
        if (points.size < 4) return 0.0
        
        val firstHalf = points.subList(0, points.size / 2)
        val secondHalf = points.subList(points.size / 2, points.size)
        
        val avgFirst = calculateAverageDistance(firstHalf)
        val avgSecond = calculateAverageDistance(secondHalf)
        
        return avgSecond - avgFirst
    }

    fun getTrajectoryWindow(): TrajectoryWindow? {
        if (locationHistory.size < windowSize) return null
        
        val points = locationHistory.takeLast(windowSize)
        val velocities = mutableListOf<Float>()
        val accelerations = mutableListOf<Float>()
        
        for (i in 1 until points.size) {
            val dist = GeoMath.haversine(
                points[i - 1].lat, points[i - 1].lng,
                points[i].lat, points[i].lng
            )
            val timeDiff = (points[i].timestamp - points[i - 1].timestamp) / 1000.0
            if (timeDiff > 0) {
                velocities.add((dist / timeDiff).toFloat())
            }
        }
        
        for (i in 2 until points.size) {
            val dist1 = GeoMath.haversine(
                points[i - 2].lat, points[i - 2].lng,
                points[i - 1].lat, points[i - 1].lng
            )
            val time1 = (points[i - 1].timestamp - points[i - 2].timestamp) / 1000.0
            val v1 = if (time1 > 0) dist1 / time1 else 0.0
            
            val dist2 = GeoMath.haversine(
                points[i - 1].lat, points[i - 1].lng,
                points[i].lat, points[i].lng
            )
            val time2 = (points[i].timestamp - points[i - 1].timestamp) / 1000.0
            val v2 = if (time2 > 0) dist2 / time2 else 0.0
            
            if (time2 > 0) {
                accelerations.add(((v2 - v1) / time2).toFloat())
            }
        }
        
        val avgSpeed = if (velocities.isNotEmpty()) velocities.average().toFloat() else 0f
        val avgAcceleration = if (accelerations.isNotEmpty()) accelerations.average().toFloat() else 0f
        
        val maxDeviation = calculateMaxDeviation(points)
        
        return TrajectoryWindow(
            points = points,
            avgSpeed = avgSpeed,
            avgAcceleration = avgAcceleration,
            maxDeviation = maxDeviation
        )
    }

    private fun calculateMaxDeviation(points: List<LocationFix>): Float {
        if (points.size < 3) return 0f
        
        val centerLat = points.map { it.lat }.average()
        val centerLng = points.map { it.lng }.average()
        
        var maxDist = 0.0
        for (point in points) {
            val dist = GeoMath.haversine(centerLat, centerLng, point.lat, point.lng)
            if (dist > maxDist) {
                maxDist = dist
            }
        }
        
        return maxDist.toFloat()
    }

    fun filterDriftPoints(points: List<LocationFix>): List<LocationFix> {
        val filtered = mutableListOf<LocationFix>()
        
        for (point in points) {
            val driftPoint = addLocation(point)
            if (driftPoint == null || driftPoint.driftType == DriftType.NONE || driftPoint.confidence < 0.7f) {
                filtered.add(point)
            }
        }
        
        return filtered
    }

    fun getDriftHistory(): List<DriftPoint> = driftPoints.toList()

    fun clear() {
        locationHistory.clear()
        driftPoints.clear()
    }
}
