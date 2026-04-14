package com.trackmaster.app.mapstraction

/**
 * 多边形覆盖物模型
 */
data class MapPolygon(
    val id: String,
    val points: List<MapLocation>,
    val fillColor: Int = 0x4000FF00.toInt(),
    val strokeColor: Int = 0xFF00FF00.toInt(),
    val strokeWidth: Float = 2f
)
