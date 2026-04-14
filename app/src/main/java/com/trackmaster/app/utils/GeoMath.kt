package com.trackmaster.app.utils

import android.util.Log
import com.trackmaster.app.core.BBox
import com.trackmaster.app.core.Geofence
import com.trackmaster.app.core.GeoFenceType
import com.trackmaster.app.core.GeoPoint
import com.trackmaster.app.data.GeoFence
import com.trackmaster.app.data.GeoFencePoint
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.pow

/**
 * 地理计算工具集
 *
 * 合并原 MainActivity 和 TrackMasterApplication 中重复的地理算法：
 * - haversine 距离
 * - BBox 计算与快速过滤
 * - 射线法 point-in-polygon
 * - 圆形围栏判断
 * - 围栏树构建
 */
internal const val TAG = "GeoMath"

object GeoMath {

    private const val EARTH_R = 6_371_000.0
    private const val DEG = Math.PI / 180.0

    // ============================================================
    // 围栏检测结果状态
    // ============================================================

    /**
     * 围栏检测结果状态
     */
    enum class FenceState {
        INSIDE,
        OUTSIDE,
        OUTER_BUFFER,
        INNER_BUFFER
    }

    /**
     * 内/外 Buffer 区的滞回检测配置
     * 更精细的控制，支持内/外不同的缓冲区大小
     */
    data class HysteresisConfig(
        val outerEnterM: Double = 5.0,
        val outerExitM: Double = 10.0,
        val innerEnterM: Double = 5.0,
        val innerExitM: Double = 10.0
    )

    /**
     * 基于定位精度动态计算缓冲区大小
     * @param accuracyM 定位精度（米）
     * @param baseBufferM 基础缓冲区大小（米）
     * @param accuracyMultiplier 精度倍率（默认 1.0）
     * @return 动态调整后的缓冲区大小
     */
    fun computeDynamicBuffer(
        accuracyM: Double,
        baseBufferM: Double = 10.0,
        accuracyMultiplier: Double = 1.0
    ): Double {
        return baseBufferM + (accuracyM * accuracyMultiplier).coerceIn(0.0, 50.0)
    }

    /**
     * 基于定位精度动态计算滞回配置
     * @param accuracyM 定位精度（米）
     * @param baseConfig 基础配置
     * @param accuracyMultiplier 精度倍率（默认 1.0）
     * @return 动态调整后的滞回配置
     */
    fun computeDynamicHysteresisConfig(
        accuracyM: Double,
        baseConfig: HysteresisConfig = HysteresisConfig(),
        accuracyMultiplier: Double = 1.0
    ): HysteresisConfig {
        val dynamicAdjustment = (accuracyM * accuracyMultiplier).coerceIn(0.0, 30.0)
        
        return HysteresisConfig(
            outerEnterM = baseConfig.outerEnterM + dynamicAdjustment * 0.5,
            outerExitM = baseConfig.outerExitM + dynamicAdjustment,
            innerEnterM = baseConfig.innerEnterM + dynamicAdjustment * 0.5,
            innerExitM = baseConfig.innerExitM + dynamicAdjustment
        )
    }

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
    // 圆形多边形缓存
    // ============================================================

    /** 圆形多边形缓存，键格式: "lat,lng,radius,sides" */
    private val circlePolygonCache = mutableMapOf<String, List<GeoPoint>>()
    private var cacheHits = 0L
    private var cacheMisses = 0L

    /** 生成缓存键 */
    private fun circlePolygonCacheKey(center: GeoPoint, radiusM: Double, sides: Int): String {
        return "${center.lat},${center.lng},$radiusM,$sides"
    }

    /** 清除圆形多边形缓存 */
    fun clearCirclePolygonCache() {
        synchronized(circlePolygonCache) {
            circlePolygonCache.clear()
            cacheHits = 0L
            cacheMisses = 0L
        }
    }

    /** 获取当前缓存大小 */
    fun getCirclePolygonCacheSize(): Int = circlePolygonCache.size

    /** 获取缓存命中率统计 */
    data class CacheStats(
        val hits: Long,
        val misses: Long,
        val totalRequests: Long,
        val hitRate: Double,
        val cacheSize: Int
    )

