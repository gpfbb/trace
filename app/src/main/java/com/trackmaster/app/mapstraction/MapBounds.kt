package com.trackmaster.app.mapstraction

/**
 * 边界框模型
 */
data class MapBounds(
    val northEast: MapLocation,
    val southWest: MapLocation
) {
    fun contains(location: MapLocation): Boolean {
        return location.latitude in southWest.latitude..northEast.latitude &&
                location.longitude in southWest.longitude..northEast.longitude
    }

    val center: MapLocation
        get() = MapLocation(
            latitude = (northEast.latitude + southWest.latitude) / 2,
            longitude = (northEast.longitude + southWest.longitude) / 2
        )
}
