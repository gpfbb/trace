package com.trackmaster.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.amap.api.location.AMapLocation
import com.amap.api.location.AMapLocationClient
import com.amap.api.maps.AMap
import com.amap.api.maps.AMapOptions
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapView
import com.amap.api.maps.model.Circle
import com.amap.api.maps.model.CircleOptions
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.Marker
import kotlinx.coroutines.launch
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.maps.model.MyLocationStyle
import com.amap.api.maps.model.Polygon
import com.amap.api.maps.model.PolygonOptions
import com.trackmaster.app.ui.theme.*
import com.trackmaster.app.ui.HeatmapOverlay
import android.content.SharedPreferences
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
    val categoryId: String = Category.UNASSIGNED_ID, // 所属分类ID
    val createdAt: Date = Date()
)

enum class GeoFenceAction {
    ENTER,
    EXIT,
    STAY
}

data class GeoFenceEvent(
    val id: String = UUID.randomUUID().toString(),
    val fenceId: String,
    val fenceName: String,
    val action: GeoFenceAction,
    val time: Date,
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val durationSeconds: Long? = null
)

data class FenceStayRecord(
    val fenceId: String,
    val fenceName: String,
    val enterTime: Date,
    val exitTime: Date? = null,
    val durationSeconds: Long? = null
)

data class DailyFenceStats(
    val date: String,
    val fenceId: String,
    val fenceName: String,
    val totalStayDuration: Long,
    val enterCount: Int,
    val exitCount: Int
)

class MainActivity : ComponentActivity() {

    private val geoFences = mutableStateListOf<GeoFence>()
    private var aMap: AMap? = null
    private lateinit var dbHelper: GeoFenceDatabaseHelper
    private lateinit var sharedPreferences: SharedPreferences

    private val KEY_MAP_TYPE = "mapType"
    private val KEY_TRAFFIC_ENABLED = "trafficEnabled"

    // 设置地图对象
    fun setMap(map: AMap?) {
        this.aMap = map
    }

    // 获取当前地图对象
    fun getCurrentMap(): AMap? {
        return aMap
    } // fenceId to isInside
    
    private val circleOverlays = mutableMapOf<String, Circle>() // fenceId to Circle
    private val polygonOverlays = mutableMapOf<String, Polygon>() // fenceId to Polygon
    private val tempDrawingMarkers = mutableListOf<Marker>() // 临时绘制标记

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

        val app = application as TrackMasterApplication
        dbHelper = app.dbHelper
        sharedPreferences = getSharedPreferences("TrackMaster", Context.MODE_PRIVATE)

        // 直接从数据库加载围栏数据，确保数据一致性
        geoFences.clear()
        val fencesFromDB = dbHelper.getAllFences()
        geoFences.addAll(fencesFromDB)
        Log.d("TrackMaster", "MainActivity从数据库加载了 ${geoFences.size} 个围栏")

        val activityContext = this

        Log.d("TrackMaster", "开始初始化高德 SDK")

        AMapLocationClient.updatePrivacyShow(activityContext, true, true)
        AMapLocationClient.updatePrivacyAgree(activityContext, true)

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

