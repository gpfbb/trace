package com.trackmaster.app.core

import java.util.Date
import java.util.UUID

// ============================================================
// 地理坐标
// ============================================================

/** 经纬度点 */
data class GeoPoint(val lat: Double, val lng: Double)

/** 预计算包围盒 [minLng, minLat, maxLng, maxLat] */
data class BBox(val minLng: Double, val minLat: Double, val maxLng: Double, val maxLat: Double)

// ============================================================
// 围栏定义
// ============================================================

enum class GeoFenceType { CIRCLE, POLYGON }

/**
 * 统一围栏定义
 *
 * 与 DB 的映射：
 * - CIRCLE → polygon 由 32 边形近似，保留 center/radius 供快速判断
 * - parentId / depth 由 SharedPreferences 管理（不改 DB schema）
 * - category 仅叶子围栏有效，父级为 null
 */
data class Geofence(
    val id: String,
    val name: String,
    val type: GeoFenceType,
    val parentId: String? = null,
    val depth: Int = 0,
    val category: String? = null,
    /** 多边形顶点（CIRCLE 时由 32 边形生成） */
    val polygon: List<GeoPoint> = emptyList(),
    /** 预计算包围盒 */
    val bbox: BBox? = null,
    /** CIRCLE 专用：中心点 */
    val center: GeoPoint? = null,
    /** CIRCLE 专用：半径（米） */
    val radius: Double? = null,
    val createdAt: Date = Date(),
    val enabled: Boolean = true,
)

// ============================================================
// 定位 / 传感器
// ============================================================

/** 定位更新 */
data class LocationFix(
    val lat: Double,
    val lng: Double,
    /** GPS 精度半径（米） */
    val accuracy: Float,
    /** 事件时间戳（Unix ms） */
    val timestamp: Long,
    /** 速度（米/秒），可为 null */
    val speed: Float? = null,
)

/** 加速度传感器数据 */
data class AccelerometerData(
    val x: Float,
    val y: Float,
    val z: Float,
    val timestamp: Long,
)

/** 活动类型 */
enum class ActivityType { STILL, WALKING, RUNNING, DRIVING, CYCLING, UNKNOWN }

/** 活动识别结果 */
data class ActivityResult(
    val type: ActivityType,
    val confidence: Int,
    val timestamp: Long,
)

// ============================================================
// 电源模式
// ============================================================

/** 电源模式 */
enum class PowerMode { BALANCED, POWERSAVE }

// ============================================================
// 停留片段
// ============================================================

enum class SegmentStatus { ACTIVE, COMPLETED, CROSSED_DAY }

/** 停留原始片段 */
data class StaySegment(
    val id: String = UUID.randomUUID().toString(),
    /** 命中的叶子围栏 ID */
    val leafGeofenceId: String,
    /**
     * 叶子围栏名称（写入时快照）。
     *
     * 围栏可能被改名或删除，历史记录必须保留当时的名称，
     * 因此在这里冗余存一份，而不是查询时再 join。
     * 为空时回落到 [leafGeofenceId]。
     */
    val leafGeofenceName: String? = null,
    /** 进入时间戳（Unix ms） */
    val startTs: Long,
    /** 离开时间戳（null = 仍在停留） */
    val endTs: Long? = null,
    val status: SegmentStatus = SegmentStatus.ACTIVE,
)
