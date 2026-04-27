package com.trackmaster.app.utils.geometry

import com.trackmaster.app.core.GeoPoint
import kotlin.math.cos
import kotlin.math.sin

/**
 * 圆形多边形缓存
 */
object CirclePolygonCache {

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
}