    private fun requestLocationPermission() {
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
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

    private var locationServiceListener: ((LocationServiceState) -> Unit)? = null

    private fun startLocation() {
        try {
            Log.d("TrackMaster", "启动定位服务")
            locationStatus.value = "正在启动定位服务..."
            locationError.value = null

            val app = application as TrackMasterApplication
            app.startLocationService()

            // 延迟一点时间来等待服务绑定，然后添加监听器
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                setupLocationServiceListener()
            }, 500)
        } catch (e: Exception) {
            Log.e("TrackMaster", "启动定位失败", e)
            locationStatus.value = "启动失败"
            locationError.value = e.message
        }
    }

    private fun setupLocationServiceListener() {
        val app = application as TrackMasterApplication
        val service = app.getLocationService()

        if (service != null) {
            // 移除之前的监听器
            locationServiceListener?.let { service.removeStateListener(it) }

            // 创建新的监听器
            locationServiceListener = { state ->
                runOnUiThread {
                    // 更新当前位置
                    state.lastLocation?.let { location ->
                        currentLocation.value = location

                        // 更新状态文本
                        val statusText = when {
                            state.isFirstLocation -> "首次定位中..."
                            state.locationCount == 0 -> "正在获取位置..."
                            else -> "定位成功 (${state.locationCount}次更新)"
                        }
                        locationStatus.value = statusText
                    }

                    // 如果服务未运行，显示错误
                    if (!state.isRunning) {
                        locationStatus.value = "定位服务已停止"
                    }
                }
            }

            // 添加监听器
            locationServiceListener?.let { service.addStateListener(it) }
            Log.d("TrackMaster", "已设置定位服务状态监听")
        } else {
            Log.w("TrackMaster", "定位服务尚未绑定，稍后重试")
            // 服务可能还在绑定中，稍后重试
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                setupLocationServiceListener()
            }, 1000)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 移除定位服务监听器
        val app = application as TrackMasterApplication
        val service = app.getLocationService()
        locationServiceListener?.let { listener ->
            service?.removeStateListener(listener)
        }
    }

    // 从数据库加载围栏
    private fun loadGeoFencesFromDB() {
        val fencesFromDB = dbHelper.getAllFences()
        geoFences.clear()
        geoFences.addAll(fencesFromDB)
        Log.d("TrackMaster", "从数据库加载了 ${fencesFromDB.size} 个围栏")
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
        dbHelper.insertFence(fence)
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
        dbHelper.insertFence(fence)
        Log.d("TrackMaster", "创建多边形围栏: $name, 顶点数: ${points.size}")
    }

    // 删除地理围栏
    fun deleteGeoFence(fenceId: String) {
        val fence = geoFences.find { it.id == fenceId }
        if (fence != null) {
            geoFences.remove(fence)
            dbHelper.deleteFence(fenceId)
            
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
        dbHelper.deleteAllFences()
        
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
    
    // 从数据库刷新围栏数据
    fun refreshGeoFences() {
        geoFences.clear()
        geoFences.addAll(dbHelper.getAllFences())
        Log.d("TrackMaster", "MainActivity刷新了 ${geoFences.size} 个围栏")
    }

    // 获取地理围栏事件
    fun getGeoFenceEvents(): List<GeoFenceEvent> {
        return dbHelper.getEventsByTimeRange(Date(0), Date())
    }

    // 获取用户当前在哪些围栏内
    fun getCurrentFences(): List<GeoFence> {
        val app = applicationContext as? TrackMasterApplication ?: return emptyList()
        val leafId = app.engine.currentLeafId ?: return emptyList()
        return geoFences.filter { it.id == leafId }
    }

    // 获取围栏停留记录
    fun getFenceStayRecords(): List<FenceStayRecord> {
        return dbHelper.getAllStayRecords()
    }

    // 获取围栏停留时间统计
    fun getFenceStats(): Map<String, Long> {
        val stats = mutableMapOf<String, Long>()
        val dbStats = dbHelper.getAllStayRecords()
        dbStats.forEach { record ->
            if (record.durationSeconds != null) {
                stats[record.fenceName] = (stats[record.fenceName] ?: 0) + record.durationSeconds
            }
        }
        return stats
    }

    // 导出CSV
    fun exportData(): String? {
        return dbHelper.exportToCSV(this)
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
                        fence.center?.let { center ->
                            fence.radius?.let { radius ->
                                val circleOptions = CircleOptions()
                                    .center(LatLng(center.latitude, center.longitude))
                                    .radius(radius.toDouble())
                                    .strokeColor(AndroidColor.parseColor("#FF0000"))
                                    .fillColor(AndroidColor.argb(50, 255, 0, 0))
                                    .strokeWidth(2f)
                                val circle = aMap.addCircle(circleOptions)
                                circleOverlays[fence.id] = circle
                            }
                        }
                    }
                }
                GeoFenceType.POLYGON -> {
                    if (!polygonOverlays.containsKey(fence.id)) {
                        fence.points?.let { pointsList ->
                            val points = pointsList.map { LatLng(it.latitude, it.longitude) }
                            val polygonOptions = PolygonOptions()
                                .addAll(points)
                                .strokeColor(AndroidColor.parseColor("#FF0000"))
                                .fillColor(AndroidColor.argb(50, 255, 0, 0))
                                .strokeWidth(2f)
                            val polygon = aMap.addPolygon(polygonOptions)
                            polygonOverlays[fence.id] = polygon
                        }
                    }
                }
            }
        }
    }

    // 添加临时绘制标记
    fun addDrawingMarker(aMap: AMap, point: GeoFencePoint, index: Int) {
        val markerOptions = MarkerOptions()
            .position(LatLng(point.latitude, point.longitude))
            .title("点 ${index + 1}")
            .snippet("${point.latitude}, ${point.longitude}")
            .draggable(false)
        val marker = aMap.addMarker(markerOptions)
        if (marker != null) {
            tempDrawingMarkers.add(marker)
        }
    }

    // 清除所有临时绘制标记
    fun clearDrawingMarkers() {
        tempDrawingMarkers.forEach { it.remove() }
        tempDrawingMarkers.clear()
    }

    // 高亮显示指定围栏
    fun highlightGeoFence(fenceId: String) {
        // 重置所有围栏的样式
        circleOverlays.forEach { (_, circle) ->
            circle.strokeColor = AndroidColor.parseColor("#FF0000")
                circle.strokeWidth = 2f
        }
        polygonOverlays.forEach { (_, polygon) ->
            polygon.strokeColor = AndroidColor.parseColor("#FF0000")
            polygon.strokeWidth = 2f
        }
        // 高亮选中的围栏
        if (circleOverlays.containsKey(fenceId)) {
            val circle = circleOverlays[fenceId]
            circle?.strokeColor = AndroidColor.parseColor("#00FF00")
            circle?.strokeWidth = 4f
        } else if (polygonOverlays.containsKey(fenceId)) {
            val polygon = polygonOverlays[fenceId]
            polygon?.strokeColor = AndroidColor.parseColor("#00FF00")
            polygon?.strokeWidth = 4f
        }
    }

    // 设置地图类型
    fun setMapType(aMap: AMap?, mapType: Int) {
        aMap?.mapType = mapType
        sharedPreferences.edit().putInt(KEY_MAP_TYPE, mapType).apply()
    }

    // 获取保存的地图类型
    fun getSavedMapType(): Int {
        return sharedPreferences.getInt(KEY_MAP_TYPE, AMap.MAP_TYPE_NORMAL)
    }

    // 设置路况显示
    fun setTrafficEnabled(aMap: AMap?, enabled: Boolean) {
        aMap?.isTrafficEnabled = enabled
        sharedPreferences.edit().putBoolean(KEY_TRAFFIC_ENABLED, enabled).apply()
    }

    // 获取保存的路况显示状态
    fun getSavedTrafficEnabled(): Boolean {
        return sharedPreferences.getBoolean(KEY_TRAFFIC_ENABLED, false)
    }


} 

