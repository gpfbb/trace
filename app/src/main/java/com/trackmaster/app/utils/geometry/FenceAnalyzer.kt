package com.trackmaster.app.utils.geometry

import com.trackmaster.app.core.BBox
import com.trackmaster.app.core.Geofence
import com.trackmaster.app.core.GeoFenceType
import com.trackmaster.app.core.GeoPoint
import kotlin.math.cos

/**
 * 围栏相交检测结果
 */
data class FenceIntersection(
    val fenceA: Geofence,
    val fenceB: Geofence,
    val description: String
)

/**
 * 检测两个围栏的边界是否相交
 * 注意：此函数检测"边界交叉"（partial overlap），不是"包含关系"
 * @return true = 两围栏边界相交
 */
fun fencesIntersect(fenceA: Geofence, fenceB: Geofence): Boolean {
    return when {
        // 圆形 vs 圆形
        fenceA.type == GeoFenceType.CIRCLE && fenceB.type == GeoFenceType.CIRCLE -> {
            val cA = fenceA.center ?: return false
            val rA = fenceA.radius ?: return false
            val cB = fenceB.center ?: return false
            val rB = fenceB.radius ?: return false
            val dist = haversine(cA, cB)
            // 相交：两圆重叠但不是包含关系
            dist < rA + rB && dist > kotlin.math.abs(rA - rB)
        }
        // 多边形 vs 多边形
        fenceA.type == GeoFenceType.POLYGON && fenceB.type == GeoFenceType.POLYGON -> {
            val polyA = fenceA.polygon
            val polyB = fenceB.polygon
            if (polyA.size < 3 || polyB.size < 3) return false

            // 快速 BBox 预检
            val bboxA = computeBBox(polyA)
            val bboxB = computeBBox(polyB)
            if (!bboxesIntersect(bboxA, bboxB)) return false

            // 检测边是否相交
            val nA = polyA.size
            val nB = polyB.size
            for (i in 0 until nA) {
                val p1 = polyA[i]
                val q1 = polyA[(i + 1) % nA]
                for (j in 0 until nB) {
                    val p2 = polyB[j]
                    val q2 = polyB[(j + 1) % nB]
                    if (segmentsIntersect(p1, q1, p2, q2)) return true
                }
            }
            false
        }
        // 圆形 vs 多边形
        fenceA.type == GeoFenceType.CIRCLE && fenceB.type == GeoFenceType.POLYGON -> {
            circlePolygonIntersect(fenceA, fenceB)
        }
        // 多边形 vs 圆形
        fenceA.type == GeoFenceType.POLYGON && fenceB.type == GeoFenceType.CIRCLE -> {
            circlePolygonIntersect(fenceB, fenceA)
        }
        else -> false
    }
}

/**
 * 圆形与多边形边界是否相交
 */
private fun circlePolygonIntersect(circleFence: Geofence, polygonFence: Geofence): Boolean {
    val center = circleFence.center ?: return false
    val radius = circleFence.radius ?: return false
    val poly = polygonFence.polygon
    if (poly.size < 3) return false

    // 1. 圆心在多边形内部 → 边界一定相交（因为圆在多边形内部）
    if (pointInPolygon(center.lat, center.lng, poly)) return true

    // 2. 检测多边形边是否与圆相交
    val n = poly.size
    for (i in 0 until n) {
        val p1 = poly[i]
        val p2 = poly[(i + 1) % n]
        // 点到线段的距离
        val dist = distanceToSegment(center, p1, p2)
        if (dist <= radius) return true
    }
    return false
}

/**
 * BBox 是否相交
 */
private fun bboxesIntersect(a: BBox, b: BBox): Boolean {
    return a.minLng <= b.maxLng && a.maxLng >= b.minLng &&
           a.minLat <= b.maxLat && a.maxLat >= b.minLat
}

/**
 * 检测所有围栏之间的相交情况
 * @return 相交的围栏对列表
 */
