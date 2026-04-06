package com.trackmaster.app

import com.trackmaster.app.core.DriftDetector
import com.trackmaster.app.core.DriftType
import com.trackmaster.app.core.LocationFix
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

class DriftDetectorTest {

    private lateinit var driftDetector: DriftDetector
    private val baseLat = 39.9042
    private val baseLng = 116.4074
    private var timestamp = System.currentTimeMillis()

    @Before
    fun setup() {
        driftDetector = DriftDetector(
            windowSize = 10,
            suddenDriftThresholdMeters = 50.0,
            gradualDriftThresholdMetersPerSecond = 2.0,
            velocityThreshold = 30f,
            accelerationThreshold = 10f
        )
        timestamp = System.currentTimeMillis()
    }

    private fun createLocation(latOffset: Double = 0.0, lngOffset: Double = 0.0, timeOffsetMs: Long = 1000): LocationFix {
        timestamp += timeOffsetMs
        return LocationFix(
            lat = baseLat + latOffset,
            lng = baseLng + lngOffset,
            accuracy = 5f,
            timestamp = timestamp,
            speed = null
        )
    }

    @Test
    fun `test normal trajectory no drift`() {
        driftDetector.clear()
        
        for (i in 0 until 15) {
            val latOffset = i * 0.00001
            val lngOffset = i * 0.00001
            val location = createLocation(latOffset, lngOffset, 1000)
            val driftPoint = driftDetector.addLocation(location)
            
            if (i > 0) {
                assertNotNull(driftPoint)
                assertEquals(DriftType.NONE, driftPoint?.driftType)
            }
        }
        
        val driftHistory = driftDetector.getDriftHistory()
        assertTrue(driftHistory.isEmpty())
    }

    @Test
    fun `test sudden drift detection`() {
        driftDetector.clear()
        
        for (i in 0 until 5) {
            val location = createLocation(0.0, 0.0, 1000)
            driftDetector.addLocation(location)
        }
        
        val suddenDriftLocation = createLocation(0.001, 0.001, 1000)
        val driftPoint = driftDetector.addLocation(suddenDriftLocation)
        
        assertNotNull(driftPoint)
        assertEquals(DriftType.SUDDEN, driftPoint?.driftType)
        assertTrue(driftPoint?.confidence ?: 0f > 0.5f)
        
        val driftHistory = driftDetector.getDriftHistory()
        assertEquals(1, driftHistory.size)
        assertEquals(DriftType.SUDDEN, driftHistory[0].driftType)
    }

    @Test
    fun `test high velocity sudden drift`() {
        driftDetector.clear()
        
        for (i in 0 until 5) {
            val location = createLocation(0.0, 0.0, 1000)
            driftDetector.addLocation(location)
        }
        
        val fastLocation = createLocation(0.0005, 0.0005, 100)
        val driftPoint = driftDetector.addLocation(fastLocation)
        
        assertNotNull(driftPoint)
        assertTrue(driftPoint?.driftType == DriftType.SUDDEN || driftPoint?.confidence ?: 0f > 0.3f)
    }

    @Test
    fun `test gradual drift detection`() {
        driftDetector.clear()
        
        for (i in 0 until 5) {
            val location = createLocation(0.0, 0.0, 1000)
            driftDetector.addLocation(location)
        }
        
        for (i in 0 until 15) {
            val latOffset = i * 0.0001
            val lngOffset = i * 0.0001
            val location = createLocation(latOffset, lngOffset, 500)
            val driftPoint = driftDetector.addLocation(location)
            
            if (driftPoint != null && driftPoint.driftType == DriftType.GRADUAL) {
                assertTrue(driftPoint.confidence > 0.3f)
                break
            }
        }
        
        val driftHistory = driftDetector.getDriftHistory()
        assertTrue(driftHistory.isNotEmpty())
    }

    @Test
    fun `test trajectory window analysis`() {
        driftDetector.clear()
        
        for (i in 0 until 15) {
            val latOffset = i * 0.00001
            val lngOffset = i * 0.00001
            val location = createLocation(latOffset, lngOffset, 1000)
            driftDetector.addLocation(location)
        }
        
        val window = driftDetector.getTrajectoryWindow()
        assertNotNull(window)
        assertEquals(10, window?.points?.size)
        assertTrue(window?.avgSpeed ?: 0f >= 0f)
        assertTrue(window?.maxDeviation ?: 0f >= 0f)
    }

    @Test
    fun `test filter drift points`() {
        driftDetector.clear()
        
        val points = mutableListOf<LocationFix>()
        
        for (i in 0 until 5) {
            val location = createLocation(0.0, 0.0, 1000)
            points.add(location)
        }
        
        val driftLocation = createLocation(0.001, 0.001, 1000)
        points.add(driftLocation)
        
        for (i in 0 until 5) {
            val location = createLocation(0.0, 0.0, 1000)
            points.add(location)
        }
        
        driftDetector.clear()
        val filtered = driftDetector.filterDriftPoints(points)
        
        assertTrue(filtered.size < points.size)
    }

    @Test
    fun `test clear history`() {
        driftDetector.clear()
        
        for (i in 0 until 10) {
            val location = createLocation(0.0, 0.0, 1000)
            driftDetector.addLocation(location)
        }
        
        val driftLocation = createLocation(0.001, 0.001, 1000)
        driftDetector.addLocation(driftLocation)
        
        assertTrue(driftDetector.getDriftHistory().isNotEmpty())
        
        driftDetector.clear()
        
        assertTrue(driftDetector.getDriftHistory().isEmpty())
    }

    @Test
    fun `test velocity deviation calculation`() {
        driftDetector.clear()
        
        for (i in 0 until 5) {
            val location = createLocation(0.0, 0.0, 1000)
            driftDetector.addLocation(location)
        }
        
        for (i in 0 until 5) {
            val latOffset = i * 0.0001
            val lngOffset = i * 0.0001
            val location = createLocation(latOffset, lngOffset, 1000)
            val driftPoint = driftDetector.addLocation(location)
            
            if (driftPoint != null && driftPoint.velocityDeviation != null) {
                assertTrue(driftPoint.velocityDeviation ?: 0f >= 0f)
            }
        }
    }

    @Test
    fun `test drift point properties`() {
        driftDetector.clear()
        
        for (i in 0 until 5) {
            val location = createLocation(0.0, 0.0, 1000)
            driftDetector.addLocation(location)
        }
        
        val driftLocation = createLocation(0.001, 0.001, 1000)
        val driftPoint = driftDetector.addLocation(driftLocation)
        
        assertNotNull(driftPoint)
        assertNotNull(driftPoint?.location)
        assertNotNull(driftPoint?.driftType)
        assertNotNull(driftPoint?.confidence)
        assertTrue(driftPoint?.confidence ?: 0f in 0f..1f)
    }

    @Test
    fun `test window size limit`() {
        driftDetector.clear()
        
        for (i in 0 until 50) {
            val latOffset = i * 0.00001
            val lngOffset = i * 0.00001
            val location = createLocation(latOffset, lngOffset, 1000)
            driftDetector.addLocation(location)
        }
        
        val window = driftDetector.getTrajectoryWindow()
        assertNotNull(window)
        assertEquals(10, window?.points?.size)
    }
}
