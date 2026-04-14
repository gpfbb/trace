package com.trackmaster.app.mapstraction

/**
 * 统一位置数据模型
 */
data class MapLocation(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float = 0f,
    val speed: Float = 0f,
    val bearing: Float = 0f,
    val timestamp: Long = System.currentTimeMillis()
)