@Composable
fun AMapView(
    currentLocation: AMapLocation?,
    isDrawingMode: Boolean = false,
    drawingPoints: List<GeoFencePoint> = emptyList(),
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

    val hasMovedCamera = remember { mutableStateOf(false) }
    val lastDrawingPointsSize = remember { mutableStateOf(0) }

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
            // 设置地图对象到MainActivity
            mainActivity.setMap(aMap)
            
            aMap.uiSettings.isZoomControlsEnabled = true
            aMap.uiSettings.isCompassEnabled = true
            aMap.uiSettings.isScaleControlsEnabled = true
            aMap.uiSettings.isMyLocationButtonEnabled = true

            // 应用保存的地图设置
            aMap.mapType = mainActivity.getSavedMapType()
            aMap.isTrafficEnabled = mainActivity.getSavedTrafficEnabled()

            try {
                val myLocationStyle = MyLocationStyle()
                myLocationStyle.myLocationType(MyLocationStyle.LOCATION_TYPE_LOCATION_ROTATE_NO_CENTER)
                myLocationStyle.interval(2000)
                myLocationStyle.strokeColor(AndroidColor.parseColor("#3D9BE9"))
                myLocationStyle.radiusFillColor(AndroidColor.argb(50, 63, 155, 233))
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

    // 处理绘制模式下的临时标记
    LaunchedEffect(drawingPoints.size, isDrawingMode, mapView) {
        val aMap = mapView.map
        if (aMap != null) {
            if (isDrawingMode) {
                // 如果点数增加，添加新标记
                if (drawingPoints.size > lastDrawingPointsSize.value) {
                    val newPoint = drawingPoints.last()
                    mainActivity.addDrawingMarker(aMap, newPoint, drawingPoints.size - 1)
                }
                // 如果点数减少（重置），清除所有标记
                else if (drawingPoints.size < lastDrawingPointsSize.value) {
                    mainActivity.clearDrawingMarkers()
                    // 重新添加所有标记
                    drawingPoints.forEachIndexed { index, point ->
                        mainActivity.addDrawingMarker(aMap, point, index)
                    }
                }
                lastDrawingPointsSize.value = drawingPoints.size
            } else {
                // 退出绘制模式，清除所有临时标记
                if (lastDrawingPointsSize.value > 0) {
                    mainActivity.clearDrawingMarkers()
                    lastDrawingPointsSize.value = 0
                }
            }
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
            ) { _ ->
                // 不需要在这里调用 onResume，已经在 DisposableEffect 中处理了生命周期
            }


        }
    }
}

@Composable
fun RadioButtonWithLabel(
    selected: Boolean,
    label: String,
    description: String? = null,
    onSelected: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 6.dp)
            .clickable { onSelected() },
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = onSelected,
            colors = RadioButtonDefaults.colors(
                selectedColor = NeonCoral,
                unselectedColor = TextSecondary
            )
        )
        Column(modifier = Modifier.padding(start = 10.dp)) {
            ResponsiveSubtitleText(
                text = label,
                priority = if (selected) TextPriority.PRIMARY else TextPriority.SECONDARY,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
            )
            if (description != null) {
                Spacer(modifier = Modifier.height(2.dp))
                ResponsiveLabelText(
                    text = description,
                    priority = TextPriority.TERTIARY
                )
            }
        }
    }
}

