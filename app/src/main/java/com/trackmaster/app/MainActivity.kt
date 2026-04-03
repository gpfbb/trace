package com.trackmaster.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.amap.api.location.AMapLocation
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import com.amap.api.location.AMapLocationListener
import com.amap.api.maps.AMap
import com.amap.api.maps.AMapOptions
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapView
import com.amap.api.maps.model.Circle
import com.amap.api.maps.model.CircleOptions
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.MyLocationStyle
import com.amap.api.maps.model.Polygon
import com.amap.api.maps.model.PolygonOptions
import com.trackmaster.app.ui.theme.TrackMasterTheme
import java.util.*

// 地理围栏相关数据结构
data class GeoFencePoint(
    val latitude: Double,
    val longitude: Double
)

enum class GeoFenceType {
    CIRCLE,
    POLYGON
}

data class GeoFence(
    val id: String,
    val name: String,
    val type: GeoFenceType,
    val center: GeoFencePoint? = null, // 圆形围栏的中心点
    val radius: Float? = null, // 圆形围栏的半径（米）
    val points: List<GeoFencePoint>? = null, // 多边形围栏的顶点
    val createdAt: Date = Date()
)

enum class GeoFenceAction {
    ENTER,
    EXIT,
    STAY
}

data class GeoFenceEvent(
    val fenceId: String,
    val fenceName: String,
    val action: GeoFenceAction,
    val time: Date
)

class MainActivity : ComponentActivity(), AMapLocationListener {

    private var locationClient: AMapLocationClient? = null
    private val geoFences = mutableStateListOf<GeoFence>()
    private val geoFenceEvents = mutableStateListOf<GeoFenceEvent>()
    private val currentFenceStates = mutableMapOf<String, Boolean>() // fenceId to isInside
    
    private val circleOverlays = mutableMapOf<String, Circle>() // fenceId to Circle
    private val polygonOverlays = mutableMapOf<String, Polygon>() // fenceId to Polygon

    private val currentLocation = mutableStateOf<AMapLocation?>(null)
    private val locationStatus = mutableStateOf("正在初始化...")
    private val locationError = mutableStateOf<String?>(null)

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineLocationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseLocationGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
        val granted = fineLocationGranted && coarseLocationGranted
        if (granted) {
            startLocation()
        } else {
            locationStatus.value = "权限被拒绝"
            locationError.value = "请授予定位权限"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val activityContext = this

        Log.d("TrackMaster", "开始初始化高德 SDK")

        AMapLocationClient.updatePrivacyShow(activityContext, true, true)
        AMapLocationClient.updatePrivacyAgree(activityContext, true)
        // 使用硬编码的 API Key 进行测试
        AMapLocationClient.setApiKey("de0019af032b8f64f337ce0004d78bf1")

        initLocationClient()

        setContent {
            TrackMasterTheme {
                MainScreen(
                    currentLocation = currentLocation.value,
                    locationStatus = locationStatus.value,
                    locationError = locationError.value
                )
            }
        }

        requestLocationPermission()
    }

    private fun initLocationClient() {
        try {
            Log.d("TrackMaster", "创建定位客户端")
            locationClient = AMapLocationClient(applicationContext)
            locationClient?.setLocationListener(this)

            val option = AMapLocationClientOption()
            option.locationMode = AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
            option.interval = 2000
            option.isNeedAddress = true
            option.isOnceLocation = false
            option.httpTimeOut = 30000
            locationClient?.setLocationOption(option)

            Log.d("TrackMaster", "定位客户端初始化完成")
        } catch (e: Exception) {
            Log.e("TrackMaster", "初始化定位客户端失败", e)
            locationStatus.value = "初始化失败"
            locationError.value = e.message
        }
    }

