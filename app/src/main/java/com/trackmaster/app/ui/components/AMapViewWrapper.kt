package com.trackmaster.app.ui.components

import android.graphics.Color
import android.os.Bundle
import android.util.Log
import androidx.compose.foundation.background
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.amap.api.location.AMapLocation
import com.amap.api.maps.AMap
import com.amap.api.maps.AMapOptions
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapView
import com.amap.api.maps.model.*
import com.trackmaster.app.GeoFence
import com.trackmaster.app.GeoFencePoint
import com.trackmaster.app.GeoFenceType

@Composable
fun AMapViewWrapper(
    @Suppress("UNUSED_PARAMETER") modifier: Modifier = Modifier,
    currentLocation: AMapLocation? = null,
    fences: List<GeoFence> = emptyList(),
    currentMatchedFenceIds: Set<String> = emptySet(),
    selectedFenceId: String? = null,
    isDrawingMode: Boolean = false,
    drawingPoints: List<GeoFencePoint> = emptyList(),
    isEditingFence: Boolean = false,
    editingPoints: List<GeoFencePoint> = emptyList(),
    onMapClick: (LatLng) -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // 隐私同意
    var isPrivacyReady by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        try {
            com.amap.api.location.AMapLocationClient.updatePrivacyShow(context, true, true)
            com.amap.api.location.AMapLocationClient.updatePrivacyAgree(context, true)
            isPrivacyReady = true
        } catch (e: Exception) {
            Log.e("TrackMaster", "隐私同意失败", e)
        }
    }

    // MapView 实例
    val mapView = remember { mutableStateOf<MapView?>(null) }
    val circleOverlays = remember { mutableStateMapOf<String, Circle>() }
    val polygonOverlays = remember { mutableStateMapOf<String, Polygon>() }
    val tempMarkers = remember { mutableStateListOf<Marker>() }

    var previewCircle by remember { mutableStateOf<Circle?>(null) }
    var previewPolygon by remember { mutableStateOf<Polygon?>(null) }
    val editingMarkers = remember { mutableStateListOf<Marker>() }

    // 初始化地图
    LaunchedEffect(isPrivacyReady) {
        if (!isPrivacyReady || mapView.value != null) return@LaunchedEffect
        val mv = MapView(context, AMapOptions().apply {
            zoomControlsEnabled(true)
            compassEnabled(true)
            scaleControlsEnabled(true)
        })
        mv.onCreate(Bundle())
        mapView.value = mv
    }

    fun cleanup() {
        circleOverlays.values.forEach { it.remove() }
        circleOverlays.clear()
        polygonOverlays.values.forEach { it.remove() }
        polygonOverlays.clear()
        tempMarkers.forEach { it.remove() }
        tempMarkers.clear()
        previewCircle?.remove()
        previewCircle = null
        previewPolygon?.remove()
        previewPolygon = null
        editingMarkers.forEach { it.remove() }
        editingMarkers.clear()
    }

    // 生命周期
    DisposableEffect(lifecycleOwner, mapView.value) {
        val mv = mapView.value ?: return@DisposableEffect onDispose { }
        val observer = LifecycleEventObserver { _, e ->
            try {
                when (e) {
                    Lifecycle.Event.ON_RESUME -> mv.onResume()
                    Lifecycle.Event.ON_PAUSE -> mv.onPause()
                    Lifecycle.Event.ON_DESTROY -> {
                        cleanup()
                        mv.onDestroy()
                    }
                    else -> {}
                }
            } catch (ex: Exception) {
                Log.e("TrackMaster", "生命周期错误: ${e.name}", ex)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            try {
                cleanup()
                mv.onPause()
                mv.onDestroy()
            } catch (ex: Exception) {
                Log.e("TrackMaster", "清理失败", ex)
            }
        }
    }

    // 地图配置
    LaunchedEffect(mapView.value) {
        val mv = mapView.value ?: return@LaunchedEffect
        var retry = 0
        var aMap: AMap? = mv.map
        while (aMap == null && retry < 30) {
            kotlinx.coroutines.delay(100)
            aMap = mv.map
            retry++
        }
        if (aMap == null) {
            Log.e("TrackMaster", "地图加载超时")
            return@LaunchedEffect
        }

        aMap.uiSettings.isZoomControlsEnabled = true
        aMap.uiSettings.isCompassEnabled = true
        aMap.uiSettings.isScaleControlsEnabled = true
        aMap.uiSettings.isMyLocationButtonEnabled = true

        try {
            val style = MyLocationStyle()
            style.myLocationType(MyLocationStyle.LOCATION_TYPE_LOCATION_ROTATE_NO_CENTER)
            style.interval(10000)
            style.strokeColor(0xFF3D9BE9.toInt())
            style.radiusFillColor(0x803D9BE9.toInt())
            style.strokeWidth(2f)
            aMap.myLocationStyle = style
            aMap.isMyLocationEnabled = true
        } catch (e: Exception) {
            Log.e("TrackMaster", "定位样式失败", e)
        }

        aMap.setOnMapClickListener { latLng -> onMapClick(latLng) }
    }

    // 首次进入时自动定位到用户当前位置（通过位置服务提供的位置）
    LaunchedEffect(currentLocation, mapView.value) {
        val mv = mapView.value ?: return@LaunchedEffect
        val aMap = mv.map ?: return@LaunchedEffect

        // 等待位置服务提供有效位置
        var retry = 0
        while (retry < 30) { // 等待最多3秒
            if (currentLocation != null && currentLocation.errorCode == 0) {
                val lat = currentLocation.latitude
                val lng = currentLocation.longitude
                if (lat != 0.0 || lng != 0.0) {
                    aMap.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lng), 16f))
                    break
                }
            }
            kotlinx.coroutines.delay(100)
            retry++
        }
    }

    // 围栏覆盖物
    LaunchedEffect(fences.map { it.id to it }.hashCode(), mapView.value) {
        val mv = mapView.value ?: return@LaunchedEffect
        val aMap = mv.map ?: return@LaunchedEffect

        // 清理不存在的
        val ids = fences.map { it.id }.toSet()
        circleOverlays.keys.filter { it !in ids }.forEach {
            circleOverlays[it]?.remove()
            circleOverlays.remove(it)
        }
        polygonOverlays.keys.filter { it !in ids }.forEach {
            polygonOverlays[it]?.remove()
            polygonOverlays.remove(it)
        }

        fences.forEach { fence ->
            val matched = fence.id in currentMatchedFenceIds
            val selected = fence.id == selectedFenceId
            val strokeColor = when {
                matched -> 0xFF00FF00.toInt()    // 绿色-命中
                selected -> 0xFFFFFF00.toInt()   // 黄色-选中
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
                    val circle = circleOverlays[fence.id] ?: run {
                        val c = aMap.addCircle(CircleOptions()
                            .center(LatLng(center.latitude, center.longitude))
                            .radius(radius.toDouble())
                            .strokeColor(strokeColor)
                            .fillColor(fillColor)
                            .strokeWidth(3f))
                        circleOverlays[fence.id] = c
                        c
                    }
                    circle.center = LatLng(center.latitude, center.longitude)
                    circle.radius = radius.toDouble()
                    circle.strokeColor = strokeColor
                    circle.fillColor = fillColor
                }
                GeoFenceType.POLYGON -> {
                    val pts = fence.points ?: return@forEach
                    if (pts.size < 3) return@forEach
                    val latLngs = pts.map { LatLng(it.latitude, it.longitude) }
                    val poly = polygonOverlays[fence.id] ?: run {
                        val p = aMap.addPolygon(PolygonOptions()
                            .addAll(latLngs)
                            .strokeColor(strokeColor)
                            .fillColor(fillColor)
                            .strokeWidth(3f))
                        polygonOverlays[fence.id] = p
                        p
                    }
                    poly.points = latLngs
                    poly.strokeColor = strokeColor
                    poly.fillColor = fillColor
                }
            }
        }
    }

    // 绘制预览
    LaunchedEffect(drawingPoints, isDrawingMode, mapView.value) {
        val mv = mapView.value ?: return@LaunchedEffect
        val aMap = mv.map ?: return@LaunchedEffect

        tempMarkers.forEach { it.remove() }
        tempMarkers.clear()
        previewCircle?.remove()
        previewCircle = null

        if (!isDrawingMode) return@LaunchedEffect

        drawingPoints.forEachIndexed { idx, pt ->
            val marker = aMap.addMarker(MarkerOptions().position(LatLng(pt.latitude, pt.longitude)).title("点 ${idx + 1}"))
            if (marker != null) tempMarkers.add(marker)
        }

        if (drawingPoints.size >= 2) {
            val center = drawingPoints.first()
            previewCircle = aMap.addCircle(CircleOptions()
                .center(LatLng(center.latitude, center.longitude))
                .radius(100.0)
                .strokeColor(0x80FF0000.toInt())
                .fillColor(0x20FF0000)
                .strokeWidth(2f))
        }
    }

    // 编辑模式
    LaunchedEffect(editingPoints, isEditingFence, mapView.value) {
        val mv = mapView.value ?: return@LaunchedEffect
        val aMap = mv.map ?: return@LaunchedEffect

        editingMarkers.forEach { it.remove() }
        editingMarkers.clear()
        previewPolygon?.remove()
        previewPolygon = null

        if (!isEditingFence) return@LaunchedEffect

        editingPoints.forEachIndexed { idx, pt ->
            val marker = aMap.addMarker(MarkerOptions().position(LatLng(pt.latitude, pt.longitude)).title("顶点 ${idx + 1}"))
            if (marker != null) editingMarkers.add(marker)
        }

        if (editingPoints.size >= 3) {
            val latLngs = editingPoints.map { LatLng(it.latitude, it.longitude) }
            previewPolygon = aMap.addPolygon(PolygonOptions()
                .addAll(latLngs)
                .strokeColor(0xFFFFFF00.toInt())
                .fillColor(0x40FFFF00)
                .strokeWidth(3f))
        }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        mapView.value?.let { mv ->
            AndroidView({ mv }, modifier = Modifier.fillMaxSize())
        } ?: CircularProgressIndicator(color = AndroidColor.White)
    }
}
