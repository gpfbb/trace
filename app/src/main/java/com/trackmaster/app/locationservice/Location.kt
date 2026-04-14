package com.trackmaster.app.locationservice

/**
 * 统一位置数据
 */
data class Location(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float = 0f,
    val speed: Float = 0f,
    val bearing: Float = 0f,
    val timestamp: Long = System.currentTimeMillis(),
    val locationType: LocationType = LocationType.UNKNOWN
)

enum class LocationType {
    GPS,
    NETWORK,
    PASSIVE,
    UNKNOWN
}
