package com.trackmaster.app.ui.components

import android.graphics.Color
import android.os.Bundle
import com.trackmaster.app.utils.AppLog
import androidx.compose.foundation.Canvas
import com.amap.api.maps.MapView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.take
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as AndroidColor
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import com.trackmaster.app.AppPreferences
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
import com.trackmaster.app.ui.theme.NeonAmber

private const val TAG = "AMapViewWrapper"

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
    isPrecisionMode: Boolean = false,
    mapType: Int = 1,
    onMapClick: (Double, Double) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val appPrefs = remember { AppPreferences(context) }

    // 创建 MapView 并传递给 AMapPresenter
    val mapView = remember { MapView(context) }
    val mapPresenter = remember { AMapPresenter(mapView, lifecycleOwner) }

    // 内部状态管理
    val tempMarkers = remember { mutableListOf<MarkerWrapper>() }
    var previewCircle by remember { mutableStateOf<CircleWrapper?>(null) }
    var previewPolygon by remember { mutableStateOf<PolygonWrapper?>(null) }
    val editingMarkers = remember { mutableListOf<MarkerWrapper>() }

    // 初始化地图
    // 【P0修复】之前立即设置isMapReady=true，所有LaunchedEffect在地图未实际就绪时就执行
    // AMap.onMapLoadedListener在地图加载完成时触发一次，但Compose重组时不会重触发
    // 改用100ms延迟确保Presenter.setupMap()完成（Presenter在init时就调用setupMap）
    var isMapReady by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(100)
        isMapReady = true
    }

    // 退出时保存相机位置
    DisposableEffect(Unit) {
        onDispose {
            try {
                mapPresenter.getCameraCenter()?.let { (lat, lng) ->
                    appPrefs.cameraLatitude = lat
                    appPrefs.cameraLongitude = lng
                    appPrefs.cameraZoom = mapPresenter.getZoom() ?: 15f
                    appPrefs.hasCameraPosition = true
                }
            } catch (e: Exception) { AppLog.e(TAG, "保存相机位置失败", e) }
        }
    }

    // 相机移动时实时保存位置（AMapViewWrapper 进入时判断 hasCameraPosition）
    // 【Bug修复 H10】合并两个相机监听器为一个，避免重复注册
    // 原代码：第一个 LaunchedEffect(isMapReady) 和第二个 LaunchedEffect(isPrecisionMode, isMapReady)
    // 都调用 setOnCameraCenterChangeListener，导致监听器累积
    // 合并后统一由一个 LaunchedEffect(isMapReady) 处理precision模式的切换
    LaunchedEffect(isMapReady, isPrecisionMode) {
        if (!isMapReady) return@LaunchedEffect
        mapPresenter.setOnCameraCenterChangeListener { lat, lng ->
            try {
                appPrefs.cameraLatitude = lat
                appPrefs.cameraLongitude = lng
                appPrefs.cameraZoom = mapPresenter.getZoom() ?: 15f
                appPrefs.hasCameraPosition = true
            } catch (e: Exception) { AppLog.e(TAG, "保存相机位置失败", e) }
        }
    }


    // 每次进入地图页：等 currentLocation 到达后跳转（reactive，无硬延迟）
    LaunchedEffect(isMapReady) {
        if (!isMapReady) return@LaunchedEffect
        snapshotFlow { currentLocation }
            .filterNotNull()
            .take(1)
            .collect { loc ->
                mapPresenter.showLocation(MapLocation(loc.latitude, loc.longitude))
            }
    }

    // 地图类型切换
    LaunchedEffect(mapType, isMapReady) {
        if (!isMapReady) return@LaunchedEffect
        mapPresenter.configureMap(mapType = mapType)
    }

    // 地图点击监听: 精确模式下使用相机中心点
    LaunchedEffect(isPrecisionMode, isMapReady) {
        if (!isMapReady) return@LaunchedEffect
        mapPresenter.setOnMapClickListener { lat, lng ->
            if (isPrecisionMode) {
                mapPresenter.getCameraCenter()?.let { (centerLat, centerLng) ->
                    onMapClick(centerLat, centerLng)
                } ?: onMapClick(lat, lng)
            } else {
                onMapClick(lat, lng)
            }
        }
    }

    // 选中围栏时，摄像机跳转到围栏位置
    LaunchedEffect(displayedFenceId, fences, isMapReady) {
        if (!isMapReady) return@LaunchedEffect
        val fid = displayedFenceId ?: return@LaunchedEffect
        val fence = fences.find { it.id == fid } ?: return@LaunchedEffect
        when (fence.type) {
            GeoFenceType.CIRCLE -> {
                val c = fence.center ?: return@LaunchedEffect
                mapPresenter.moveCamera(c.latitude, c.longitude, 16f)
            }
            GeoFenceType.POLYGON -> {
                val pts = fence.points ?: return@LaunchedEffect
                if (pts.isEmpty()) return@LaunchedEffect
                var minLat = Double.MAX_VALUE; var maxLat = -Double.MAX_VALUE
                var minLng = Double.MAX_VALUE; var maxLng = -Double.MAX_VALUE
                pts.forEach { p ->
                    if (p.latitude < minLat) minLat = p.latitude
                    if (p.latitude > maxLat) maxLat = p.latitude
                    if (p.longitude < minLng) minLng = p.longitude
                    if (p.longitude > maxLng) maxLng = p.longitude
                }
                val centerLat = (minLat + maxLat) / 2
                val centerLng = (minLng + maxLng) / 2
                mapPresenter.moveCamera(centerLat, centerLng, 16f)
            }
        }
    }

    // 围栏覆盖物 - 使用 MapPresenter 管理
    LaunchedEffect(displayedFenceId, fences, isMapReady) {
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
        // 精确模式十字准星
        if (isPrecisionMode && (isDrawingMode || isEditingFence)) {
            CrosshairOverlay()
        }
    }
}

@Composable
private fun CrosshairOverlay() {
    val primaryColor = NeonAmber
    Canvas(Modifier.fillMaxSize()) {
        val centerX = size.width / 2
        val centerY = size.height / 2
        val crossSize = 24.dp.toPx()
        val strokeWidth = 2.dp.toPx()

        // 横线
        drawLine(
            color = primaryColor,
            start = androidx.compose.ui.geometry.Offset(centerX - crossSize, centerY),
            end = androidx.compose.ui.geometry.Offset(centerX + crossSize, centerY),
            strokeWidth = strokeWidth
        )
        // 竖线
        drawLine(
            color = primaryColor,
            start = androidx.compose.ui.geometry.Offset(centerX, centerY - crossSize),
            end = androidx.compose.ui.geometry.Offset(centerX, centerY + crossSize),
            strokeWidth = strokeWidth
        )
    }
}

// 内部包装类，用于追踪创建的覆盖物
private data class MarkerWrapper(val id: String, val marker: MapMarker)
private data class CircleWrapper(val id: String, val circle: MapCircle)
private data class PolygonWrapper(val id: String, val polygon: MapPolygon)