    private fun requestLocationPermission() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.d("TrackMaster", "请求定位权限")
            locationStatus.value = "请求权限中..."
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        } else {
            Log.d("TrackMaster", "已有定位权限，直接启动定位")
            startLocation()
        }
    }

    private fun startLocation() {
        try {
            Log.d("TrackMaster", "启动定位服务")
            locationStatus.value = "正在获取位置..."
            locationError.value = null
            locationClient?.startLocation()
        } catch (e: Exception) {
            Log.e("TrackMaster", "启动定位失败", e)
            locationStatus.value = "启动失败"
            locationError.value = e.message
        }
    }

    override fun onLocationChanged(location: AMapLocation?) {
        if (location == null) {
            Log.e("TrackMaster", "onLocationChanged: location is null")
            return
        }

        Log.d("TrackMaster", """
            收到位置更新:
            - errorCode: ${location.errorCode}
            - errorInfo: ${location.errorInfo}
            - 经度: ${location.longitude}
            - 纬度: ${location.latitude}
            - 精度: ${location.accuracy}米
            - 地址: ${location.address}
        """.trimIndent())

        if (location.errorCode == 0) {
            currentLocation.value = location
            locationStatus.value = "定位成功"
            locationError.value = null
            
            // 检查地理围栏
            checkGeoFences(location)
        } else {
            locationStatus.value = "定位失败"
            locationError.value = "${location.errorInfo} (错误码: ${location.errorCode})"
            Log.e("TrackMaster", "定位错误: ${location.errorInfo}, 错误码: ${location.errorCode}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        locationClient?.stopLocation()
        locationClient?.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        locationClient?.startLocation()
    }

    override fun onPause() {
        super.onPause()
        locationClient?.stopLocation()
    }

    // 计算两点之间的距离（米）
    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371e3 // 地球半径（米）
        val φ1 = Math.toRadians(lat1)
        val φ2 = Math.toRadians(lat2)
        val Δφ = Math.toRadians(lat2 - lat1)
        val Δλ = Math.toRadians(lon2 - lon1)

        val a = Math.sin(Δφ/2) * Math.sin(Δφ/2) +
                Math.cos(φ1) * Math.cos(φ2) *
                Math.sin(Δλ/2) * Math.sin(Δλ/2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a))

        return R * c
    }

    // 判断点是否在圆形围栏内
    private fun isPointInCircle(point: GeoFencePoint, fence: GeoFence): Boolean {
        if (fence.type != GeoFenceType.CIRCLE || fence.center == null || fence.radius == null) {
            return false
        }
        val distance = calculateDistance(point.latitude, point.longitude, fence.center.latitude, fence.center.longitude)
        return distance <= fence.radius
    }

    // 判断点是否在多边形围栏内
    private fun isPointInPolygon(point: GeoFencePoint, fence: GeoFence): Boolean {
        if (fence.type != GeoFenceType.POLYGON || fence.points == null || fence.points.size < 3) {
            return false
        }

        var inside = false
        val points = fence.points
        val n = points.size
        var j = n - 1

        for (i in 0 until n) {
            if ((points[i].longitude > point.longitude) != (points[j].longitude > point.longitude) &&
                point.latitude < (points[j].latitude - points[i].latitude) * (point.longitude - points[i].longitude) /
                (points[j].longitude - points[i].longitude) + points[i].latitude) {
                inside = !inside
            }
            j = i
        }
        return inside
    }

    // 检查用户是否在围栏内
    private fun checkGeoFences(location: AMapLocation) {
        val userPoint = GeoFencePoint(location.latitude, location.longitude)

        for (fence in geoFences) {
            val isInside = when (fence.type) {
                GeoFenceType.CIRCLE -> isPointInCircle(userPoint, fence)
                GeoFenceType.POLYGON -> isPointInPolygon(userPoint, fence)
            }

            val previousState = currentFenceStates[fence.id] ?: false
            if (isInside && !previousState) {
                // 进入围栏
                val event = GeoFenceEvent(fence.id, fence.name, GeoFenceAction.ENTER, Date())
                geoFenceEvents.add(event)
                Log.d("TrackMaster", "进入围栏: ${fence.name}")
            } else if (!isInside && previousState) {
                // 退出围栏
                val event = GeoFenceEvent(fence.id, fence.name, GeoFenceAction.EXIT, Date())
                geoFenceEvents.add(event)
                Log.d("TrackMaster", "退出围栏: ${fence.name}")
            }
            currentFenceStates[fence.id] = isInside
        }
    }

    // 创建圆形地理围栏
    fun createCircleGeoFence(name: String, latitude: Double, longitude: Double, radius: Float) {
        val fence = GeoFence(
            id = UUID.randomUUID().toString(),
            name = name,
            type = GeoFenceType.CIRCLE,
            center = GeoFencePoint(latitude, longitude),
            radius = radius
        )
        geoFences.add(fence)
        currentFenceStates[fence.id] = false
        Log.d("TrackMaster", "创建圆形围栏: $name, 半径: ${radius}米")
    }

    // 创建多边形地理围栏
    fun createPolygonGeoFence(name: String, points: List<GeoFencePoint>) {
        if (points.size < 3) {
            Log.e("TrackMaster", "多边形围栏至少需要3个点")
            return
        }
        val fence = GeoFence(
            id = UUID.randomUUID().toString(),
            name = name,
            type = GeoFenceType.POLYGON,
            points = points
        )
        geoFences.add(fence)
        currentFenceStates[fence.id] = false
        Log.d("TrackMaster", "创建多边形围栏: $name, 顶点数: ${points.size}")
    }

    // 删除地理围栏
    fun deleteGeoFence(fenceId: String) {
        val fence = geoFences.find { it.id == fenceId }
        if (fence != null) {
            geoFences.remove(fence)
            currentFenceStates.remove(fenceId)
            
            // 移除地图上的覆盖物
            circleOverlays[fenceId]?.remove()
            circleOverlays.remove(fenceId)
            polygonOverlays[fenceId]?.remove()
            polygonOverlays.remove(fenceId)
            
            Log.d("TrackMaster", "删除围栏: ${fence.name}")
        }
    }

    // 清除所有地理围栏
    fun clearAllGeoFences() {
        geoFences.clear()
        currentFenceStates.clear()
        geoFenceEvents.clear()
        
        // 清除所有地图覆盖物
        circleOverlays.values.forEach { it.remove() }
        circleOverlays.clear()
        polygonOverlays.values.forEach { it.remove() }
        polygonOverlays.clear()
        
        Log.d("TrackMaster", "清除所有围栏")
    }

    // 获取所有地理围栏
    fun getGeoFences(): List<GeoFence> {
        return geoFences.toList()
    }

    // 获取地理围栏事件
    fun getGeoFenceEvents(): List<GeoFenceEvent> {
        return geoFenceEvents.toList()
    }

    // 获取用户当前在哪些围栏内
    fun getCurrentFences(): List<GeoFence> {
        return geoFences.filter { 
            val state = currentFenceStates[it.id]
            state == true
        }
    }

    // 更新地图上的围栏覆盖物
    fun updateGeoFenceOverlays(aMap: AMap) {
        val existingFenceIds = geoFences.map { it.id }.toSet()
        
        // 移除不存在的围栏覆盖物
        val circlesToRemove = circleOverlays.keys.filter { it !in existingFenceIds }
        circlesToRemove.forEach { fenceId ->
            circleOverlays[fenceId]?.remove()
            circleOverlays.remove(fenceId)
        }
        
        val polygonsToRemove = polygonOverlays.keys.filter { it !in existingFenceIds }
        polygonsToRemove.forEach { fenceId ->
            polygonOverlays[fenceId]?.remove()
            polygonOverlays.remove(fenceId)
        }
        
        // 添加或更新围栏覆盖物
        geoFences.forEach { fence ->
            when (fence.type) {
                GeoFenceType.CIRCLE -> {
                    if (!circleOverlays.containsKey(fence.id)) {
                        val circleOptions = CircleOptions()
                            .center(LatLng(fence.center!!.latitude, fence.center!!.longitude))
                            .radius(fence.radius!!.toDouble())
                            .strokeColor(Color.parseColor("#FF0000"))
                            .fillColor(Color.argb(50, 255, 0, 0))
                            .strokeWidth(2f)
                        val circle = aMap.addCircle(circleOptions)
                        circleOverlays[fence.id] = circle
                    }
                }
                GeoFenceType.POLYGON -> {
                    if (!polygonOverlays.containsKey(fence.id)) {
                        val points = fence.points!!.map { LatLng(it.latitude, it.longitude) }
                        val polygonOptions = PolygonOptions()
                            .addAll(points)
                            .strokeColor(Color.parseColor("#FF0000"))
                            .fillColor(Color.argb(50, 255, 0, 0))
                            .strokeWidth(2f)
                        val polygon = aMap.addPolygon(polygonOptions)
                        polygonOverlays[fence.id] = polygon
                    }
                }
            }
        }
    }
}

