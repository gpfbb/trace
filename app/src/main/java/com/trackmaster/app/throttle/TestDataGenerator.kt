package com.trackmaster.app.throttle

import com.trackmaster.app.core.AccelerometerData
import com.trackmaster.app.core.ActivityResult
import com.trackmaster.app.core.ActivityType
import com.trackmaster.app.core.BBox
import com.trackmaster.app.core.GeoMath
import com.trackmaster.app.core.GeoPoint
import com.trackmaster.app.core.Geofence
import com.trackmaster.app.core.GeoFenceType
import com.trackmaster.app.core.LocationFix
import com.trackmaster.app.core.PowerMode
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI
import kotlin.random.Random

class TestDataGenerator {
    private val random = Random.Default

    fun generateLocationFix(
        baseLat: Double = 39.9042,
        baseLng: Double = 116.4074,
        timestamp: Long,
        accuracy: Float = 10f,
        maxOffsetMeters: Double = 50.0
    ): LocationFix {
        val offsetLat = random.nextDouble(-maxOffsetMeters / 111320.0, maxOffsetMeters / 111320.0)
        val offsetLng = random.nextDouble(
            -maxOffsetMeters / (111320.0 * cos(baseLat * PI / 180.0)),
            maxOffsetMeters / (111320.0 * cos(baseLat * PI / 180.0))
        )
        return LocationFix(
            lat = baseLat + offsetLat,
            lng = baseLng + offsetLng,
            accuracy = accuracy,
            timestamp = timestamp
        )
    }

    fun generateMovingLocationFixes(
        startLat: Double = 39.9042,
        startLng: Double = 116.4074,
        startTime: Long,
        durationMs: Long,
        intervalMs: Long = 5000L,
        speedKmh: Double = 5.0
    ): List<LocationFix> {
        val fixes = mutableListOf<LocationFix>()
        val speedMps = speedKmh / 3.6
        val distancePerInterval = speedMps * intervalMs / 1000.0
        val angle = random.nextDouble(0.0, 2 * PI)
        
        var currentLat = startLat
        var currentLng = startLng
        var currentTime = startTime

        while (currentTime < startTime + durationMs) {
            fixes.add(
                generateLocationFix(
                    baseLat = currentLat,
                    baseLng = currentLng,
                    timestamp = currentTime,
                    accuracy = 15f
                )
            )
            
            currentLat += (distancePerInterval * cos(angle)) / 111320.0
            currentLng += (distancePerInterval * sin(angle)) / (111320.0 * cos(startLat * PI / 180.0))
            currentTime += intervalMs
        }

        return fixes
    }

    fun generateStationaryLocationFixes(
        baseLat: Double = 39.9042,
        baseLng: Double = 116.4074,
        startTime: Long,
        durationMs: Long,
        intervalMs: Long = 5000L
    ): List<LocationFix> {
        val fixes = mutableListOf<LocationFix>()
        var currentTime = startTime

        while (currentTime < startTime + durationMs) {
            fixes.add(
                generateLocationFix(
                    baseLat = baseLat,
                    baseLng = baseLng,
                    timestamp = currentTime,
                    accuracy = 10f,
                    maxOffsetMeters = 20.0
                )
            )
            currentTime += intervalMs
        }

        return fixes
    }

    fun generateAccelerometerData(
        timestamp: Long,
        isMoving: Boolean = false
    ): AccelerometerData {
        return if (isMoving) {
            AccelerometerData(
                x = (random.nextFloat() * 4f) - 2f,
                y = (random.nextFloat() * 4f) - 2f,
                z = 9.8f + (random.nextFloat() * 2f) - 1f,
                timestamp = timestamp
            )
        } else {
            AccelerometerData(
                x = (random.nextFloat() * 0.2f) - 0.1f,
                y = (random.nextFloat() * 0.2f) - 0.1f,
                z = 9.8f + (random.nextFloat() * 0.2f) - 0.1f,
                timestamp = timestamp
            )
        }
    }

    fun generateActivityResult(
        timestamp: Long,
        type: ActivityType = ActivityType.STILL
    ): ActivityResult {
        return ActivityResult(
            type = type,
            confidence = random.nextInt(70, 100),
            timestamp = timestamp
        )
    }

    fun generateGeofence(
        id: String,
        name: String,
        centerLat: Double = 39.9042,
        centerLng: Double = 116.4074,
        radiusMeters: Double = 200.0
    ): Geofence {
        val center = GeoPoint(centerLat, centerLng)
        val polygon = generateCirclePolygon(center, radiusMeters, 32)
        val bbox = GeoMath.computeBBox(polygon)

        return Geofence(
            id = id,
            name = name,
            type = GeoFenceType.CIRCLE,
            polygon = polygon,
            bbox = bbox,
            center = center,
            radius = radiusMeters
        )
    }

    private fun generateCirclePolygon(
        center: GeoPoint,
        radiusMeters: Double,
        points: Int
    ): List<GeoPoint> {
        val polygon = mutableListOf<GeoPoint>()
        for (i in 0 until points) {
            val angle = 2 * PI * i / points
            val latOffset = (radiusMeters * cos(angle)) / 111320.0
            val lngOffset = (radiusMeters * sin(angle)) / (111320.0 * cos(center.lat * PI / 180.0))
            polygon.add(
                GeoPoint(
                    lat = center.lat + latOffset,
                    lng = center.lng + lngOffset
                )
            )
        }
        return polygon
    }

    fun runPowerSavingTest(
        mode: PowerMode,
        testDurationMs: Long = 60000L,
        stationaryRatio: Double = 0.7
    ): PowerSavingStats {
        val fences = listOf(
            generateGeofence("home", "家", 39.9042, 116.4074, 300.0)
        )
        val scheduler = SmartScheduler(fences)
        scheduler.setMode(mode)

        val startTime = System.currentTimeMillis()
        val stationaryDurationMs = (testDurationMs * stationaryRatio).toLong()
        val movingDurationMs = testDurationMs - stationaryDurationMs

        val stationaryFixes = generateStationaryLocationFixes(
            startTime = startTime,
            durationMs = stationaryDurationMs
        )
        val movingFixes = generateMovingLocationFixes(
            startTime = startTime + stationaryDurationMs,
            durationMs = movingDurationMs
        )

        val allFixes = stationaryFixes + movingFixes

        allFixes.forEach { fix ->
            val accel = generateAccelerometerData(
                timestamp = fix.timestamp,
                isMoving = fix.timestamp >= startTime + stationaryDurationMs
            )
            scheduler.evaluate(fix, accel)
        }

        return scheduler.stats
    }

    fun generateComprehensiveTestReport(): String {
        val sb = StringBuilder()
        sb.appendLine("=== 省电算法综合测试报告 ===")
        sb.appendLine()

        PowerMode.values().forEach { mode ->
            sb.appendLine("--- 测试模式: $mode ---")
            val stats = runPowerSavingTest(mode, testDurationMs = 120000L)
            sb.appendLine(stats.generateReport())
            sb.appendLine()
        }

        return sb.toString()
    }
}
