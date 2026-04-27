package com.trackmaster.app.locationservice

import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

/**
 * LocationProvider null safety test
 */
class LocationProviderTest {

    private lateinit var mockProvider: MockLocationProvider

    class MockLocationProvider : LocationProvider {
        var callback: LocationCallback? = null
        var _lastLocation: Location? = null
        var callCount = 0

        override fun start() {}
        override fun stop() {}
        override fun isRunning(): Boolean = false
        override fun getLastLocation(): Location? = _lastLocation
        override fun setLocationCallback(callback: LocationCallback) {
            this.callback = callback
        }
        override fun removeLocationCallback() {
            this.callback = null
        }
        override fun setLocationMode(mode: LocationMode) {}
        override fun setInterval(intervalMs: Long) {}
        override fun getProviderName(): String = "Mock"

        fun simulateLocationUpdate(location: Location) {
            _lastLocation = location
            callCount++
            callback?.onLocationChanged(location)
        }

        fun simulateError(errorCode: Int, errorInfo: String) {
            callCount++
            callback?.onLocationError(errorCode, errorInfo)
        }
    }

    @Before
    fun setup() {
        mockProvider = MockLocationProvider()
    }

    @Test
    fun callbackNullDoesNotCauseNPE() {
        val location = Location(
            latitude = 39.9289,
            longitude = 116.4173,
            accuracy = 10f,
            speed = 0f,
            bearing = 0f,
            timestamp = System.currentTimeMillis(),
            locationType = LocationType.GPS
        )

        mockProvider.simulateLocationUpdate(location)

        assertEquals("callCount should be 1", 1, mockProvider.callCount)
        assertEquals("lastLocation should match", location, mockProvider.getLastLocation())
    }

    @Test
    fun callbackReceivesLocationUpdates() {
        val receivedLocations = mutableListOf<Location>()

        val testCallback = object : LocationCallback {
            override fun onLocationChanged(location: Location) {
                receivedLocations.add(location)
            }
            override fun onLocationError(errorCode: Int, errorInfo: String) {}
        }

        mockProvider.setLocationCallback(testCallback)

        val location1 = Location(
            latitude = 39.9289,
            longitude = 116.4173,
            accuracy = 10f,
            speed = 0f,
            bearing = 0f,
            timestamp = System.currentTimeMillis(),
            locationType = LocationType.GPS
        )

        mockProvider.simulateLocationUpdate(location1)

        assertEquals("Should receive 1 location", 1, receivedLocations.size)
        assertEquals("lastLocation should match", location1, mockProvider.getLastLocation())
    }

    @Test
    fun removeCallbackStopsNotifications() {
        var notificationCount = 0
        val testCallback = object : LocationCallback {
            override fun onLocationChanged(location: Location) {
                notificationCount++
            }
            override fun onLocationError(errorCode: Int, errorInfo: String) {}
        }

        mockProvider.setLocationCallback(testCallback)
        mockProvider.removeLocationCallback()

        val location = Location(
            latitude = 39.9289,
            longitude = 116.4173,
            accuracy = 10f,
            speed = 0f,
            bearing = 0f,
            timestamp = System.currentTimeMillis(),
            locationType = LocationType.GPS
        )

        mockProvider.simulateLocationUpdate(location)

        assertEquals("Should not receive any notification", 0, notificationCount)
    }

    @Test
    fun getLastLocationReturnsNullWhenNoLocationReceived() {
        assertNull("lastLocation should be null initially", mockProvider.getLastLocation())
    }

    @Test
    fun concurrentLocationUpdatesThreadSafe() {
        var notificationCount = 0
        val testCallback = object : LocationCallback {
            override fun onLocationChanged(location: Location) {
                synchronized(this) {
                    notificationCount++
                }
            }
            override fun onLocationError(errorCode: Int, errorInfo: String) {}
        }

        mockProvider.setLocationCallback(testCallback)

        val threadCount = 10
        val threads = (0 until threadCount).map { i ->
            Thread {
                val location = Location(
                    latitude = 39.9 + i * 0.01,
                    longitude = 116.4 + i * 0.01,
                    accuracy = 10f,
                    speed = 0f,
                    bearing = 0f,
                    timestamp = System.currentTimeMillis(),
                    locationType = LocationType.GPS
                )
                mockProvider.simulateLocationUpdate(location)
            }
        }

        threads.forEach { it.start() }
        threads.forEach { it.join() }

        assertEquals("Should receive $threadCount notifications", threadCount, notificationCount)
        assertEquals("callCount should be $threadCount", threadCount, mockProvider.callCount)
    }

    @Test
    fun errorCallbackWorks() {
        val receivedErrors = mutableListOf<Pair<Int, String>>()

        val testCallback = object : LocationCallback {
            override fun onLocationChanged(location: Location) {}
            override fun onLocationError(errorCode: Int, errorInfo: String) {
                receivedErrors.add(Pair(errorCode, errorInfo))
            }
        }

        mockProvider.setLocationCallback(testCallback)
        mockProvider.simulateError(-1, "Invalid coordinate (0,0)")

        assertEquals("Should receive 1 error", 1, receivedErrors.size)
        assertEquals("Error code should be -1", -1, receivedErrors[0].first)
        assertEquals("Error message should match", "Invalid coordinate (0,0)", receivedErrors[0].second)
    }

    @Test
    fun setIntervalUpdatesInterval() {
        mockProvider.setInterval(30000L)
        mockProvider.setInterval(60000L)
    }

    @Test
    fun setLocationModeUpdatesMode() {
        mockProvider.setLocationMode(LocationMode.HIGH_ACCURACY)
        mockProvider.setLocationMode(LocationMode.BATTERY_SAVING)
        mockProvider.setLocationMode(LocationMode.DEVICE_ONLY)
    }
}