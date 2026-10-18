package com.trackmaster.app.utils.geometry

import com.trackmaster.app.core.BBox
import com.trackmaster.app.core.Geofence
import com.trackmaster.app.core.GeoFenceType
import com.trackmaster.app.core.GeoPoint
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

internal const val TAG = "GeoMath"
internal const val EARTH_R = 6_371_000.0
internal const val DEG = Math.PI / 180.0

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

fun haversine(p1: GeoPoint, p2: GeoPoint): Double =
    haversine(p1.lat, p1.lng, p2.lat, p2.lng)

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
        val dLat = expandM / 111_320.0
        val midLat = (minLat + maxLat) / 2
        val dLng = expandM / (111_320.0 * cos(midLat * DEG))
        return BBox(minLng - dLng, minLat - dLat, maxLng + dLng, maxLat + dLat)
    }
    return BBox(minLng, minLat, maxLng, maxLat)
}

/** 检查点是否在 BBox 内 */
fun pointInBBox(lat: Double, lng: Double, bbox: BBox): Boolean =
    lng >= bbox.minLng && lng <= bbox.maxLng && lat >= bbox.minLat && lat <= bbox.maxLat

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

/**
 * 计算多边形面积（平方米）。
 *
 * 用等距圆柱投影把经纬度换算成米，再套标准鞋带公式：
 *
 * ```
 * x = λ · R · cos(φ_mean)      （经度方向，随纬度收缩）
 * y = φ · R                    （纬度方向）
 * A = ½ |Σ (x_i · y_{i+1} − x_{i+1} · y_i)|
 * ```
 *
 * 围栏尺度（几百米到几公里）下这个近似足够准确，误差远小于 GPS 本身。
 *
 * ## 之前的实现是错的
 *
 * 旧代码写的是 `Σ (Δλ · Δφ)`，也就是把每条边的经纬度增量相乘再累加。
 * 那不是鞋带公式：**对轴对齐的矩形，每条边要么 Δλ=0 要么 Δφ=0，
 * 乘积恒为 0，面积恒等于 0**。而 `MapScreen` 用这个面积挑"最小的匹配围栏"，
 * 于是只要同时命中圆和多边形，多边形永远以 0 胜出——不管它实际多大。
 */
private fun calculatePolygonArea(polygon: List<GeoPoint>): Double {
    if (polygon.size < 3) return 0.0

    var latSum = 0.0
    for (p in polygon) latSum += p.lat
    // 用平均纬度做经度收缩因子。同时 clamp 一下，避免在极点附近算出 0。
    val meanLatRad = (latSum / polygon.size) * DEG
    val metersPerRadLng = EARTH_R * cos(meanLatRad).coerceAtLeast(1e-6)

    var twiceArea = 0.0
    val n = polygon.size
    for (i in 0 until n) {
        val j = (i + 1) % n
        val p1 = polygon[i]
        val p2 = polygon[j]

        val x1 = p1.lng * DEG * metersPerRadLng
        val y1 = p1.lat * DEG * EARTH_R
        val x2 = p2.lng * DEG * metersPerRadLng
        val y2 = p2.lat * DEG * EARTH_R

        twiceArea += x1 * y2 - x2 * y1
    }

    return Math.abs(twiceArea) / 2.0
}

// ============================================================
// 点到线段距离计算
// ============================================================

/**
 * 计算点到线段的距离（米）
 * 使用投影法：找到点在线段上的投影点，然后计算到投影点的距离
 */
fun distanceToSegment(point: GeoPoint, segStart: GeoPoint, segEnd: GeoPoint): Double {
    val segLat = segEnd.lat - segStart.lat
    val segLng = segEnd.lng - segStart.lng
    val pointLat = point.lat - segStart.lat
    val pointLng = point.lng - segStart.lng

    val segLengthSq = segLat * segLat + segLng * segLng
    if (segLengthSq == 0.0) {
        return haversine(point, segStart)
    }

    val t = ((pointLat * segLat + pointLng * segLng) / segLengthSq).coerceIn(0.0, 1.0)
    val projection = GeoPoint(
        segStart.lat + t * segLat,
        segStart.lng + t * segLng
    )

    return haversine(point, projection)
}

/**
 * 计算点到多边形边界的最小距离（米）
 * 返回值：
 * - 正数 = 点在多边形外部，距离边界的距离
 * - 负数 = 点在多边形内部，距离边界的距离
 */
fun distanceToPolygonBoundary(point: GeoPoint, polygon: List<GeoPoint>): Double {
    val n = polygon.size
    if (n < 3) return Double.POSITIVE_INFINITY

    var minDistance = Double.POSITIVE_INFINITY
    var j = n - 1

    for (i in 0 until n) {
        val distance = distanceToSegment(point, polygon[i], polygon[j])
        if (distance < minDistance) {
            minDistance = distance
        }
        j = i
    }

    val inside = pointInPolygon(point.lat, point.lng, polygon)
    return if (inside) -minDistance else minDistance
}

/**
 * 计算点到围栏边界的最小距离（米）
 * 返回值：
 * - 正数 = 点在围栏外部，距离边界的距离
 * - 负数 = 点在围栏内部，距离边界的距离
 */
fun distanceToFenceBoundary(point: GeoPoint, fence: Geofence): Double {
    return when (fence.type) {
        GeoFenceType.CIRCLE -> {
            val center = fence.center ?: return Double.POSITIVE_INFINITY
            val radius = fence.radius ?: return Double.POSITIVE_INFINITY
            val distance = haversine(point, center)
            distance - radius
        }
        GeoFenceType.POLYGON -> {
            distanceToPolygonBoundary(point, fence.polygon)
        }
    }
}
