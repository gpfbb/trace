package com.trackmaster.app.model

/**
 * 统一地理坐标点
 * 合并 data.GeoFencePoint 和 core.GeoPoint
 */
data class GeoPoint(
    val latitude: Double,
    val longitude: Double
) {
    companion object {
        /** 从 (lat, lng) 创建 */
        fun fromLatLng(lat: Double, lng: Double) = GeoPoint(lat, lng)
    }

    /** 兼容 core.GeoPoint 的 lat 属性 */
    val lat: Double get() = latitude

    /** 兼容 core.GeoPoint 的 lng 属性 */
    val lng: Double get() = longitude
}

/**
 * 预计算包围盒 [minLng, minLat, maxLng, maxLat]
 */
data class BBox(
    val minLng: Double,
    val minLat: Double,
    val maxLng: Double,
    val maxLat: Double
) {
    fun contains(point: GeoPoint): Boolean {
        return point.latitude in minLat..maxLat &&
                point.longitude in minLng..maxLng
    }
}

/**
 * 围栏类型枚举
 */
enum class GeoFenceType {
    CIRCLE,
    POLYGON
}
