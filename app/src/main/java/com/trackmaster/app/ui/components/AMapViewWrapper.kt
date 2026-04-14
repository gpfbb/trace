package com.trackmaster.app.ui.components

import android.graphics.Color
import android.os.Bundle
import android.util.Log
import androidx.compose.foundation.background
import com.amap.api.maps.MapView
import kotlin.math.sin
import kotlin.math.cos
import kotlin.math.sqrt
import kotlin.math.atan2
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as AndroidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import com.trackmaster.app.data.GeoFence
import com.trackmaster.app.data.GeoFencePoint
import com.trackmaster.app.core.GeoFenceType
import com.trackmaster.app.mapstraction.MapCircle
import com.trackmaster.app.mapstraction.MapLocation
import com.trackmaster.app.mapstraction.MapPolygon
import com.trackmaster.app.mapstraction.MapMarker
import com.trackmaster.app.mapstraction.MarkerIcon
import com.trackmaster.app.mapstraction.amap.AMapPresenter
import com.trackmaster.app.locationservice.Location

private const val TAG = "AMapViewWrapper"

// 判断点是否在围栏内部
private fun isLocationInsideFence(lat: Double, lng: Double, fence: GeoFence): Boolean {
    return when (fence.type) {
        GeoFenceType.CIRCLE -> {
            val center = fence.center ?: return false
            val radius = fence.radius ?: return false
            val distance = calculateDistance(lat, lng, center.latitude, center.longitude)
            distance <= radius
        }
        GeoFenceType.POLYGON -> {
            val points = fence.points ?: return false
            if (points.size < 3) return false
            isPointInPolygon(lat, lng, points)
        }
    }
}

// 计算两点之间的距离（米）
private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val R = 6371000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
    val c = 2 * atan2(sqrt(a), sqrt(1 - a))
    return R * c
}

// 判断点是否在多边形内部（射线法）
private fun isPointInPolygon(x: Double, y: Double, points: List<GeoFencePoint>): Boolean {
    var inside = false
    val n = points.size
    var j = n - 1

    for (i in 0 until n) {
        val xi = points[i].longitude
        val yi = points[i].latitude
        val xj = points[j].longitude
        val yj = points[j].latitude

        val intersect = ((yi > y) != (yj > y)) &&
                (x < (xj - xi) * (y - yi) / (yj - yi) + xi)
        if (intersect) inside = !inside
        j = i
    }
    return inside
}

