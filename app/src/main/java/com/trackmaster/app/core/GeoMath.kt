package com.trackmaster.app.core

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
            val dLat = (p2.lat - p1.lat) * DEG

            area += dLng * EARTH_R * EARTH_R * cos(latRad) * dLat
        }

        return Math.abs(area) / 2
    }

    // ============================================================
    // 围栏树
    // ============================================================

    /** 获取围栏深度（0 = 顶级） */
    fun getFenceDepth(fenceId: String, fenceMap: Map<String, Geofence>): Int {
        var d = 0
        var cur = fenceMap[fenceId]
        while (cur?.parentId != null) {
            d++
            cur = fenceMap[cur.parentId]
        }
        return d
    }

    /** 为围栏列表填充 depth 字段 */
    fun populateDepth(fences: List<Geofence>): List<Geofence> {
        val map = fences.associateBy { it.id }
        return fences.map { it.copy(depth = getFenceDepth(it.id, map)) }
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

/** 构建围栏父子映射 */
fun buildFenceTree(fences: List<Geofence>): FenceTreeResult {
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

    return FenceTreeResult(
        fenceMap = fenceMap,
        childrenMap = childrenMap,
        leafFences = leafFences,
        leavesByDepth = leavesByDepth,
    )
}