fun detectFenceIntersections(fences: List<Geofence>): List<FenceIntersection> {
    val intersections = mutableListOf<FenceIntersection>()
    for (i in fences.indices) {
        for (j in i + 1 until fences.size) {
            val a = fences[i]
            val b = fences[j]
            if (fencesIntersect(a, b)) {
                val desc = when {
                    a.type == GeoFenceType.CIRCLE && b.type == GeoFenceType.CIRCLE ->
                        "${a.name}(圆形) 与 ${b.name}(圆形) 边界相交"
                    a.type == GeoFenceType.POLYGON && b.type == GeoFenceType.POLYGON ->
                        "${a.name}(多边形) 与 ${b.name}(多边形) 边界相交"
                    a.type == GeoFenceType.CIRCLE ->
                        "${a.name}(圆形) 与 ${b.name}(多边形) 边界相交"
                    else ->
                        "${a.name}(多边形) 与 ${b.name}(圆形) 边界相交"
                }
                intersections.add(FenceIntersection(a, b, desc))
            }
        }
    }
    return intersections
}

/**
 * 判断两个线段 (p1,q1) 和 (p2,q2) 是否相交
 * 使用方向性检测算法
 */
private fun segmentsIntersect(p1: GeoPoint, q1: GeoPoint, p2: GeoPoint, q2: GeoPoint): Boolean {
    // 计算三个点的方向：positive, negative, zero
    fun orientation(a: GeoPoint, b: GeoPoint, c: GeoPoint): Int {
        val val_ = (c.lat - a.lat) * (b.lng - a.lng) - (b.lat - a.lat) * (c.lng - a.lng)
        return when {
            val_ > 0 -> 1   // clockwise
            val_ < 0 -> 2   // counterclockwise
            else -> 0       // collinear
        }
    }

    val o1 = orientation(p1, q1, p2)
    val o2 = orientation(p1, q1, q2)
    val o3 = orientation(p2, q2, p1)
    val o4 = orientation(p2, q2, q1)

    // 一般情况：线段相交
    if (o1 != o2 && o3 != o4) return true

    // 特殊情况的共线检测（端点落在另一线段上）
    // p1, p2 共线且 p2 在 p1q1 上
    if (o1 == 0 && onSegment(p1, p2, q1)) return true
    // p1, q2 共线且 q2 在 p1q1 上
    if (o2 == 0 && onSegment(p1, q2, q1)) return true
    // p2, p1 共线且 p1 在 p2q2 上
    if (o3 == 0 && onSegment(p2, p1, q2)) return true
    // p2, q1 共线且 q1 在 p2q2 上
    if (o4 == 0 && onSegment(p2, q1, q2)) return true

    return false
}

/**
 * 判断点 p 是否在线段 (a, b) 上（假设共线）
 */
private fun onSegment(a: GeoPoint, p: GeoPoint, b: GeoPoint): Boolean {
    return p.lng <= maxOf(a.lng, b.lng) && p.lng >= minOf(a.lng, b.lng) &&
           p.lat <= maxOf(a.lat, b.lat) && p.lat >= minOf(a.lat, b.lat)
}

/**
 * 检查围栏inner是否完全包含在围栏outer内
 * 增强版：多边形之间会检测边界是否相交
 */
