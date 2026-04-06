package com.trackmaster.app

import com.trackmaster.app.core.AccelerometerData
import com.trackmaster.app.core.FilteredLocation
import com.trackmaster.app.core.KalmanFilter
import com.trackmaster.app.core.KalmanFilterConfig
import com.trackmaster.app.core.LocationFix
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class KalmanFilterTest {

    private lateinit var filter: KalmanFilter

    @Before
    fun setup() {
        filter = KalmanFilter()
    }

    @Test
    fun `filter initialization works`() {
        val location = LocationFix(
            lat = 40.7128,
            lng = -74.0060,
            accuracy = 5.0f,
            timestamp = System.currentTimeMillis()
        )

        val result = filter.process(location)

        assertNotNull(result)
        assertEquals(40.7128, result.lat, 1e-6)
        assertEquals(-74.0060, result.lng, 1e-6)
        assertEquals(0.0, result.velX, 1e-6)
        assertEquals(0.0, result.velY, 1e-6)
    }

    @Test
    fun `filter smooths noisy data`() {
        val baseLat = 40.7128
        val baseLng = -74.0060
        val noiseLevel = 0.001

        val locations = mutableListOf<LocationFix>()
        for (i in 0..19) {
            val noisyLat = baseLat + (Math.random() - 0.5) * noiseLevel
            val noisyLng = baseLng + (Math.random() - 0.5) * noiseLevel
            locations.add(
                LocationFix(
                    lat = noisyLat,
                    lng = noisyLng,
                    accuracy = 10.0f,
                    timestamp = System.currentTimeMillis() + i * 1000
                )
            )
        }

        val results = mutableListOf<FilteredLocation>()
        for (loc in locations) {
            results.add(filter.process(loc))
        }

        val lastResult = results.last()
        assertTrue(
            "Filtered position should be closer to true value",
            abs(lastResult.lat - baseLat) < noiseLevel / 2
        )
        assertTrue(
            "Filtered position should be closer to true value",
            abs(lastResult.lng - baseLng) < noiseLevel / 2
        )

        val rawVariance = calculateVariance(locations.map { it.lat })
        val filteredVariance = calculateVariance(results.map { it.lat })
        assertTrue(
            "Filtered variance should be lower than raw variance",
            filteredVariance < rawVariance
        )
    }

    @Test
    fun `filter tracks moving object`() {
        val startLat = 40.7128
        val startLng = -74.0060
        val velocityLat = 0.00001
        val velocityLng = 0.000015

        val locations = mutableListOf<LocationFix>()
        for (i in 0..29) {
            val trueLat = startLat + velocityLat * i
            val trueLng = startLng + velocityLng * i
            val noisyLat = trueLat + (Math.random() - 0.5) * 0.0005
            val noisyLng = trueLng + (Math.random() - 0.5) * 0.0005
            locations.add(
                LocationFix(
                    lat = noisyLat,
                    lng = noisyLng,
                    accuracy = 5.0f,
                    timestamp = System.currentTimeMillis() + i * 1000
                )
            )
        }

        var lastResult: FilteredLocation? = null
        for (loc in locations) {
            lastResult = filter.process(loc)
        }

        assertNotNull(lastResult)
        val expectedLat = startLat + velocityLat * 29
        val expectedLng = startLng + velocityLng * 29

        assertTrue(
            "Filtered latitude should track true position",
            abs(lastResult!!.lat - expectedLat) < 0.0002
        )
        assertTrue(
            "Filtered longitude should track true position",
            abs(lastResult.lng - expectedLng) < 0.0002
        )
    }

    @Test
    fun `config update works`() {
        val config = KalmanFilterConfig(
            processNoisePosition = 5.0,
            processNoiseVelocity = 0.5,
            observationNoise = 10.0
        )

        val customFilter = KalmanFilter(config)

        val location = LocationFix(
            lat = 40.7128,
            lng = -74.0060,
            accuracy = 5.0f,
            timestamp = System.currentTimeMillis()
        )

        customFilter.process(location)

        val newConfig = KalmanFilterConfig(
            observationNoise = 1.0
        )
        customFilter.updateConfig(newConfig)

        val result = customFilter.process(
            LocationFix(
                lat = 40.7129,
                lng = -74.0061,
                accuracy = 5.0f,
                timestamp = System.currentTimeMillis() + 1000
            )
        )

        assertNotNull(result)
    }

    @Test
    fun `reset works`() {
        val location1 = LocationFix(
            lat = 40.7128,
            lng = -74.0060,
            accuracy = 5.0f,
            timestamp = System.currentTimeMillis()
        )
        filter.process(location1)

        val stateBefore = filter.getCurrentState()
        assertNotNull(stateBefore)

        filter.reset()

        val stateAfter = filter.getCurrentState()
        assertNull(stateAfter)

        val location2 = LocationFix(
            lat = 51.5074,
            lng = -0.1278,
            accuracy = 5.0f,
            timestamp = System.currentTimeMillis() + 1000
        )
        val result = filter.process(location2)

        assertEquals(51.5074, result.lat, 1e-6)
        assertEquals(-0.1278, result.lng, 1e-6)
    }

    @Test
    fun `getCurrentState returns correct state`() {
        val location = LocationFix(
            lat = 40.7128,
            lng = -74.0060,
            accuracy = 5.0f,
            timestamp = System.currentTimeMillis()
        )
        val result = filter.process(location)

        val state = filter.getCurrentState()

        assertNotNull(state)
        assertEquals(result.lat, state!!.lat, 1e-6)
        assertEquals(result.lng, state.lng, 1e-6)
        assertEquals(result.velX, state.velX, 1e-6)
        assertEquals(result.velY, state.velY, 1e-6)
        assertEquals(result.timestamp, state.timestamp)
        assertEquals(result.uncertainty, state.uncertainty, 1e-6)
    }

    private fun calculateVariance(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val mean = values.average()
        val sumSquaredDiff = values.sumOf { (it - mean) * (it - mean) }
        return sumSquaredDiff / values.size
    }

    @Test
    fun `filter performance is within limits`() {
        val locations = mutableListOf<LocationFix>()
        for (i in 0..99) {
            locations.add(
                LocationFix(
                    lat = 40.7128 + (Math.random() - 0.5) * 0.001,
                    lng = -74.0060 + (Math.random() - 0.5) * 0.001,
                    accuracy = 5.0f,
                    timestamp = System.currentTimeMillis() + i * 100
                )
            )
        }

        val startTime = System.nanoTime()
        for (loc in locations) {
            filter.process(loc)
        }
        val endTime = System.nanoTime()

        val averageTimeMs = (endTime - startTime) / 100.0 / 1_000_000.0
        assertTrue(
            "Average filter time should be less than 50ms, was ${String.format("%.2f", averageTimeMs)}ms",
            averageTimeMs < 50.0
        )
    }
}
