package com.trackmaster.app.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.trackmaster.app.AppPreferences
import com.google.android.gms.maps.MapView
import com.trackmaster.app.data.GeoFence
import com.trackmaster.app.data.GeoFencePoint
import com.trackmaster.app.core.GeoFenceType
import com.trackmaster.app.locationservice.Location
import com.trackmaster.app.mapstraction.MapCircle
import com.trackmaster.app.mapstraction.MapLocation
import com.trackmaster.app.mapstraction.MapPolygon
import com.trackmaster.app.mapstraction.MapMarker
import com.trackmaster.app.mapstraction.MarkerIcon
import com.trackmaster.app.mapstraction.MapPresenter
import com.trackmaster.app.mapstraction.google.GoogleMapPresenter
import com.trackmaster.app.ui.theme.NeonAmber
import kotlin.math.sin
import kotlin.math.cos
import kotlin.math.sqrt
import kotlin.math.atan2

private const val TAG = "GoogleMapWrapper"

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
fun GoogleMapWrapper(
    modifier: Modifier = Modifier,
    currentLocation: Location?,
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

    // 内部创建 Google Maps presenter
    val mapView: MapView = remember { MapView(context) }
    val mapPresenter: GoogleMapPresenter = remember(mapView) {
        GoogleMapPresenter(mapView, lifecycleOwner.lifecycle)
    }

    // 内部状态管理
    val tempMarkers = remember { mutableStateListOf<GMarkerWrapper>() }
    var previewCircle by remember { mutableStateOf<GCircleWrapper?>(null) }
    var previewPolygon by remember { mutableStateOf<GPolygonWrapper?>(null) }
    val editingMarkers = remember { mutableStateListOf<GMarkerWrapper>() }
    var cameraCenterLat by remember { mutableStateOf(0.0) }
    var cameraCenterLng by remember { mutableStateOf(0.0) }
    var hasCameraMoved by remember { mutableStateOf(false) }
    var hasNavigatedToUser by remember { mutableStateOf(false) }

    // 地图真正就绪后才会触发后续 LaunchedEffect
    val isMapReady by mapPresenter.isMapReady.collectAsState()

    // 退出时保存相机位置
    DisposableEffect(Unit) {
        onDispose {
            try {
                mapPresenter.getCameraCenter()?.let { (lat, lng) ->
                    appPrefs.cameraLatitude = lat
                    appPrefs.cameraLongitude = lng
                    appPrefs.cameraZoom = mapPresenter.getZoom() ?: 17f
                    appPrefs.hasCameraPosition = true
                }
            } catch (e: Exception) { Log.e(TAG, "保存相机位置失败", e) }
        }
    }

    // 相机移动时实时保存位置
    LaunchedEffect(isMapReady) {
        if (!isMapReady) return@LaunchedEffect
        mapPresenter.setOnCameraCenterChangeListener { lat, lng ->
            try {
                appPrefs.cameraLatitude = lat
                appPrefs.cameraLongitude = lng
                appPrefs.cameraZoom = mapPresenter.getZoom() ?: 17f
                appPrefs.hasCameraPosition = true
            } catch (e: Exception) { Log.e(TAG, "保存相机位置失败", e) }
        }
    }

    // ========== 位置权限处理 ==========
    var hasLocationPermission by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        hasLocationPermission = fineGranted || coarseGranted
        Log.d("GoogleMapWrapper", "=== 权限回调: FINE=$fineGranted, COARSE=$coarseGranted ===")
        if (hasLocationPermission) {
            Log.d("GoogleMapWrapper", "权限授予，调用 mapPresenter.onLocationPermissionGranted() + service.onLocationPermissionGranted()")
            mapPresenter.onLocationPermissionGranted()
            // 通知 GeoFenceLocationService 权限已授予，Google provider 可以重新启动
            (context.applicationContext as? com.trackmaster.app.TrackMasterApplication)
                ?.getLocationService()?.onLocationPermissionGranted()
        } else {
            Log.w("GoogleMapWrapper", "权限仍被拒绝")
        }
    }

    LaunchedEffect(Unit) {
        hasLocationPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasLocationPermission) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    // 蓝点定位（Google Maps SDK 原生显示蓝色点/移动 chevron，方向由 SDK 根据 bearing 自动显示）

    LaunchedEffect(isMapReady, hasLocationPermission) {
        Log.d("GoogleMapWrapper", "LaunchedEffect(isMapReady,hasLocationPermission): isMapReady=$isMapReady, hasLocationPermission=$hasLocationPermission")
        if (!isMapReady) {
            Log.d("GoogleMapWrapper", "LaunchedEffect: 地图未就绪，跳过 showMyLocation")
            return@LaunchedEffect
        }
        if (hasLocationPermission) {
            Log.d("GoogleMapWrapper", "LaunchedEffect: 地图已就绪+有权限，调用 showMyLocation()")
            mapPresenter.showMyLocation()
        } else {
            Log.d("GoogleMapWrapper", "LaunchedEffect: 地图已就绪但无权限，不调用 showMyLocation")
        }
    }

    // 每次进入地图页：GPS连续监听，收到首次精准位置后喂入地图显示蓝点
    // 纯 Google GPS 定位，不走 AMap
    LaunchedEffect(isMapReady) {
        if (!isMapReady) return@LaunchedEffect
    }

    // Google GPS 连续监听：只在有权限时启动，组件销毁时自动停止
    DisposableEffect(hasLocationPermission) {
        if (!hasLocationPermission) return@DisposableEffect onDispose { }

        val fusedClient = LocationServices.getFusedLocationProviderClient(context)

        val callback = object : com.google.android.gms.location.LocationCallback() {
            override fun onLocationResult(result: com.google.android.gms.location.LocationResult) {
                val loc = result.lastLocation ?: return
                val gpsLoc = com.trackmaster.app.locationservice.Location(
                    latitude = loc.latitude,
                    longitude = loc.longitude,
                    accuracy = loc.accuracy,
                    speed = loc.speed,
                    bearing = loc.bearing,
                    timestamp = loc.time,
                    locationType = com.trackmaster.app.locationservice.LocationType.GPS
                )
                Log.d("GoogleMapWrapper", "GPS位置: lat=${gpsLoc.latitude}, acc=${loc.accuracy}m")
                mapPresenter.setExternalLocation(gpsLoc)
                mapPresenter.showMyLocation()
            }
        }

        val request = com.google.android.gms.location.LocationRequest.Builder(
            com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY, 2000L
        ).setMinUpdateIntervalMillis(1000L).build()

        fusedClient.requestLocationUpdates(request, callback, android.os.Looper.getMainLooper())
        Log.d("GoogleMapWrapper", "Google GPS 连续监听已启动")

        onDispose {
            fusedClient.removeLocationUpdates(callback)
            Log.d("GoogleMapWrapper", "Google GPS 监听已停止")
        }
    }

    // 地图点击监听
    LaunchedEffect(isMapReady) {
        if (!isMapReady) return@LaunchedEffect
        mapPresenter.setOnMapClickListener { lat, lng ->
            onMapClick(lat, lng)
        }
    }

    // 精确模式下，跟踪相机中心点，并在激活时立即初始化相机中心
    LaunchedEffect(isPrecisionMode, isMapReady) {
        if (!isMapReady) return@LaunchedEffect
        if (isPrecisionMode) {
            // 立即初始化相机中心（不用等相机移动）
            mapPresenter.getCameraCenter()?.let { (lat, lng) ->
                cameraCenterLat = lat
                cameraCenterLng = lng
                hasCameraMoved = true
            }
            // 持续跟踪相机中心
            mapPresenter.setOnCameraCenterChangeListener { lat, lng ->
                cameraCenterLat = lat
                cameraCenterLng = lng
                hasCameraMoved = true
            }
        }
    }

    // 精确模式下，点击使用相机中心点而非实际点击位置
    LaunchedEffect(isPrecisionMode, isMapReady) {
        if (!isMapReady) return@LaunchedEffect
        mapPresenter.setOnMapClickListener { lat, lng ->
            if (isPrecisionMode && hasCameraMoved) {
                onMapClick(cameraCenterLat, cameraCenterLng)
            } else {
                onMapClick(lat, lng)
            }
        }
    }

    // 地图类型切换（presenter 内部处理 AMap 3→HYBRID 的转换）
    LaunchedEffect(mapType, isMapReady) {
        if (!isMapReady) return@LaunchedEffect
        mapPresenter.configureMap(mapType = mapType)
    }

    // 选中围栏时，摄像机跳转到围栏位置
    LaunchedEffect(displayedFenceId, isMapReady) {
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

    // 围栏覆盖物
    LaunchedEffect(displayedFenceId, fences.map { it.id to it }.hashCode(), isMapReady) {
        if (!isMapReady) return@LaunchedEffect

        mapPresenter.clearAll()

        fences.forEach { fence ->
            val matched = fence.id in currentMatchedFenceIds
            val highlighted = fence.id == highlightedFenceId
            val displayed = fence.id == displayedFenceId

            val strokeColor = when {
                highlighted -> 0xFFFFFF00.toInt()
                displayed -> AndroidColor.parseColor(fence.color)
                matched -> 0xFF00FF00.toInt()
                else -> AndroidColor.parseColor(fence.color)
            }

            val alphaInt = (fence.alpha * 255).toInt()
            val fillColor = AndroidColor.argb(
                alphaInt,
                AndroidColor.red(AndroidColor.parseColor(fence.color)),
                AndroidColor.green(AndroidColor.parseColor(fence.color)),
                AndroidColor.blue(AndroidColor.parseColor(fence.color))
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

            // 灵动岛选中的围栏，添加点位标记
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

        tempMarkers.forEach { mapPresenter.removeMarker(it.id) }
        tempMarkers.clear()
        previewCircle?.let { mapPresenter.removeCircle(it.id) }
        previewCircle = null
        previewPolygon?.let { mapPresenter.removePolygon(it.id) }
        previewPolygon = null

        if (!isDrawingMode) return@LaunchedEffect

        drawingPoints.forEachIndexed { idx, pt ->
            val marker = MapMarker(
                id = "drawing_$idx",
                position = MapLocation(pt.latitude, pt.longitude),
                title = "点 ${idx + 1}",
                icon = MarkerIcon.DEFAULT
            )
            mapPresenter.addMarker(marker)
            tempMarkers.add(GMarkerWrapper(marker.id, marker))
        }

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
            previewCircle = GCircleWrapper(circle.id, circle)
        }

        if (selectedFenceType == GeoFenceType.POLYGON && drawingPoints.size >= 3) {
            val polygon = MapPolygon(
                id = "preview_polygon",
                points = drawingPoints.map { MapLocation(it.latitude, it.longitude) },
                fillColor = 0x20FF0000,
                strokeColor = 0x80FF0000.toInt(),
                strokeWidth = 2f
            )
            mapPresenter.addPolygon(polygon)
            previewPolygon = GPolygonWrapper(polygon.id, polygon)
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
            editingMarkers.add(GMarkerWrapper(marker.id, marker))
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
            previewPolygon = GPolygonWrapper(polygon.id, polygon)
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                Log.d("GoogleMapWrapper", "=== AndroidView factory 被调用 ===")
                Log.d("GoogleMapWrapper", "AndroidView factory: 调用 mapPresenter.onCreate()")
                mapPresenter.onCreate(null)
                Log.d("GoogleMapWrapper", "AndroidView factory: mapPresenter.onCreate() 返回，MapView 实例=$mapView")
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

        drawLine(
            color = primaryColor,
            start = androidx.compose.ui.geometry.Offset(centerX - crossSize, centerY),
            end = androidx.compose.ui.geometry.Offset(centerX + crossSize, centerY),
            strokeWidth = strokeWidth
        )
        drawLine(
            color = primaryColor,
            start = androidx.compose.ui.geometry.Offset(centerX, centerY - crossSize),
            end = androidx.compose.ui.geometry.Offset(centerX, centerY + crossSize),
            strokeWidth = strokeWidth
        )
    }
}

// 内部包装类
private data class GMarkerWrapper(val id: String, val marker: MapMarker)
private data class GCircleWrapper(val id: String, val circle: MapCircle)
private data class GPolygonWrapper(val id: String, val polygon: MapPolygon)