internal fun isFenceContainedByFence(inner: Geofence, outer: Geofence): Boolean {
    return when {
        // 圆形 vs 圆形
        inner.type == GeoFenceType.CIRCLE && outer.type == GeoFenceType.CIRCLE -> {
            val innerCenter = inner.center ?: return false
            val innerRadius = inner.radius ?: return false
            val outerCenter = outer.center ?: return false
            val outerRadius = outer.radius ?: return false
            val dist = haversine(innerCenter, outerCenter)
            dist + innerRadius <= outerRadius
        }
        // 多边形 vs 多边形 - 增强版：检测边界相交
        inner.type == GeoFenceType.POLYGON && outer.type == GeoFenceType.POLYGON -> {
            val innerPoly = inner.polygon
            val outerPoly = outer.polygon
            if (innerPoly.size < 3 || outerPoly.size < 3) return false

            // 1. 先用BBox快速判断：如果inner的BBox完全在outer的BBox内，继续精确检测
            val innerBBox = computeBBox(innerPoly)
            val outerBBox = computeBBox(outerPoly)
            if (!pointInBBox(innerBBox.minLat, innerBBox.minLng, outerBBox) ||
                !pointInBBox(innerBBox.maxLat, innerBBox.maxLng, outerBBox)) {
                // inner的BBox不在outer内，直接返回false
                return false
            }

            // 2. 检测两多边形边界是否有交叉（使用正确的闭合多边形边）
            val nInner = innerPoly.size
            val nOuter = outerPoly.size

            // 检查inner的每条边与outer的每条边是否相交
            for (i in 0 until nInner) {
                val p1 = innerPoly[i]
                val q1 = innerPoly[(i + 1) % nInner]
                for (j in 0 until nOuter) {
                    val p2 = outerPoly[j]
                    val q2 = outerPoly[(j + 1) % nOuter]
                    if (segmentsIntersect(p1, q1, p2, q2)) {
                        return false // 边界相交，不算包含
                    }
                }
            }

            // 3. 边界不相交，检测顶点位置关系
            // 如果内多边形所有顶点都在外多边形内，返回true（被外包含）
            // 注意：排除最后一个重复点（闭合多边形首尾相同）
            val innerToCheck = if (innerPoly.size > 1 &&
                innerPoly.first() == innerPoly.last()) innerPoly.dropLast(1) else innerPoly
            val outerToCheck = if (outerPoly.size > 1 &&
                outerPoly.first() == outerPoly.last()) outerPoly.dropLast(1) else outerPoly

            val innerAllInside = innerToCheck.all { pointInPolygon(it.lat, it.lng, outerToCheck) }
            if (innerAllInside) return true

            // 如果外多边形所有顶点都在内多边形内，说明内包含外，而非外包含内
            val outerAllInside = outerToCheck.all { pointInPolygon(it.lat, it.lng, innerToCheck) }
            if (outerAllInside) return false

            // 边界不相交且无包含关系
            return false
        }
        // 圆形 vs 多边形：圆心在内，且圆上4个方向最远点都在多边形内
        inner.type == GeoFenceType.CIRCLE && outer.type == GeoFenceType.POLYGON -> {
            val innerCenter = inner.center ?: return false
            val innerRadius = inner.radius ?: return false
            val outerPoly = outer.polygon
            if (outerPoly.size < 3) return false

            // 圆心必须在多边形内
            val centerInside = pointInPolygon(innerCenter.lat, innerCenter.lng, outerPoly)
            if (!centerInside) return false

            // 取圆上4个方向（N/E/S/W）的最远点，都必须在多边形内
            val dLat = innerRadius / 111_320.0
            val midLat = innerCenter.lat
            val cosMidLat = cos(midLat * DEG)
            val dLng = innerRadius / (111_320.0 * cosMidLat)

            val north = GeoPoint(innerCenter.lat + dLat, innerCenter.lng)
            val south = GeoPoint(innerCenter.lat - dLat, innerCenter.lng)
            val east = GeoPoint(innerCenter.lat, innerCenter.lng + dLng)
            val west = GeoPoint(innerCenter.lat, innerCenter.lng - dLng)

            pointInPolygon(north.lat, north.lng, outerPoly) &&
            pointInPolygon(south.lat, south.lng, outerPoly) &&
            pointInPolygon(east.lat, east.lng, outerPoly) &&
            pointInPolygon(west.lat, west.lng, outerPoly)
        }
        // 多边形 vs 圆形：多边形的所有顶点都在圆内
        inner.type == GeoFenceType.POLYGON && outer.type == GeoFenceType.CIRCLE -> {
            val innerPoly = inner.polygon
            val outerCenter = outer.center ?: return false
            val outerRadius = outer.radius ?: return false
            if (innerPoly.size < 3) return false
            // 所有顶点都在圆内
            innerPoly.all { pointInCircle(it, outerCenter, outerRadius) }
        }
        else -> false
    }
}