@Composable
fun AMapViewWrapper(
    @Suppress("UNUSED_PARAMETER") modifier: Modifier = Modifier,
    currentLocation: Location? = null,
    fences: List<GeoFence> = emptyList(),
    currentMatchedFenceIds: Set<String> = emptySet(),
    highlightedFenceId: String? = null,
    displayedFenceId: String? = null,
    isDrawingMode: Boolean = false,
    drawingPoints: List<GeoFencePoint> = emptyList(),
    selectedFenceType: GeoFenceType = GeoFenceType.CIRCLE,
    isEditingFence: Boolean = false,
    editingPoints: List<GeoFencePoint> = emptyList(),
    onMapClick: (Double, Double) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // 创建 MapView 并传递给 AMapPresenter
    val mapView = remember { MapView(context) }
    val mapPresenter = remember { AMapPresenter(mapView, lifecycleOwner) }

    // 内部状态管理
    val tempMarkers = remember { mutableStateListOf<MarkerWrapper>() }
    var previewCircle by remember { mutableStateOf<CircleWrapper?>(null) }
    var previewPolygon by remember { mutableStateOf<PolygonWrapper?>(null) }
    val editingMarkers = remember { mutableStateListOf<MarkerWrapper>() }
    val displayedMarkers = remember { mutableStateListOf<MarkerWrapper>() }

    // 初始化地图
    var isMapReady by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        isMapReady = true
    }

    // 生命周期由 AMapPresenter 内部管理，地图点击监听
    LaunchedEffect(isMapReady) {
        if (!isMapReady) return@LaunchedEffect
        mapPresenter.setOnMapClickListener { lat, lng ->
            onMapClick(lat, lng)
        }
    }

    // 首次进入时自动定位到用户当前位置
    LaunchedEffect(currentLocation, isMapReady) {
        if (!isMapReady) return@LaunchedEffect
        if (currentLocation != null) {
            val lat = currentLocation.latitude
            val lng = currentLocation.longitude
            if (lat != 0.0 || lng != 0.0) {
                mapPresenter.moveCamera(lat, lng, 16f)
            }
        }
    }

    // 围栏覆盖物 - 使用 MapPresenter 管理
    LaunchedEffect(displayedFenceId, fences.map { it.id to it }.hashCode(), isMapReady) {
        if (!isMapReady) return@LaunchedEffect

        // 清理旧的覆盖物
        mapPresenter.clearAll()

        fences.forEach { fence ->
            val matched = fence.id in currentMatchedFenceIds
            val highlighted = fence.id == highlightedFenceId
            val displayed = fence.id == displayedFenceId

            val strokeColor = when {
                highlighted -> 0xFFFFFF00.toInt()
                displayed -> Color.parseColor(fence.color)
                matched -> 0xFF00FF00.toInt()
                else -> Color.parseColor(fence.color)
            }

            val alphaInt = (fence.alpha * 255).toInt()
            val fillColor = Color.argb(
                alphaInt,
                Color.red(Color.parseColor(fence.color)),
                Color.green(Color.parseColor(fence.color)),
                Color.blue(Color.parseColor(fence.color))
            )

            when (fence.type) {
                GeoFenceType.CIRCLE -> {
                    val center = fence.center ?: return@forEach
                    val radius = fence.radius ?: return@forEach
                    val circle = MapCircle(
                        id = fence.id,
                        center = MapLocation(center.latitude, center.longitude),
                        radius = radius.toDouble(),
                        fillColor = fillColor,
                        strokeColor = strokeColor,
                        strokeWidth = 3f
                    )
                    mapPresenter.addCircle(circle)
                }
                GeoFenceType.POLYGON -> {
                    val pts = fence.points ?: return@forEach
                    if (pts.size < 3) return@forEach
                    val polygon = MapPolygon(
                        id = fence.id,
                        points = pts.map { MapLocation(it.latitude, it.longitude) },
                        fillColor = fillColor,
                        strokeColor = strokeColor,
                        strokeWidth = 3f
                    )
                    mapPresenter.addPolygon(polygon)
                }
            }

            // 如果是灵动岛选中的围栏，添加所有点位标记
            if (displayed) {
                when (fence.type) {
                    GeoFenceType.CIRCLE -> {
                        val center = fence.center
                        if (center != null) {
                            val marker = MapMarker(
                                id = "${fence.id}_center",
                                position = MapLocation(center.latitude, center.longitude),
                                title = "${fence.name} (圆心)",
                                snippet = "半径: ${fence.radius}m",
                                icon = MarkerIcon.CENTER
                            )
                            mapPresenter.addMarker(marker)
                        }
                    }
                    GeoFenceType.POLYGON -> {
                        val pts = fence.points
                        if (pts != null) {
                            pts.forEachIndexed { idx, pt ->
                                val marker = MapMarker(
                                    id = "${fence.id}_pt_$idx",
                                    position = MapLocation(pt.latitude, pt.longitude),
                                    title = "${fence.name} 顶点${idx + 1}",
                                    snippet = "(${String.format("%.6f", pt.latitude)}, ${String.format("%.6f", pt.longitude)})",
                                    icon = MarkerIcon.DEFAULT
                                )
                                mapPresenter.addMarker(marker)
                            }
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    // 绘制预览
    LaunchedEffect(drawingPoints, isDrawingMode, selectedFenceType, isMapReady) {
        if (!isMapReady) return@LaunchedEffect

        // 清理预览
        tempMarkers.forEach { mapPresenter.removeMarker(it.id) }
        tempMarkers.clear()
        previewCircle?.let { mapPresenter.removeCircle(it.id) }
        previewCircle = null
        previewPolygon?.let { mapPresenter.removePolygon(it.id) }
        previewPolygon = null

        if (!isDrawingMode) return@LaunchedEffect

        // 添加绘制点标记
        drawingPoints.forEachIndexed { idx, pt ->
            val marker = MapMarker(
                id = "drawing_$idx",
                position = MapLocation(pt.latitude, pt.longitude),
                title = "点 ${idx + 1}",
                icon = MarkerIcon.DEFAULT
            )
            mapPresenter.addMarker(marker)
            tempMarkers.add(MarkerWrapper(marker.id, marker))
        }

        // 圆形围栏预览
        if (selectedFenceType == GeoFenceType.CIRCLE && drawingPoints.isNotEmpty()) {
            val center = drawingPoints.first()
            val circle = MapCircle(
                id = "preview_circle",
                center = MapLocation(center.latitude, center.longitude),
                radius = 100.0,
                fillColor = 0x20FF0000,
                strokeColor = 0x80FF0000.toInt(),
                strokeWidth = 2f
            )
            mapPresenter.addCircle(circle)
            previewCircle = CircleWrapper(circle.id, circle)
        }

        // 多边形围栏预览
        if (selectedFenceType == GeoFenceType.POLYGON && drawingPoints.size >= 3) {
            val polygon = MapPolygon(
                id = "preview_polygon",
                points = drawingPoints.map { MapLocation(it.latitude, it.longitude) },
                fillColor = 0x20FF0000,
                strokeColor = 0x80FF0000.toInt(),
                strokeWidth = 2f
            )
            mapPresenter.addPolygon(polygon)
            previewPolygon = PolygonWrapper(polygon.id, polygon)
        }
    }

    // 编辑模式
    LaunchedEffect(editingPoints, isEditingFence, isMapReady) {
        if (!isMapReady) return@LaunchedEffect

        editingMarkers.forEach { mapPresenter.removeMarker(it.id) }
        editingMarkers.clear()
        previewPolygon?.let { mapPresenter.removePolygon(it.id) }
        previewPolygon = null

        if (!isEditingFence) return@LaunchedEffect

        editingPoints.forEachIndexed { idx, pt ->
            val marker = MapMarker(
                id = "editing_$idx",
                position = MapLocation(pt.latitude, pt.longitude),
                title = "顶点 ${idx + 1}",
                icon = MarkerIcon.DEFAULT
            )
            mapPresenter.addMarker(marker)
            editingMarkers.add(MarkerWrapper(marker.id, marker))
        }

        if (editingPoints.size >= 3) {
            val polygon = MapPolygon(
                id = "editing_polygon",
                points = editingPoints.map { MapLocation(it.latitude, it.longitude) },
                fillColor = 0x40FFFF00,
                strokeColor = 0xFFFFFF00.toInt(),
                strokeWidth = 3f
            )
            mapPresenter.addPolygon(polygon)
            previewPolygon = PolygonWrapper(polygon.id, polygon)
        }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { ctx ->
                mapPresenter.onCreate(null)
                mapView
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}

// 内部包装类，用于追踪创建的覆盖物
private data class MarkerWrapper(val id: String, val marker: MapMarker)
private data class CircleWrapper(val id: String, val circle: MapCircle)
private data class PolygonWrapper(val id: String, val polygon: MapPolygon)

