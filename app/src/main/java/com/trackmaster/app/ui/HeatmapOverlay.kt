package com.trackmaster.app.ui

import android.graphics.Color as AndroidColor
import com.amap.api.maps.AMap
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.Polygon
import com.amap.api.maps.model.PolygonOptions
import com.trackmaster.app.core.DailySummary
import com.trackmaster.app.core.Geofence

/**
 * 热力图叠加层 — 围栏多边形按停留时长着色
 *
 * 将 DailySummary 的时长映射到围栏多边形的填充颜色，
 * 在 AMap 上形成热力图效果。
 */
class HeatmapOverlay(private val aMap: AMap) {

    private val overlays = mutableListOf<Polygon>()

    /**
     * 更新热力图覆盖层
     *
     * @param fences 核心围栏列表
     * @param summaries 当日汇总（含停留时长）
     * @param maxDurationMin 最大时长（用于归一化），0 = 自动计算
     */
    fun update(
        fences: List<Geofence>,
        summaries: List<DailySummary>,
        maxDurationMin: Int = 0,
    ) {
        clear()

        val durationMap = summaries.associate { it.geofenceId to it.spatialDurationMin }
        val max = if (maxDurationMin > 0) maxDurationMin
                  else durationMap.values.maxOrNull() ?: 1

        for (fence in fences) {
            val duration = durationMap[fence.id] ?: continue
            if (duration <= 0) continue

            val ratio = (duration.toFloat() / max).coerceIn(0f, 1f)
            val color = heatColor(ratio)

            val points = fence.polygon.map { LatLng(it.lat, it.lng) }
            if (points.size < 3) continue

            val polygon = aMap.addPolygon(
                PolygonOptions()
                    .addAll(points)
                    .fillColor(color)
                    .strokeColor(AndroidColor.argb(100, 255, 255, 255))
                    .strokeWidth(1f)
            )
            overlays.add(polygon)
        }
    }

    /** 清除所有覆盖层 */
    fun clear() {
        overlays.forEach { it.remove() }
        overlays.clear()
    }

    companion object {
        /** 将 0..1 映射为冷→暖色（紫色系，与红色围栏不冲突） */
        private fun heatColor(ratio: Float): Int {
            val alpha = (80 + 120 * ratio).toInt() // 80~200
            return when {
                ratio < 0.25f -> AndroidColor.argb(alpha, 156, 39, 176)   // 浅紫
                ratio < 0.5f -> AndroidColor.argb(alpha, 142, 36, 170)   // 中紫
                ratio < 0.75f -> AndroidColor.argb(alpha, 123, 31, 162)   // 深紫
                else -> AndroidColor.argb(alpha, 74, 20, 140)             // 极深紫
            }
        }
    }
}