@Composable
fun AMapView(
    currentLocation: AMapLocation?,
    onMapReady: (AMap) -> Unit = {},
    onMapClick: (LatLng) -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mainActivity = context as MainActivity

    val mapView = remember {
        MapView(context, AMapOptions().apply {
            zoomControlsEnabled(true)
            compassEnabled(true)
            scaleControlsEnabled(true)
        }).apply {
            onCreate(Bundle())
        }
    }

    val mapType = remember { mutableStateOf(AMap.MAP_TYPE_NORMAL) }
    val showTraffic = remember { mutableStateOf(false) }
    val isLayerMenuExpanded = remember { mutableStateOf(false) }
    val hasMovedCamera = remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            try {
                when (event) {
                    Lifecycle.Event.ON_RESUME -> mapView.onResume()
                    Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                    Lifecycle.Event.ON_DESTROY -> {
                        mapView.onDestroy()
                    }
                    else -> {}
                }
            } catch (e: Exception) {
                Log.e("TrackMaster", "地图生命周期处理失败: ${event.name}", e)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(mapView) {
        val aMap = mapView.map
        if (aMap != null) {
            aMap.uiSettings.isZoomControlsEnabled = true
            aMap.uiSettings.isCompassEnabled = true
            aMap.uiSettings.isScaleControlsEnabled = true
            aMap.uiSettings.isMyLocationButtonEnabled = true

            try {
                val myLocationStyle = MyLocationStyle()
                myLocationStyle.myLocationType(MyLocationStyle.LOCATION_TYPE_LOCATION_ROTATE_NO_CENTER)
                myLocationStyle.interval(2000)
                myLocationStyle.strokeColor(Color.parseColor("#3D9BE9"))
                myLocationStyle.radiusFillColor(Color.argb(50, 63, 155, 233))
                myLocationStyle.strokeWidth(2f)
                aMap.myLocationStyle = myLocationStyle
                aMap.isMyLocationEnabled = true

                aMap.setOnMyLocationChangeListener {
                    if (it != null) {
                        Log.d("TrackMaster", "地图蓝点位置变化: ${it.latitude}, ${it.longitude}")
                    }
                }

                // 设置地图点击监听器
                aMap.setOnMapClickListener { latLng ->
                    Log.d("TrackMaster", "地图点击: ${latLng.latitude}, ${latLng.longitude}")
                    onMapClick(latLng)
                }

                onMapReady(aMap)

                Log.d("TrackMaster", "地图初始化完成")
            } catch (e: Exception) {
                Log.e("TrackMaster", "设置定位蓝点失败", e)
            }
        }
    }

    LaunchedEffect(mapType.value, mapView) {
        val aMap = mapView.map
        if (aMap != null) {
            aMap.mapType = mapType.value
        }
    }

    LaunchedEffect(showTraffic.value, mapView) {
        val aMap = mapView.map
        if (aMap != null) {
            aMap.isTrafficEnabled = showTraffic.value
        }
    }

    LaunchedEffect(currentLocation, mapView) {
        val aMap = mapView.map
        if (currentLocation != null && currentLocation.errorCode == 0 && aMap != null) {
            // 检查是否为有效坐标（非0,0）
            val isInvalidLocation = currentLocation.latitude == 0.0 && currentLocation.longitude == 0.0
            if (!isInvalidLocation) {
                val latLng = LatLng(currentLocation.latitude, currentLocation.longitude)
                // 只在首次定位时移动相机，避免强制居中
                if (!hasMovedCamera.value) {
                    aMap.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 16f))
                    hasMovedCamera.value = true
                }
            }
        }
    }

    LaunchedEffect(mainActivity.getGeoFences().size, mapView) {
        val aMap = mapView.map
        if (aMap != null) {
            mainActivity.updateGeoFenceOverlays(aMap)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            AndroidView(
                factory = { mapView },
                modifier = Modifier.fillMaxSize()
            ) { view ->
                // 不需要在这里调用 onResume，已经在 DisposableEffect 中处理了生命周期
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(16.dp)
            ) {
                Card(
                    modifier = Modifier
                        .width(200.dp)
                        .animateContentSize(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "图层",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { isLayerMenuExpanded.value = !isLayerMenuExpanded.value }) {
                            Text(
                                text = if (isLayerMenuExpanded.value) "▼" else "▶",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }

                    if (isLayerMenuExpanded.value) {
                        Column(modifier = Modifier.padding(bottom = 8.dp)) {
                            RadioButtonWithLabel(
                                selected = mapType.value == AMap.MAP_TYPE_NORMAL,
                                label = "白昼地图",
                                onSelected = { mapType.value = AMap.MAP_TYPE_NORMAL }
                            )
                            RadioButtonWithLabel(
                                selected = mapType.value == AMap.MAP_TYPE_NIGHT,
                                label = "夜景地图",
                                onSelected = { mapType.value = AMap.MAP_TYPE_NIGHT }
                            )
                            RadioButtonWithLabel(
                                selected = mapType.value == AMap.MAP_TYPE_SATELLITE,
                                label = "卫星地图",
                                onSelected = { mapType.value = AMap.MAP_TYPE_SATELLITE }
                            )
                            RadioButtonWithLabel(
                                selected = mapType.value == AMap.MAP_TYPE_NAVI,
                                label = "导航地图",
                                onSelected = { mapType.value = AMap.MAP_TYPE_NAVI }
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 16.dp, end = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "路况图层",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f)
                                )
                                Switch(
                                    checked = showTraffic.value,
                                    onCheckedChange = { showTraffic.value = it }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RadioButtonWithLabel(
    selected: Boolean,
    label: String,
    onSelected: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp)
            .clickable { onSelected() },
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = onSelected
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}

@Composable
fun MainScreen(
    currentLocation: AMapLocation?,
    locationStatus: String,
    locationError: String?
) {
    val context = LocalContext.current
    val mainActivity = context as MainActivity
    val showGeoFenceDialog = remember { mutableStateOf(false) }
    val fenceName = remember { mutableStateOf("") }
    val fenceRadius = remember { mutableStateOf(100f) }
    val selectedFenceType = remember { mutableStateOf(GeoFenceType.CIRCLE) }
    val isDrawingFence = remember { mutableStateOf(false) }
    val drawingPoints = remember { mutableStateListOf<GeoFencePoint>() }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        floatingActionButton = {
            Column {
                if (isDrawingFence.value) {
                    FloatingActionButton(
                        onClick = {
                            if (drawingPoints.size >= 3) {
                                if (fenceName.value.isNotEmpty()) {
                                    mainActivity.createPolygonGeoFence(
                                        name = fenceName.value,
                                        points = drawingPoints.toList()
                                    )
                                    isDrawingFence.value = false
                                    drawingPoints.clear()
                                    fenceName.value = ""
                                } else {
                                    // 显示一个简单的提示，要求输入围栏名称
                                    showGeoFenceDialog.value = true
                                }
                            }
                        },
                        containerColor = MaterialTheme.colorScheme.primary
                    ) {
                        Text("完成")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    FloatingActionButton(
                        onClick = {
                            isDrawingFence.value = false
                            drawingPoints.clear()
                        },
                        containerColor = MaterialTheme.colorScheme.error
                    ) {
                        Text("取消")
                    }
                } else {
                    FloatingActionButton(
                        onClick = { showGeoFenceDialog.value = true },
                        containerColor = MaterialTheme.colorScheme.primary
                    ) {
                        Text("+")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    FloatingActionButton(
                        onClick = { mainActivity.clearAllGeoFences() },
                        containerColor = MaterialTheme.colorScheme.error
                    ) {
                        Text("清")
                    }
                }
            }
        }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(it),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "轨迹大师",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f)
                        )

                        Text(
                            text = locationStatus,
                            style = MaterialTheme.typography.bodySmall,
                            color = when {
                                locationStatus.contains("成功") -> MaterialTheme.colorScheme.primary
                                locationStatus.contains("失败") || locationStatus.contains("拒绝") -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }

                    if (locationError != null) {
                        Text(
                            text = locationError,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = 12.dp, bottom = 4.dp)
                        )
                    }

                    if (currentLocation != null && currentLocation.errorCode == 0) {
                        Text(
                            text = "经纬度: ${String.format("%.6f", currentLocation.longitude)}, ${String.format("%.6f", currentLocation.latitude)} | 精度: ${currentLocation.accuracy.toInt()}m",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 6.dp)
                        )
                    }

                    // 显示当前在哪些围栏内
                    val currentFences = mainActivity.getCurrentFences()
                    if (currentFences.isNotEmpty()) {
                        Text(
                            text = "当前在围栏内: ${currentFences.joinToString { it.name }}",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 6.dp)
                        )
                    }

                    // 显示绘制状态
                    if (isDrawingFence.value) {
                        Text(
                            text = "绘制模式：已点击 ${drawingPoints.size} 个点（至少需要3个点）",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 6.dp)
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    AMapView(
                        currentLocation = currentLocation,
                        onMapClick = { latLng ->
                            if (isDrawingFence.value) {
                                drawingPoints.add(GeoFencePoint(latLng.latitude, latLng.longitude))
                            }
                        }
                    )
                }

                // 地理围栏列表
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp)
                        .heightIn(min = 100.dp, max = 200.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f)
                    )
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text(
                            text = "地理围栏",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                        val fences = mainActivity.getGeoFences()
                        if (fences.isEmpty()) {
                            Text(
                                text = "暂无围栏",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Column {
                                fences.forEach { fence ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = fence.name,
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Text(
                                            text = when (fence.type) {
                                                GeoFenceType.CIRCLE -> "圆形"
                                                GeoFenceType.POLYGON -> "多边形"
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        IconButton(onClick = { mainActivity.deleteGeoFence(fence.id) }) {
                                            Text("×", color = MaterialTheme.colorScheme.error)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // 创建地理围栏对话框
    if (showGeoFenceDialog.value) {
        AlertDialog(
            onDismissRequest = { 
                showGeoFenceDialog.value = false
                if (!isDrawingFence.value) {
                    drawingPoints.clear()
                }
            },
            title = { Text(if (isDrawingFence.value) "完成绘制" else "创建地理围栏") },
            text = {
                Column {
                    if (isDrawingFence.value) {
                        Text(
                            text = "已点击 ${drawingPoints.size} 个点（至少需要3个点）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = fenceName.value,
                            onValueChange = { fenceName.value = it },
                            label = { Text("围栏名称") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        OutlinedTextField(
                            value = fenceName.value,
                            onValueChange = { fenceName.value = it },
                            label = { Text("围栏名称") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row {
                            RadioButton(
                                selected = selectedFenceType.value == GeoFenceType.CIRCLE,
                                onClick = { selectedFenceType.value = GeoFenceType.CIRCLE }
                            )
                            Text("圆形围栏")
                            Spacer(modifier = Modifier.width(16.dp))
                            RadioButton(
                                selected = selectedFenceType.value == GeoFenceType.POLYGON,
                                onClick = { selectedFenceType.value = GeoFenceType.POLYGON }
                            )
                            Text("多边形围栏")
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        if (selectedFenceType.value == GeoFenceType.CIRCLE) {
                            OutlinedTextField(
                                value = fenceRadius.value.toString(),
                                onValueChange = { fenceRadius.value = it.toFloatOrNull() ?: 100f },
                                label = { Text("半径 (米)") },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        if (selectedFenceType.value == GeoFenceType.POLYGON) {
                            Button(
                                onClick = { 
                                    isDrawingFence.value = true
                                    drawingPoints.clear()
                                    showGeoFenceDialog.value = false // 关闭对话框，让用户能在地图上点击
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("开始绘制多边形围栏")
                            }
                        }
                    }
                }
            },
            confirmButton = {
                if (isDrawingFence.value) {
                    Button(onClick = {
                        if (drawingPoints.size >= 3 && fenceName.value.isNotEmpty()) {
                            mainActivity.createPolygonGeoFence(
                                name = fenceName.value,
                                points = drawingPoints.toList()
                            )
                            showGeoFenceDialog.value = false
                            isDrawingFence.value = false
                            drawingPoints.clear()
                            fenceName.value = ""
                        }
                    }) {
                        Text("完成")
                    }
                } else if (selectedFenceType.value == GeoFenceType.CIRCLE) {
                    Button(onClick = {
                        if (fenceName.value.isNotEmpty() && currentLocation != null) {
                            mainActivity.createCircleGeoFence(
                                name = fenceName.value,
                                latitude = currentLocation.latitude,
                                longitude = currentLocation.longitude,
                                radius = fenceRadius.value
                            )
                            showGeoFenceDialog.value = false
                            fenceName.value = ""
                        }
                    }) {
                        Text("创建")
                    }
                } else {
                    Button(onClick = {
                        showGeoFenceDialog.value = false
                    }) {
                        Text("关闭")
                    }
                }
            },
            dismissButton = {
                if (isDrawingFence.value) {
                    Button(onClick = {
                        showGeoFenceDialog.value = false
                        isDrawingFence.value = false
                        drawingPoints.clear()
                    }) {
                        Text("取消")
                    }
                } else {
                    Button(onClick = {
                        showGeoFenceDialog.value = false
                    }) {
                        Text("取消")
                    }
                }
            }
        )
    }
}