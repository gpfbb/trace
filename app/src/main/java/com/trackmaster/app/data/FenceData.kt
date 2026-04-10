package com.trackmaster.app.data

import java.util.Date
import java.util.UUID

// ============================================================
// UI 层地理围栏数据模型
// 这些类用于 UI、数据库和 MainActivity
// 与 Engine 层的 Geofence (core/GeofenceModels.kt) 不同
// 注意：GeoFenceType 枚举使用 core 包中的定义
// ============================================================

/**
 * 地理围栏点
 */
data class GeoFencePoint(
    val latitude: Double,
    val longitude: Double
)

/**
 * 地理围栏 (UI 层)
 * 使用 core.GeoFenceType 枚举
 */
data class GeoFence(
    val id: String,
    val name: String,
    val type: com.trackmaster.app.core.GeoFenceType,
    val parentId: String? = null, // 父围栏ID，支持嵌套
    val center: GeoFencePoint? = null, // 圆形围栏的中心点
    val radius: Float? = null, // 圆形围栏的半径（米）
    val points: List<GeoFencePoint>? = null, // 多边形围栏的顶点
    val categoryId: String = "unassigned", // 所属分类ID
    val createdAt: Date = Date(),
    val color: String = "#FF0000", // 围栏颜色，默认红色
    val alpha: Float = 0.3f // 围栏透明度，默认30%
)

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
    val id: String? = null,  // 可选，导入时用于去重
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