    /** 获取缓存统计信息 */
    fun getCacheStats(): CacheStats = synchronized(circlePolygonCache) {
        val total = cacheHits + cacheMisses
        val hitRate = if (total > 0) cacheHits.toDouble() / total else 0.0
        CacheStats(
            hits = cacheHits,
            misses = cacheMisses,
            totalRequests = total,
            hitRate = hitRate,
            cacheSize = circlePolygonCache.size
        )
    }

    /** 将 CIRCLE 转为近似多边形（32 边形） */
    fun circleToPolygon(center: GeoPoint, radiusM: Double, sides: Int = 32): List<GeoPoint> {
        val cacheKey = circlePolygonCacheKey(center, radiusM, sides)

        // 同步计算并缓存（包含双检查）
        return synchronized(circlePolygonCache) {
            // 双检查：先检查缓存是否已存在
            circlePolygonCache[cacheKey]?.let {
                cacheHits++
                return@synchronized it
            }

            cacheMisses++

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

            val result = points.toList() // 转为不可变列表
            circlePolygonCache[cacheKey] = result
            result
        }
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
            val dLat = (p2.lat - p1.lat) * DEG

            area += dLng * EARTH_R * EARTH_R * cos(latRad) * dLat
        }

        return Math.abs(area) / 2
    }

    // ============================================================
    // 围栏树
    // ============================================================

    /** 获取围栏深度（0 = 顶级，基于parentId） */
    fun getFenceDepth(fenceId: String, fenceMap: Map<String, Geofence>): Int {
        var d = 0
        var cur = fenceMap[fenceId]
        while (cur?.parentId != null) {
            d++
            cur = fenceMap[cur.parentId]
        }
        return d
    }

    /** 为围栏列表填充 depth 字段 - 自动计算嵌套关系 */
    fun populateDepth(fences: List<Geofence>): List<Geofence> {
        if (fences.isEmpty()) return emptyList()

        // 1. 首先用 parentId 计算已有的深度
        val map = fences.associateBy { it.id }
        val depthByParent = fences.associate { it.id to getFenceDepth(it.id, map) }

        // 2. 自动计算几何嵌套：检查每个围栏是否被其他围栏包含
        // 如果围栏A包含围栏B，则B的深度 = A的深度 + 1
        val containmentDepth = calculateContainmentDepth(fences)

        // 3. 取两者的最大值（parentId深度 和 几何嵌套深度）
        // 几何嵌套优先级更高，因为用户可能没设置parent但围栏确实嵌套
        return fences.map { fence ->
            val parentDepth = depthByParent[fence.id] ?: 0
            val containDepth = containmentDepth[fence.id] ?: 0
            // 如果几何嵌套深度更大，覆盖parentId的深度
            val finalDepth = if (containDepth > parentDepth) containDepth else parentDepth
            fence.copy(depth = finalDepth)
        }
    }

    /**
     * 基于几何包含关系计算围栏深度
     * 如果围栏A包含围栏B，则B的深度 = A的深度 + 1
     * 叶子围栏（不被任何围栏包含）深度最大
     */
    private fun calculateContainmentDepth(fences: List<Geofence>): Map<String, Int> {
        if (fences.isEmpty()) return emptyMap()

        val result = mutableMapOf<String, Int>()

        // 初始化：所有围栏深度为0
        fences.forEach { result[it.id] = 0 }

        // 比较每对围栏，检查是否有包含关系
        for (fenceA in fences) {
            for (fenceB in fences) {
                if (fenceA.id == fenceB.id) continue

                // 检查 B 是否在 A 内部
                if (isFenceContainedByFence(fenceB, fenceA)) {
                    // B 在 A 内部，所以 B 的深度应该 > A 的深度
                    val currentBDepth = result[fenceB.id] ?: 0
                    val currentADepth = result[fenceA.id] ?: 0
                    // B 的深度至少是 A 的深度 + 1
                    result[fenceB.id] = maxOf(currentBDepth, currentADepth + 1)
                }
            }
        }

        return result
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

                // 如果外多边形所有顶点都在内多边形内，返回true（外包含内）
                val outerAllInside = outerToCheck.all { pointInPolygon(it.lat, it.lng, innerToCheck) }
                if (outerAllInside) return true

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

    // ============================================================
    // 围栏相交检测
    // ============================================================

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

    /**
     * 带 Buffer 区的围栏检测
     * @param bufferM 缓冲区大小（米）
     * @return 围栏状态
     */
    fun pointInFenceWithBuffer(point: GeoPoint, fence: Geofence, bufferM: Double): FenceState {
        val distance = distanceToFenceBoundary(point, fence)

        return when {
            distance < -bufferM -> FenceState.INSIDE
            distance in -bufferM..0.0 -> FenceState.INNER_BUFFER
            distance in 0.0..bufferM -> FenceState.OUTER_BUFFER
            else -> FenceState.OUTSIDE
        }
    }

    /**
     * 扩展的 pointInFence 函数，支持 Buffer 区
     * @param bufferM 缓冲区大小（米）
     * @param considerBufferAsInside 是否将缓冲区视为"内部"
     */
    fun pointInFence(
        point: GeoPoint,
        fence: Geofence,
        bufferM: Double = 0.0,
        considerBufferAsInside: Boolean = false
    ): Boolean {
        if (bufferM <= 0.0) {
            return pointInFence(point, fence)
        }

        val state = pointInFenceWithBuffer(point, fence, bufferM)

        return if (considerBufferAsInside) {
            state == FenceState.INSIDE || 
            state == FenceState.INNER_BUFFER || 
            state == FenceState.OUTER_BUFFER
        } else {
            state == FenceState.INSIDE
        }
    }

    /**
     * 带滞回机制（Hysteresis）的围栏检测器
     * 避免边界抖动：进入缓冲区需要更小的距离，离开需要更大的距离
     * 
     * @param enterBufferM 进入时的缓冲区（较小）
     * @param exitBufferM 离开时的缓冲区（较大，应 > enterBufferM）
     * @param previousState 前一次的检测状态
     */
    fun pointInFenceWithHysteresis(
        point: GeoPoint,
        fence: Geofence,
        enterBufferM: Double,
        exitBufferM: Double,
        previousState: Boolean
    ): Boolean {
        require(exitBufferM >= enterBufferM) { 
            "exitBufferM must be >= enterBufferM for hysteresis to work" 
        }

        val distance = distanceToFenceBoundary(point, fence)

        return if (previousState) {
            distance <= exitBufferM
        } else {
            distance <= enterBufferM
        }
    }

    /**
     * 精细化的滞回检测，支持内外缓冲区独立配置
     */
    fun pointInFenceWithInnerOuterHysteresis(
        point: GeoPoint,
        fence: Geofence,
        config: HysteresisConfig = HysteresisConfig(),
        previousState: FenceState = FenceState.OUTSIDE
    ): FenceState {
        val distance = distanceToFenceBoundary(point, fence)

        return when (previousState) {
            FenceState.OUTSIDE -> {
                when {
                    distance <= -config.outerEnterM -> FenceState.INSIDE
                    distance <= config.outerEnterM -> FenceState.OUTER_BUFFER
                    else -> FenceState.OUTSIDE
                }
            }
            FenceState.OUTER_BUFFER -> {
                when {
                    distance <= -config.outerEnterM -> FenceState.INSIDE
                    distance > config.outerExitM -> FenceState.OUTSIDE
                    else -> FenceState.OUTER_BUFFER
                }
            }
            FenceState.INNER_BUFFER -> {
                when {
                    distance <= -config.innerExitM -> FenceState.INSIDE
                    distance > config.innerExitM -> FenceState.OUTSIDE
                    else -> FenceState.INNER_BUFFER
                }
            }
            FenceState.INSIDE -> {
                when {
                    distance > config.innerExitM -> FenceState.OUTSIDE
                    distance > 0.0 -> FenceState.INNER_BUFFER
                    distance > -config.innerEnterM -> FenceState.INNER_BUFFER
                    else -> FenceState.INSIDE
                }
            }
        }
    }

    /**
     * 为围栏补充引擎用字段（polygon 近似多边形 + bbox 包围盒）
     * 统一模型后，FenceMapper 的 toCore 逻辑移至此
     * @param fence data.GeoFence (UI/DB层)
     * @return core.Geofence (引擎层)
     */
    fun buildEngineGeofence(fence: GeoFence): Geofence {
        val geoFenceType = try {
            GeoFenceType.valueOf(fence.type.name)
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
}

/**
 * 围栏树构建结果
 */
data class FenceTreeResult(
    val fenceMap: Map<String, Geofence>,
    val childrenMap: Map<String, List<Geofence>>,
    val leafFences: List<Geofence>,
    /** 按深度降序排列的叶子围栏 */
    val leavesByDepth: List<Geofence>,
)

/**
 * 验证围栏树的完整性
 * @return 验证结果，包含错误列表
 */
data class TreeValidationResult(
    val isValid: Boolean,
    val errors: List<String> = emptyList()
)

/** 验证围栏树完整性 */
fun validateFenceTree(fences: List<Geofence>): TreeValidationResult {
    val errors = mutableListOf<String>()
    val fenceMap = fences.associateBy { it.id }

    // 检查循环引用
    for (fence in fences) {
        val visited = mutableSetOf<String>()
        var currentId: String? = fence.id

        // 沿着父链向上检查
        while (currentId != null) {
            if (!visited.add(currentId)) {
                errors.add("循环引用检测到: 围栏 $currentId 在父链中重复出现")
                break
            }

            val currentFence = fenceMap[currentId]
            if (currentFence == null) {
                errors.add("引用不存在的围栏: $currentId")
                break
            }

            currentId = currentFence.parentId
        }

        // 检查父级是否存在（如果指定了父级）
        if (fence.parentId != null && fenceMap[fence.parentId] == null) {
            errors.add("围栏 ${fence.id} 引用了不存在的父级 ${fence.parentId}")
        }

        // 检查自引用
        if (fence.parentId == fence.id) {
            errors.add("围栏 ${fence.id} 引用了自身作为父级")
        }
    }

    // 检查深度一致性
    val depths = mutableMapOf<String, Int>()
    fun calculateDepth(fenceId: String): Int {
        if (fenceId in depths) return depths[fenceId]!!

        val fence = fenceMap[fenceId] ?: return 0
        if (fence.parentId == null) {
            depths[fenceId] = 0
            return 0
        }

        val parentDepth = calculateDepth(fence.parentId)
        val depth = parentDepth + 1
        depths[fenceId] = depth
        return depth
    }

    for (fence in fences) {
        calculateDepth(fence.id)
    }

    // 检查深度字段是否与计算一致
    for (fence in fences) {
        val calculatedDepth = depths[fence.id] ?: 0
        if (fence.depth != calculatedDepth) {
            errors.add("围栏 ${fence.id} 的深度字段不一致: 存储=${fence.depth}, 计算=$calculatedDepth")
        }
    }

    return TreeValidationResult(errors.isEmpty(), errors)
}

/** 构建围栏父子映射 */
fun buildFenceTree(fences: List<Geofence>, validate: Boolean = true): FenceTreeResult {
    val startTime = System.currentTimeMillis()

    // 可选验证
    if (validate) {
        val validation = validateFenceTree(fences)
        if (!validation.isValid) {
            Log.w(TAG, "围栏树验证失败，但继续构建: ${validation.errors.joinToString(", ")}")
        } else {
            Log.d(TAG, "围栏树验证通过: ${fences.size} 个围栏")
        }
    }

    val fenceMap = fences.associateBy { it.id }
    val childrenMap = mutableMapOf<String, MutableList<Geofence>>()

    for (f in fences) {
        val pid = f.parentId
        if (pid != null) {
            childrenMap.getOrPut(pid) { mutableListOf() }.add(f)
        }
    }

    // 叶子 = 没有子级的围栏
    val leafFences = fences.filter { it.id !in childrenMap }
    val leavesByDepth = leafFences.sortedByDescending { it.depth }

    // 收集统计信息
    val maxDepth = leavesByDepth.firstOrNull()?.depth ?: 0
    val avgChildren = if (childrenMap.isNotEmpty()) {
        childrenMap.values.sumOf { it.size }.toDouble() / childrenMap.size
    } else 0.0
    val leafCount = leafFences.size
    val totalWithParent = fences.count { it.parentId != null }

    val buildTime = System.currentTimeMillis() - startTime

    Log.d(TAG, "围栏树构建完成: 总数=${fences.size}, 叶子=$leafCount, 最大深度=$maxDepth, 平均子节点数=${"%.2f".format(avgChildren)}, 有父级的围栏=$totalWithParent, 耗时=${buildTime}ms")

    return FenceTreeResult(
        fenceMap = fenceMap,
        childrenMap = childrenMap,
        leafFences = leafFences,
        leavesByDepth = leavesByDepth,
    )
}
