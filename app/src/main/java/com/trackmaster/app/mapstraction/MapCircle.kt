package com.trackmaster.app.mapstraction

/**
 * 圆形覆盖物模型
 */
data class MapCircle(
    val id: String,
    val center: MapLocation,
    val radius: Double,
    val fillColor: Int = 0x4000FF00.toInt(),
    val strokeColor: Int = 0xFF00FF00.toInt(),
    val strokeWidth: Float = 2f
)
