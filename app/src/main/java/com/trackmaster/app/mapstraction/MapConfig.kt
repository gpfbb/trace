package com.trackmaster.app.mapstraction

/**
 * 地图配置数据类
 */
data class MapConfig(
    val centerLat: Double = 39.9042,
    val centerLng: Double = 116.4074,
    val zoomLevel: Float = 15f,
    val mapType: MapType = MapType.STANDARD,
    val showTraffic: Boolean = false,
    val showBuildings: Boolean = true,
    val enableMyLocation: Boolean = true
)

enum class MapType {
    STANDARD,
    SATELLITE,
    NIGHT,
    NAVIGATION
}
