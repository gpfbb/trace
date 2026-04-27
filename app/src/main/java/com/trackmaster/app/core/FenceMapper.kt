package com.trackmaster.app.core

import com.trackmaster.app.data.GeoFence
import com.trackmaster.app.utils.geometry.CirclePolygonCache.circleToPolygon
import com.trackmaster.app.utils.geometry.computeBBox

/**
 * 为围栏补充引擎用字段（polygon 近似多边形 + bbox 包围盒）
 * 统一模型后，FenceMapper 的 toCore 逻辑移至此
 * @param fence data.GeoFence (UI/DB层)
 * @return core.Geofence (引擎层)
 */
fun buildEngineGeofence(fence: GeoFence): Geofence {
    val fenceType = fence.type ?: return Geofence(
        id = fence.id,
        name = fence.name,
        type = GeoFenceType.POLYGON,
        polygon = emptyList(),
        bbox = null,
        center = null,
        radius = null,
        createdAt = fence.createdAt,
    )
    val geoFenceType = try {
        GeoFenceType.valueOf(fenceType.name)
    } catch (_: Exception) {
        return Geofence(
            id = fence.id,
            name = fence.name,
            type = GeoFenceType.POLYGON,
            polygon = emptyList(),
            bbox = null,
            center = null,
            radius = null,
            createdAt = fence.createdAt,
        )
    }

    val polygon = when (geoFenceType) {
        GeoFenceType.POLYGON -> fence.points?.map {
            GeoPoint(it.latitude, it.longitude)
        } ?: emptyList()
        GeoFenceType.CIRCLE -> {
            val c = fence.center
            val r = fence.radius
            if (c != null && r != null) {
                circleToPolygon(GeoPoint(c.latitude, c.longitude), r.toDouble(), 32)
            } else emptyList()
        }
    }
    val bbox = if (polygon.isNotEmpty()) computeBBox(polygon) else null
    return Geofence(
        id = fence.id,
        name = fence.name,
        type = geoFenceType,
        polygon = polygon,
        bbox = bbox,
        center = fence.center?.let { GeoPoint(it.latitude, it.longitude) },
        radius = fence.radius?.toDouble(),
        category = if (fence.categoryId != "unassigned") fence.categoryId else null,
        createdAt = fence.createdAt,
    )
}
