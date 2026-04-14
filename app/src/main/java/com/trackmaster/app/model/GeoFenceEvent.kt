package com.trackmaster.app.model

import java.util.Date
import java.util.UUID

/**
 * 地理围栏动作
 */
enum class GeoFenceAction {
    ENTER,
    EXIT,
    STAY
}

/**
 * 地理围栏事件
 */
data class GeoFenceEvent(
    val id: String = UUID.randomUUID().toString(),
    val fenceId: String,
    val fenceName: String,
    val action: GeoFenceAction,
    val time: Date,
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val durationSeconds: Long? = null
)

/**
 * 围栏停留记录
 */
data class FenceStayRecord(
    val id: String? = null,
    val fenceId: String,
    val fenceName: String,
    val enterTime: Date,
    val exitTime: Date? = null,
    val durationSeconds: Long? = null
)

/**
 * 每日围栏统计
 */
data class DailyFenceStats(
    val date: String,
    val fenceId: String,
    val fenceName: String,
    val totalStayDuration: Long,
    val enterCount: Int,
    val exitCount: Int
)