@Composable
fun FenceItem(fence: GeoFence, mainActivity: MainActivity) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp, horizontal = 4.dp)
            .clickable {
                mainActivity.highlightGeoFence(fence.id)
            },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
        ),
        shape = RoundedCornerShape(6.dp),
        elevation = CardDefaults.cardElevation(1.dp)
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = fence.name,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                Badge(
                    containerColor = when (fence.type) {
                        GeoFenceType.CIRCLE -> MaterialTheme.colorScheme.primary
                        GeoFenceType.POLYGON -> MaterialTheme.colorScheme.secondary
                    }
                ) {
                    Text(
                        text = when (fence.type) {
                            GeoFenceType.CIRCLE -> "圆形"
                            GeoFenceType.POLYGON -> "多边形"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
                IconButton(
                    onClick = { mainActivity.deleteGeoFence(fence.id) },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        Icons.Default.DeleteForever,
                        contentDescription = "删除围栏",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
            Text(
                text = "创建时间: ${fence.createdAt}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

@Composable
fun FenceItemWithMove(
    fence: GeoFence, 
    mainActivity: MainActivity,
    onMove: () -> Unit,
    onDeleteClick: (GeoFence) -> Unit
) {
    AnimatedVisibility(
        visible = true,
        enter = fadeIn(animationSpec = tween(300)) + slideInVertically(
            initialOffsetY = { -40 },
            animationSpec = tween(300)
        ),
        exit = fadeOut(animationSpec = tween(300)) + slideOutVertically(
            targetOffsetY = { 40 },
            animationSpec = tween(300)
        )
    ) {
        GlassCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp, horizontal = 6.dp)
                .clickable {
                    mainActivity.highlightGeoFence(fence.id)
                }
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ResponsiveSubtitleText(
                        text = fence.name,
                        priority = TextPriority.PRIMARY,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    GlassBadge(
                        containerColor = when (fence.type) {
                            GeoFenceType.CIRCLE -> NeonCoral
                            GeoFenceType.POLYGON -> NeonAmber
                        }
                    ) {
                        Text(
                            text = when (fence.type) {
                                GeoFenceType.CIRCLE -> "圆形"
                                GeoFenceType.POLYGON -> "多边形"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (fence.type == GeoFenceType.POLYGON) Color.Black else Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    
                    // 移动围栏按钮 - 使用更直观的图标
                    GlassFloatingActionButton(
                        onClick = onMove,
                        modifier = Modifier.size(48.dp),
                        containerColor = NeonCoral.copy(alpha = 0.4f)
                    ) {
                        Icon(
                            Icons.Default.EditLocation, 
                            contentDescription = "移动/编辑围栏",
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    
                    Spacer(modifier = Modifier.width(8.dp))
                    
                    // 删除围栏按钮 - 使用更鲜明的红色
                    GlassFloatingActionButton(
                        onClick = { onDeleteClick(fence) },
                        modifier = Modifier.size(48.dp),
                        containerColor = Danger.copy(alpha = 0.4f)
                    ) {
                        Icon(
                            Icons.Default.DeleteForever, 
                            contentDescription = "删除围栏",
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                ResponsiveLabelText(
                    text = "创建时间: ${fence.createdAt}",
                    priority = TextPriority.TERTIARY,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
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
    val app = context.applicationContext as TrackMasterApplication
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val configuration = LocalConfiguration.current
    val screenWidthDp = configuration.screenWidthDp
    val screenHeightDp = configuration.screenHeightDp
    val isSmallScreen = screenWidthDp < 360
    val isLargeScreen = screenWidthDp >= 600
    val showGeoFenceDialog = remember { mutableStateOf(false) }
    val fenceName = remember { mutableStateOf("") }
    val fenceRadius = remember { mutableStateOf(100f) }
    val selectedFenceType = remember { mutableStateOf(GeoFenceType.CIRCLE) }
    val isDrawingFence = remember { mutableStateOf(false) }
    val drawingPoints = remember { mutableStateListOf<GeoFencePoint>() }

    // 控制菜单展开状态
    val isMenuExpanded = remember { mutableStateOf(false) }
    // 控制图层对话框显示
    val showLayerDialog = remember { mutableStateOf(false) }
    // 控制历史记录对话框显示
    val showHistoryDialog = remember { mutableStateOf(false) }
    // 控制清理确认对话框显示
    val showClearConfirmation = remember { mutableStateOf(false) }
    // 控制设置对话框显示
    val showSettingsDialog = remember { mutableStateOf(false) }
    // 控制分类管理对话框显示
    val showCategoryManagerDialog = remember { mutableStateOf(false) }
    // 控制数据管理对话框显示
    val showDataManagementDialog = remember { mutableStateOf(false) }
    // 热力图开关
    val heatmapEnabled = remember { mutableStateOf(false) }

    // 分类相关状态
    var categories by remember { mutableStateOf<List<Category>>(emptyList()) }
    var expandedCategories by remember { mutableStateOf<Set<String>>(emptySet()) }
    var categoryDialogClosed by remember { mutableStateOf(false) }
    var showMoveFenceDialog by remember { mutableStateOf<GeoFence?>(null) }
    var refreshDataTrigger by remember { mutableStateOf(0) }
    var showDeleteConfirmDialog by remember { mutableStateOf<GeoFence?>(null) }

    suspend fun loadCategories() {
        val userCategories = app.categoryManager.getAllCategories()
        categories = listOf(Category.UNASSIGNED) + userCategories
    }

    LaunchedEffect(Unit) {
        loadCategories()
    }
    
    LaunchedEffect(categoryDialogClosed) {
        if (categoryDialogClosed) {
            loadCategories()
            categoryDialogClosed = false
        }
    }
    
    LaunchedEffect(refreshDataTrigger) {
        if (refreshDataTrigger > 0) {
            loadCategories()
        }
    }

    val isBottomSheetExpanded = remember { mutableStateOf(false) }
    
    // 全屏一体化布局
    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        // 地图作为全屏底色 (z-index: 1)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (isBottomSheetExpanded.value) {
                        Modifier.blur(radius = 8.dp)
                    } else {
                        Modifier
                    }
                )
        ) {
            AMapView(
                currentLocation = currentLocation,
                isDrawingMode = isDrawingFence.value,
                drawingPoints = drawingPoints.toList(),
                onMapClick = { latLng ->
                    if (isDrawingFence.value) {
                        drawingPoints.add(GeoFencePoint(latLng.latitude, latLng.longitude))
                    }
                }
            )
            // 深色叠加层，让地图更安静
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(color = Color(0x33000000)) // rgba(0, 0, 0, 0.2)
            )
        }

        // SnackbarHost
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = if (isBottomSheetExpanded.value) 400.dp else 200.dp)
        )

        // 顶部悬浮卡片 (z-index: 10)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp)
                .align(Alignment.TopCenter)
        ) {
            GlassTopCard {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "轨迹大师",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = NeonCoral,
                            modifier = Modifier.weight(1f)
                        )
                        
                        // 添加分类按钮 - 始终显示，使用图标 + 文字
                        GlassButton(
                            onClick = { showCategoryManagerDialog.value = true },
                            modifier = Modifier.height(36.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    Icons.Default.Add,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = "添加分类",
                                    fontSize = 12.sp,
                                    color = Color.White,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }

                        GlassBadge(
                            containerColor = when {
                                locationStatus.contains("成功") -> Success
                                locationStatus.contains("失败") || locationStatus.contains("拒绝") -> Danger
                                else -> Color(0xFF757575)
                            }
                        ) {
                            Text(
                                text = locationStatus,
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp)) // 增加间距

                    if (currentLocation != null && currentLocation.errorCode == 0) {
                        Text(
                            text = "经纬度: ${String.format("%.6f", currentLocation.longitude)}, ${String.format("%.6f", currentLocation.latitude)} | 精度: ${currentLocation.accuracy.toInt()}m",
                            style = MaterialTheme.typography.bodySmall,
                            color = NeonCoral.copy(alpha = 0.9f)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                    }

                    // 显示当前在哪些围栏内
                    val currentFences = mainActivity.getCurrentFences()
                    if (currentFences.isNotEmpty()) {
                        Text(
                            text = "当前在: ${currentFences.joinToString { it.name }}",
                            style = MaterialTheme.typography.bodySmall,
                            color = NeonAmber.copy(alpha = 0.9f),
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                    }

                    // 显示绘制状态
                    if (isDrawingFence.value) {
                        Text(
                            text = "绘制模式: ${drawingPoints.size}/3 点",
                            style = MaterialTheme.typography.bodySmall,
                            color = Info,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                    }

                    if (locationError != null) {
                        Text(
                            text = locationError,
                            style = MaterialTheme.typography.bodySmall,
                            color = Danger
                        )
                    }
                }
            }
        }

        // 右侧浮动操作按钮 (z-index: 15)
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 10.dp, bottom = 180.dp)
        ) {
            if (isDrawingFence.value) {
                Column {
                    GlassFloatingActionButton(
                        onClick = {
                            if (drawingPoints.size >= 3) {
                                if (fenceName.value.isNotEmpty()) {
                                    mainActivity.createPolygonGeoFence(
                                        name = fenceName.value,
                                        points = drawingPoints.toList()
                                    )
                                    mainActivity.clearDrawingMarkers()
                                    isDrawingFence.value = false
                                    drawingPoints.clear()
                                    fenceName.value = ""
                                } else {
                                    showGeoFenceDialog.value = true
                                }
                            }
                        },
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(Icons.Default.Check, contentDescription = "完成", tint = Color.Black, modifier = Modifier.size(20.dp))
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    GlassFloatingActionButton(
                        onClick = {
                            mainActivity.clearDrawingMarkers()
                            isDrawingFence.value = false
                            drawingPoints.clear()
                        },
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "取消", tint = Color.Black, modifier = Modifier.size(20.dp))
                    }
                }
            } else {
                Column {
                    AnimatedVisibility(
                        visible = isMenuExpanded.value,
                        enter = fadeIn(animationSpec = tween(200)) + scaleIn(initialScale = 0.85f),
                        exit = fadeOut(animationSpec = tween(200)) + scaleOut(targetScale = 0.85f)
                    ) {
                        Column {
                            GlassFloatingActionButton(
                                onClick = { showGeoFenceDialog.value = true },
                                modifier = Modifier.size(44.dp)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = "添加围栏", tint = Color.Black, modifier = Modifier.size(20.dp))
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            GlassFloatingActionButton(
                                onClick = { showLayerDialog.value = true },
                                modifier = Modifier.size(44.dp)
                            ) {
                                Icon(Icons.Default.Layers, contentDescription = "图层", tint = Color.Black, modifier = Modifier.size(20.dp))
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            GlassFloatingActionButton(
                                onClick = { showHistoryDialog.value = true },
                                modifier = Modifier.size(44.dp)
                            ) {
                                Icon(Icons.Default.History, contentDescription = "历史", tint = Color.Black, modifier = Modifier.size(20.dp))
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            GlassFloatingActionButton(
                                onClick = { showSettingsDialog.value = true },
                                modifier = Modifier.size(44.dp)
                            ) {
                                Icon(Icons.Default.Settings, contentDescription = "设置", tint = Color.Black, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    GlassFloatingActionButton(
                        onClick = { isMenuExpanded.value = !isMenuExpanded.value },
                        modifier = Modifier.size(50.dp)
                    ) {
                        Icon(
                            if (isMenuExpanded.value) Icons.Default.ExpandLess else Icons.Default.Menu,
                            contentDescription = if (isMenuExpanded.value) "收起" else "菜单",
                            tint = Color.Black,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        }

        // 底部一体化悬浮卡片 (z-index: 10)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
        ) {
            GlassBottomSheetCard(
                isExpanded = isBottomSheetExpanded.value,
                onExpandChange = { isBottomSheetExpanded.value = it },
                peekHeight = 0.15f
            ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "地理围栏",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = NeonCoral
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "${mainActivity.getGeoFences().size} 个",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        val allFences = mainActivity.getGeoFences()
                        
                        if (allFences.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(20.dp)
                                    .background(GlassPanelRich)
                                    .border(
                                        width = 1.dp,
                                        color = GlassBorder,
                                        shape = RoundedCornerShape(8.dp)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "暂无围栏",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFFBDBDBD)
                                )
                            }
                        } else {
                            LazyColumn(modifier = Modifier.fillMaxHeight()) {
                                // "全部分类"作为容器
                                item {
                                    val isExpanded = expandedCategories.contains("all")
                                    
                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp)
                                            .clickable {
                                                expandedCategories = if (isExpanded) {
                                                    expandedCategories - "all"
                                                } else {
                                                    expandedCategories + "all"
                                                }
                                            },
                                        colors = CardDefaults.cardColors(
                                            containerColor = GlassPanelRich
                                        ),
                                        shape = RoundedCornerShape(10.dp),
                                        elevation = CardDefaults.cardElevation(0.dp)
                                    ) {
                                        Column(modifier = Modifier.padding(10.dp)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = "全部分类",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    fontWeight = FontWeight.Bold,
                                                    color = NeonCoral,
                                                    modifier = Modifier.weight(1f)
                                                )
                                                Text(
                                                    text = "${allFences.size} 个",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = Color(0xFFBDBDBD)
                                                )
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(
                                                    text = if (isExpanded) "▲" else "▼",
                                                    fontSize = 14.sp,
                                                    color = NeonCoral
                                                )
                                            }
                                            if (isExpanded) {
                                                Spacer(modifier = Modifier.height(8.dp))
                                                // 在"全部分类"下列出所有分类
                                                categories.forEach { category ->
                                                    val fencesInCategory = allFences.filter { it.categoryId == category.id }
                                                    val categoryExpanded = expandedCategories.contains(category.id)
                                                    
                                                    Card(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .padding(vertical = 4.dp, horizontal = 8.dp)
                                                            .clickable {
                                                                expandedCategories = if (categoryExpanded) {
                                                                    expandedCategories - category.id
                                                                } else {
                                                                    expandedCategories + category.id
                                                                }
                                                            },
                                                        colors = CardDefaults.cardColors(
                                                            containerColor = GlassPanelLightRich
                                                        ),
                                                        shape = RoundedCornerShape(8.dp),
                                                        elevation = CardDefaults.cardElevation(0.dp)
                                                    ) {
                                                        Column(modifier = Modifier.padding(8.dp)) {
                                                            Row(
                                                                modifier = Modifier.fillMaxWidth(),
                                                                verticalAlignment = Alignment.CenterVertically
                                                            ) {
                                                                Text(
                                                                    text = category.icon ?: "📁",
                                                                    fontSize = 18.sp,
                                                                    modifier = Modifier.padding(end = 8.dp)
                                                                )
                                                                Text(
                                                                    text = category.name,
                                                                    style = MaterialTheme.typography.bodySmall,
                                                                    fontWeight = FontWeight.Medium,
                                                                    color = Color(0xFFE0E0E0),
                                                                    modifier = Modifier.weight(1f)
                                                                )
                                                                Text(
                                                                    text = "${fencesInCategory.size} 个",
                                                                    style = MaterialTheme.typography.labelSmall,
                                                                    color = Color(0xFFBDBDBD)
                                                                )
                                                                Spacer(modifier = Modifier.width(4.dp))
                                                                Text(
                                                                    text = if (categoryExpanded) "▲" else "▼",
                                                                    fontSize = 12.sp,
                                                                    color = NeonAmber
                                                                )
                                                            }
                                                            if (categoryExpanded) {
                                                                Spacer(modifier = Modifier.height(6.dp))
                                                                if (fencesInCategory.isEmpty()) {
                                                                    Box(
                                                                        modifier = Modifier
                                                                            .fillMaxWidth()
                                                                            .padding(8.dp),
                                                                        contentAlignment = Alignment.Center
                                                                    ) {
                                                                        Text(
                                                                            text = "该分类暂无围栏",
                                                                            style = MaterialTheme.typography.bodySmall,
                                                                            color = Color(0xFF9E9E9E)
                                                                        )
                                                                    }
                                                                } else {
                                                                    Column {
                                                                        fencesInCategory.forEach { fence ->
                                                                            FenceItemWithMove(
                                                                                fence = fence,
                                                                                mainActivity = mainActivity,
                                                                                onMove = { showMoveFenceDialog = fence },
                                                                                onDeleteClick = { showDeleteConfirmDialog = it }
                                                                            )
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
                                }
                            }
                        }
                    }
            }
        }
    }

    // 创建地理围栏对话框
    if (showGeoFenceDialog.value) {
        GlassDialog(
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
                            color = NeonCoral
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        GlassTextField(
                            value = fenceName.value,
                            onValueChange = { fenceName.value = it },
                            label = { Text("围栏名称") }
                        )
                    } else {
                        GlassTextField(
                            value = fenceName.value,
                            onValueChange = { fenceName.value = it },
                            label = { Text("围栏名称") }
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        // 滑动标签切换器
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp)
                                .background(
                                    color = Color(0x4D2A221C),
                                    shape = RoundedCornerShape(22.dp)
                                )
                        ) {
                            // 滑动背景指示器 - 始终可见，只是位置变化
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth(0.5f)
                                    .align(
                                        if (selectedFenceType.value == GeoFenceType.CIRCLE) 
                                            Alignment.CenterStart 
                                        else 
                                            Alignment.CenterEnd
                                    )
                                    .padding(2.dp)
                                    .background(
                                        brush = Brush.linearGradient(
                                            colors = listOf(NeonCoral, NeonAmber),
                                            start = androidx.compose.ui.geometry.Offset(0f, 0f),
                                            end = androidx.compose.ui.geometry.Offset(100f, 100f)
                                        ),
                                        shape = RoundedCornerShape(20.dp)
                                    )
                                    .animateContentSize()
                            )
                            
                            Row(
                                modifier = Modifier.fillMaxSize(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .clickable {
                                            selectedFenceType.value = GeoFenceType.CIRCLE
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "圆形围栏",
                                        color = if (selectedFenceType.value == GeoFenceType.CIRCLE) 
                                            Color.White 
                                            else 
                                            Color(0xFFBDBDBD),
                                        fontWeight = if (selectedFenceType.value == GeoFenceType.CIRCLE) 
                                            FontWeight.Bold 
                                            else 
                                            FontWeight.Normal
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .clickable {
                                            selectedFenceType.value = GeoFenceType.POLYGON
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "多边形围栏",
                                        color = if (selectedFenceType.value == GeoFenceType.POLYGON) 
                                            Color.White 
                                            else 
                                            Color(0xFFBDBDBD),
                                        fontWeight = if (selectedFenceType.value == GeoFenceType.POLYGON) 
                                            FontWeight.Bold 
                                            else 
                                            FontWeight.Normal
                                    )
                                }
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        // 半径输入框 - 带透明度动画
                        AnimatedVisibility(
                            visible = selectedFenceType.value == GeoFenceType.CIRCLE,
                            enter = fadeIn() + scaleIn(),
                            exit = fadeOut() + scaleOut()
                        ) {
                            GlassTextField(
                                value = fenceRadius.value.toString(),
                                onValueChange = { fenceRadius.value = it.toFloatOrNull() ?: 100f },
                                label = { Text("半径 (米)") }
                            )
                        }
                        
                        // 绘制按钮 - 带透明度动画
                        AnimatedVisibility(
                            visible = selectedFenceType.value == GeoFenceType.POLYGON,
                            enter = fadeIn() + scaleIn(),
                            exit = fadeOut() + scaleOut()
                        ) {
                            Column {
                                Spacer(modifier = Modifier.height(8.dp))
                                GlassButton(
                                    onClick = { 
                                        isDrawingFence.value = true
                                        drawingPoints.clear()
                                        showGeoFenceDialog.value = false // 关闭对话框，让用户能在地图上点击
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("开始绘制多边形围栏", color = Color.White)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                if (isDrawingFence.value) {
                    GlassButton(onClick = {
                        if (drawingPoints.size >= 3 && fenceName.value.isNotEmpty()) {
                            mainActivity.createPolygonGeoFence(
                                name = fenceName.value,
                                points = drawingPoints.toList()
                            )
                            mainActivity.clearDrawingMarkers() // 清除临时标记
                            showGeoFenceDialog.value = false
                            isDrawingFence.value = false
                            drawingPoints.clear()
                            fenceName.value = ""
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    message = "多边形围栏创建成功",
                                    duration = SnackbarDuration.Short
                                )
                            }
                        }
                    }) {
                        Text("完成", color = Color.White)
                    }
                } else if (selectedFenceType.value == GeoFenceType.CIRCLE) {
                    GlassButton(onClick = {
                        if (fenceName.value.isNotEmpty() && currentLocation != null) {
                            mainActivity.createCircleGeoFence(
                                name = fenceName.value,
                                latitude = currentLocation.latitude,
                                longitude = currentLocation.longitude,
                                radius = fenceRadius.value
                            )
                            showGeoFenceDialog.value = false
                            fenceName.value = ""
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    message = "围栏创建成功",
                                    duration = SnackbarDuration.Short
                                )
                            }
                        }
                    }) {
                        Text("创建", color = Color.White)
                    }
                } else {
                    GlassButton(onClick = {
                        showGeoFenceDialog.value = false
                    }) {
                        Text("关闭", color = Color.White)
                    }
                }
            },
            dismissButton = {
                if (isDrawingFence.value) {
                    GlassButton(onClick = {
                        mainActivity.clearDrawingMarkers() // 清除临时标记
                        showGeoFenceDialog.value = false
                        isDrawingFence.value = false
                        drawingPoints.clear()
                    }) {
                        Text("取消", color = Color.White)
                    }
                } else {
                    GlassButton(onClick = {
                        showGeoFenceDialog.value = false
                    }) {
                        Text("取消", color = Color.White)
                    }
                }
            }
        )
    }

    // 图层选择对话框
    if (showLayerDialog.value) {
        val mapType = remember { mutableStateOf(mainActivity.getSavedMapType()) }
        val showTraffic = remember { mutableStateOf(mainActivity.getSavedTrafficEnabled()) }
        
        GlassDialog(
            onDismissRequest = { showLayerDialog.value = false },
            title = { 
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Layers, contentDescription = "图层", tint = NeonCoral)
                    Spacer(modifier = Modifier.width(8.dp))
                    ResponsiveTitleText(text = "选择地图图层", priority = TextPriority.PRIMARY)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    // 地图类型选择
                    GlassCard(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Map, contentDescription = "地图", tint = NeonCoral)
                                Spacer(modifier = Modifier.width(8.dp))
                                ResponsiveSubtitleText(text = "地图类型", priority = TextPriority.ACCENT, fontWeight = FontWeight.Bold)
                            }
                            
                            RadioButtonWithLabel(
                                selected = mapType.value == AMap.MAP_TYPE_NORMAL,
                                label = "白昼地图",
                                description = "标准矢量地图，适合日间使用",
                                onSelected = { mapType.value = AMap.MAP_TYPE_NORMAL }
                            )
                            RadioButtonWithLabel(
                                selected = mapType.value == AMap.MAP_TYPE_NIGHT,
                                label = "夜景地图",
                                description = "深色地图，保护夜间视力",
                                onSelected = { mapType.value = AMap.MAP_TYPE_NIGHT }
                            )
                            RadioButtonWithLabel(
                                selected = mapType.value == AMap.MAP_TYPE_SATELLITE,
                                label = "卫星地图",
                                description = "真实卫星影像，查看地表细节",
                                onSelected = { mapType.value = AMap.MAP_TYPE_SATELLITE }
                            )
                            RadioButtonWithLabel(
                                selected = mapType.value == AMap.MAP_TYPE_NAVI,
                                label = "导航地图",
                                description = "简化地图，专注导航信息",
                                onSelected = { mapType.value = AMap.MAP_TYPE_NAVI }
                            )
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    // 高级图层开关
                    GlassCard(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Tune, contentDescription = "高级", tint = NeonAmber)
                                Spacer(modifier = Modifier.width(8.dp))
                                ResponsiveSubtitleText(text = "高级图层", priority = TextPriority.PRIMARY, fontWeight = FontWeight.Bold)
                            }
                            
                            // 路况图层
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Traffic, contentDescription = "路况", tint = NeonCoral)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        ResponsiveSubtitleText(text = "路况图层", priority = TextPriority.PRIMARY)
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    ResponsiveLabelText(
                                        text = "实时显示道路交通拥堵情况",
                                        priority = TextPriority.TERTIARY
                                    )
                                }
                                Switch(
                                    checked = showTraffic.value,
                                    onCheckedChange = { showTraffic.value = it },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = NeonCoral,
                                        checkedTrackColor = NeonCoral.copy(alpha = 0.5f),
                                        uncheckedThumbColor = TextSecondary,
                                        uncheckedTrackColor = TextTertiary.copy(alpha = 0.3f)
                                    )
                                )
                            }
                            
                            // 热力图层
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Whatshot, contentDescription = "热力图", tint = Danger)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        ResponsiveSubtitleText(text = "热力图", priority = TextPriority.PRIMARY)
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    ResponsiveLabelText(
                                        text = "显示围栏访问热度，颜色越深越频繁",
                                        priority = TextPriority.TERTIARY
                                    )
                                }
                                Switch(
                                    checked = heatmapEnabled.value,
                                    onCheckedChange = { heatmapEnabled.value = it },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Danger,
                                        checkedTrackColor = Danger.copy(alpha = 0.5f),
                                        uncheckedThumbColor = TextSecondary,
                                        uncheckedTrackColor = TextTertiary.copy(alpha = 0.3f)
                                    )
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                GlassButton(onClick = {
                    // 应用图层设置
                    val aMap = mainActivity.getCurrentMap()
                    mainActivity.setMapType(aMap, mapType.value)
                    mainActivity.setTrafficEnabled(aMap, showTraffic.value)
                    // 应用热力图
                    if (aMap != null) {
                        val overlay = HeatmapOverlay(aMap)
                        if (heatmapEnabled.value) {
                            scope.launch {
                                val summaries = try {
                                    val today = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                                    app.aggregator.aggregate(today)
                                } catch (_: Exception) { emptyList() }
                                overlay.update(app.geoFences.mapNotNull { f ->
                                    try {
                                        com.trackmaster.app.core.Geofence(
                                            id = f.id, name = f.name,
                                            type = com.trackmaster.app.core.GeoFenceType.valueOf(f.type.name),
                                            polygon = if (f.type == com.trackmaster.app.GeoFenceType.CIRCLE) {
                                                com.trackmaster.app.core.GeoMath.circleToPolygon(
                                                    com.trackmaster.app.core.GeoPoint(f.center!!.latitude, f.center!!.longitude),
                                                    f.radius?.toDouble() ?: 0.0, 32
                                                )
                                            } else {
                                                f.points?.map { com.trackmaster.app.core.GeoPoint(it.latitude, it.longitude) } ?: emptyList()
                                            }
                                        )
                                    } catch (_: Exception) { null }
                                }, summaries)
                            }
                        } else {
                            overlay.clear()
                        }
                    }
                    showLayerDialog.value = false
                }) {
                    Text("确定", color = Color.White)
                }
            },
            dismissButton = {
                GlassButton(onClick = {
                    showLayerDialog.value = false
                }) {
                    Text("取消", color = Color.White)
                }
            }
        )
    }

    // 删除确认对话框
    if (showClearConfirmation.value) {
        GlassDialog(
            onDismissRequest = { showClearConfirmation.value = false },
            title = { 
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, contentDescription = "警告", tint = Danger)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("警告")
                }
            },
            text = { 
                Column {
                    Text("此操作将删除所有图层和围栏数据，且无法恢复。")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("是否继续？", fontWeight = FontWeight.Bold)
                }
            },
            confirmButton = {
                GlassButton(
                    onClick = { 
                        showClearConfirmation.value = false 
                        mainActivity.clearAllGeoFences() 
                    }
                ) {
                    Text("确认删除", color = Color.White)
                }
            },
            dismissButton = {
                GlassButton(onClick = { showClearConfirmation.value = false }) {
                    Text("取消", color = Color.White)
                }
            }
        )
    }

    // 统计数据可视化对话框
    if (showHistoryDialog.value) {
        StatisticsScreen(
            onDismiss = { showHistoryDialog.value = false },
            statisticsManager = app.statisticsManager
        )
    }

    // 设置对话框
    if (showSettingsDialog.value) {
        SettingsScreen(
            onDismiss = { showSettingsDialog.value = false },
            onOpenDataManagement = {
                showSettingsDialog.value = false
                showDataManagementDialog.value = true
            }
        )
    }

    // 数据管理对话框
    if (showDataManagementDialog.value) {
        DataManagementScreen(
            onDismiss = { 
                showDataManagementDialog.value = false
                mainActivity.refreshGeoFences()
                refreshDataTrigger++
            },
            dataManager = app.dataManager
        )
    }

    // 分类管理对话框
    if (showCategoryManagerDialog.value) {
        CategoryManagerScreen(
            onDismiss = {
                showCategoryManagerDialog.value = false
                categoryDialogClosed = true
            }
        )
    }

    // 移动围栏对话框
    showMoveFenceDialog?.let { fence ->
        MoveFenceDialog(
            fence = fence,
            categories = categories,
            onDismiss = { showMoveFenceDialog = null },
            onConfirm = { newCategoryId ->
                scope.launch {
                    val result = app.categoryManager.moveFenceToCategory(fence.id, newCategoryId)
                    if (result.isSuccess) {
                        showMoveFenceDialog = null
                        mainActivity.refreshGeoFences()
                        refreshDataTrigger++
                    }
                }
            }
        )
    }

    // 删除围栏确认对话框
    showDeleteConfirmDialog?.let { fence ->
        GlassDialog(
            onDismissRequest = { showDeleteConfirmDialog = null },
            title = { Text("删除围栏") },
            text = {
                Text("确定要删除「${fence.name}」吗？")
            },
            confirmButton = {
                GlassButton(
                    onClick = {
                        val fenceNameToDelete = fence.name
                        mainActivity.deleteGeoFence(fence.id)
                        showDeleteConfirmDialog = null
                        mainActivity.refreshGeoFences()
                        refreshDataTrigger++
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                message = "已删除围栏「${fenceNameToDelete}」",
                                duration = SnackbarDuration.Short
                            )
                        }
                    }
                ) {
                    Text("确认", color = Color.White)
                }
            },
            dismissButton = {
                GlassButton(onClick = { showDeleteConfirmDialog = null }) {
                    Text("取消", color = Color.White)
                }
            }
        )
    }

    // 移除分类式围栏列表对话框（已不再需要）
}