package com.trackmaster.app.service

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.trackmaster.app.data.entity.Geofence
import com.trackmaster.app.data.entity.GeofenceEvent
import com.trackmaster.app.data.repository.TrackingRepository
import kotlinx.coroutines.*
import javax.inject.Inject
import javax.inject.Singleton

data class LatLng(val latitude: Double, val longitude: Double)

@Singleton
class GeofenceService @Inject constructor(
    private val repository: TrackingRepository
) {
    private val gson = Gson()
    private val geofenceState = mutableMapOf<Long, GeofenceState>()
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private data class GeofenceState(
        var isInside: Boolean = false,
        val debounceBuffer: MutableList<Boolean> = mutableListOf()
    )

    fun checkGeofences(location: LatLng) {
        coroutineScope.launch {
            val geofences = repository.getEnabledGeofences()
            geofences.forEach { geofence ->
                checkGeofence(geofence, location)
            }
        }
    }

    private suspend fun checkGeofence(geofence: Geofence, location: LatLng) {
        val points = parsePoints(geofence.pointsJson)
        val isInside = isPointInPolygon(location, points)

        val state = geofenceState.getOrPut(geofence.id) { GeofenceState() }
        state.debounceBuffer.add(isInside)

        if (state.debounceBuffer.size > 3) {
            state.debounceBuffer.removeAt(0)
        }

        if (state.debounceBuffer.size >= 3) {
            val allSame = state.debounceBuffer.all { it == isInside }
            if (allSame && isInside != state.isInside) {
                state.isInside = isInside
                handleGeofenceEvent(geofence, location, isInside)
            }
        }
    }

    private suspend fun handleGeofenceEvent(
        geofence: Geofence,
        location: LatLng,
        isEnter: Boolean
    ) {
        val event = GeofenceEvent(
            geofenceId = geofence.id,
            eventType = if (isEnter) "ENTER" else "EXIT",
            latitude = location.latitude,
            longitude = location.longitude,
            timestamp = System.currentTimeMillis()
        )
        repository.insertGeofenceEvent(event)

        val updatedGeofence = geofence.copy(
            isInside = isEnter,
            color = if (isEnter) 0xFFF44336.toInt() else 0xFF4CAF50.toInt()
        )
        repository.updateGeofence(updatedGeofence)
    }

    private fun parsePoints(pointsJson: String): List<LatLng> {
        val type = object : TypeToken<List<LatLng>>() {}.type
        return gson.fromJson(pointsJson, type)
    }

    private fun isPointInPolygon(point: LatLng, polygon: List<LatLng>): Boolean {
        var inside = false
        val n = polygon.size
        var j = n - 1

        for (i in 0 until n) {
            val xi = polygon[i].latitude
            val yi = polygon[i].longitude
            val xj = polygon[j].latitude
            val yj = polygon[j].longitude

            val intersect = ((yi > point.longitude) != (yj > point.longitude)) &&
                    (point.latitude < (xj - xi) * (point.longitude - yi) / (yj - yi) + xi)

            if (intersect) inside = !inside
            j = i
        }
        return inside
    }

    fun createGeofencePoints(points: List<LatLng>): String {
        return gson.toJson(points)
    }

    fun parseGeofencePoints(json: String): List<LatLng> {
        return parsePoints(json)
    }
}
