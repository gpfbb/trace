package com.trackmaster.app.architecture.common.utils

import com.trackmaster.app.architecture.domain.models.*
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 地理计算工具集
 */
object GeoMath {

    private const val EARTH_R = 6_371_000.0
    private const val DEG = Math.PI / 180.0

    // ============================================================
    // 距离
    // ============================================================

    /** Haversine 公式计算两点间距离（米） */
    fun haversine(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = (lat2 - lat1) * DEG
        val dLng = (lng2 - lng1) * DEG
        val r1 = lat1 * DEG
        val r2 = lat2 * DEG
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(r1) * cos(r2) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * EARTH_R * atan2(sqrt(a), sqrt(1 - a))
    }

    fun haversine(p1: GeoPoint, p2: GeoPoint): Double = haversine(p1.lat, p1.lng, p2.lat, p2.lng)

    // ============================================================
    // BBox
    // ============================================================

    /** 计算多边形包围盒，可向外扩展 expandM 米 */
    fun computeBBox(polygon: List<GeoPoint>, expandM: Double = 0.0): BBox {
        var minLng = Double.MAX_VALUE
        var minLat = Double.MAX_VALUE
        var maxLng = -Double.MAX_VALUE
        var maxLat = -Double.MAX_VALUE
        for (p in polygon) {
            if (p.lng < minLng) minLng = p.lng
            if (p.lng > maxLng) maxLng = p.lng
            if (p.lat < minLat) minLat = p.lat
            if (p.lat > maxLat) maxLat = p.lat
        }
        if (expandM > 0) {
            val deltaLat = expandM / 111_320.0
            val midLat = (minLat + maxLat) / 2
            val deltaLng = expandM / (111_320.0 * cos(midLat * DEG))
            return BBox(minLng - deltaLng, minLat - deltaLat, maxLng + deltaLng, maxLat + deltaLat)
        }
        return BBox(minLng, minLat, maxLng, maxLat)
    }

    /** 检查点是否在 BBox 内 */
    fun pointInBBox(lat: Double, lng: Double, bbox: BBox): Boolean = 
        lng >= bbox.minLng && lng <= bbox.maxLng && lat >= bbox.minLat && lat <= bbox.maxLat

    /** 将 CIRCLE 转为近似多边形（32 边形） */
    fun circleToPolygon(center: GeoPoint, radiusM: Double, sides: Int = 32): List<GeoPoint> {
        val dLat = radiusM / 111_320.0
        val dLng = radiusM / (111_320.0 * cos(center.lat * DEG))
        val points = mutableListOf<GeoPoint>()
        for (i in 0 until sides) {
            val angle = (i.toDouble() / sides) * Math.PI * 2
            points.add(GeoPoint(
                center.lat + dLat * sin(angle),
                center.lng + dLng * cos(angle),
            ))
        }
        // 闭合多边形：首尾相连
        points.add(points[0])
        return points
    }

    // ============================================================
    // Point-in-Polygon（射线法）
    // ============================================================

    /**
     * 射线法判断点是否在多边形内部
     */
    fun pointInPolygon(lat: Double, lng: Double, polygon: List<GeoPoint>): Boolean {
        var inside = false
        val n = polygon.size
        var j = n - 1
        for (i in 0 until n) {
            val yi = polygon[i].lat
            val xi = polygon[i].lng
            val yj = polygon[j].lat
            val xj = polygon[j].lng
            if ((yi > lat) != (yj > lat) &&
                lng < (xj - xi) * (lat - yi) / (yj - yi) + xi) {
                inside = !inside
            }
            j = i
        }
        return inside
    }

    /** 判断点是否在圆形围栏内 */
    fun pointInCircle(point: GeoPoint, center: GeoPoint, radiusM: Double): Boolean = 
        haversine(point, center) <= radiusM

    /** 统一判断点是否在围栏内（自动区分 CIRCLE / POLYGON） */
    fun pointInFence(point: GeoPoint, fence: Geofence): Boolean {
        return when (fence.type) {
            GeoFenceType.CIRCLE -> {
                val c = fence.center ?: return false
                val r = fence.radius ?: return false
                pointInCircle(point, c, r)
            }
            GeoFenceType.POLYGON -> {
                if (fence.polygon.size < 3) return false
                pointInPolygon(point.lat, point.lng, fence.polygon)
            }
        }
    }

    /** 计算围栏面积（平方米） */
    fun calculateFenceArea(fence: Geofence): Double {
        return when (fence.type) {
            GeoFenceType.CIRCLE -> {
                val r = fence.radius ?: 0.0
                Math.PI * r * r
            }
            GeoFenceType.POLYGON -> {
                calculatePolygonArea(fence.polygon)
            }
        }
    }

    /** 计算多边形面积（平方米），使用 shoelace 公式 */
    private fun calculatePolygonArea(polygon: List<GeoPoint>): Double {
        if (polygon.size < 3) return 0.0

        var area = 0.0
        val n = polygon.size

        for (i in 0 until n) {
            val j = (i + 1) % n
            val p1 = polygon[i]
            val p2 = polygon[j]

            val midLat = (p1.lat + p2.lat) / 2
            val latRad = midLat * DEG

            val dLng = (p2.lng - p1.lng) * DEG

            area += dLng * EARTH_R * EARTH_R * cos(latRad)
        }

        return Math.abs(area) / 2
    }

    /** 为围栏列表填充 depth 字段 */
    fun populateDepth(fences: List<Geofence>): List<Geofence> {
        val map = fences.associateBy { it.id }
        return fences.map { it.copy(depth = getFenceDepth(it.id, map)) }
    }

    /** 获取围栏深度（0 = 顶级） */
    private fun getFenceDepth(fenceId: String, fenceMap: Map<String, Geofence>): Int {
        var d = 0
        var cur = fenceMap[fenceId]
        while (cur?.parentId != null) {
            d++
            cur = fenceMap[cur.parentId]
        }
        return d
    }
}