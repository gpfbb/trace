package com.trackmaster.app.core

import com.trackmaster.app.Category
import com.trackmaster.app.data.GeoFence
import com.trackmaster.app.data.GeoFencePoint
import java.util.Date

/**
 * 旧 DB GeoFence ↔ 核心 Geofence 双向映射
 *
 * 职责：
 * - toCore：旧类型 → 核心类型（含 CIRCLE→32 边形转换）
 * - toOld：核心类型 → 旧类型（写回 DB 时使用）
 */
object FenceMapper {

    /** 旧 GeoFence 列表 → 核心 Geofence 列表 */
    fun toCoreList(fences: List<GeoFence>): List<Geofence> =
        fences.mapNotNull { toCore(it) }

    /** 单个旧 GeoFence → 核心 Geofence */
    fun toCore(old: GeoFence): Geofence? {
        val coreType = try {
            GeoFenceType.valueOf(old.type.name)
        } catch (_: Exception) {
            return null
        }

        val polygon = when (coreType) {
            GeoFenceType.POLYGON -> old.points?.map {
                GeoPoint(it.latitude, it.longitude)
            } ?: emptyList()
            GeoFenceType.CIRCLE -> {
                val center = old.center
                val radius = old.radius
                if (center != null && radius != null) {
                    GeoMath.circleToPolygon(
                        GeoPoint(center.latitude, center.longitude),
                        radius.toDouble(),
                        32,
                    )
                } else emptyList()
            }
        }

        return Geofence(
            id = old.id,
            name = old.name,
            type = coreType,
            polygon = polygon,
            bbox = if (polygon.isNotEmpty()) GeoMath.computeBBox(polygon) else null,
            center = old.center?.let { GeoPoint(it.latitude, it.longitude) },
            radius = old.radius?.toDouble(),
            category = if (old.categoryId != Category.UNASSIGNED_ID) old.categoryId else null,
            createdAt = old.createdAt,
        )
    }

    /** 核心 Geofence → 旧 GeoFence（写回 DB） */
    fun toOld(core: Geofence): GeoFence {
        return GeoFence(
            id = core.id,
            name = core.name,
            type = core.type,  // Same GeoFenceType enum, direct assignment
            center = core.center?.let { GeoFencePoint(it.lat, it.lng) },
            radius = core.radius?.toFloat(),
            points = when (core.type) {
                GeoFenceType.POLYGON -> core.polygon.map { GeoFencePoint(it.lat, it.lng) }
                GeoFenceType.CIRCLE -> null
            },
            categoryId = core.category ?: Category.UNASSIGNED_ID,
            createdAt = core.createdAt,
        )
    }
}
