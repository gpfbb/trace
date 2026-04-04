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
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
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
import com.amap.api.maps.model.Marker
import kotlinx.coroutines.launch
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.maps.model.MyLocationStyle
import com.amap.api.maps.model.Polygon
import com.amap.api.maps.model.PolygonOptions
import com.trackmaster.app.ui.theme.TrackMasterTheme
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

class MainActivity : ComponentActivity(), AMapLocationListener {

    private var locationClient: AMapLocationClient? = null
    private val geoFences = mutableStateListOf<GeoFence>()
    private val currentFenceStates = mutableMapOf<String, Boolean>()
    private val fenceEnterTimes = mutableMapOf<String, Date>()
    private val stableLocationCount = mutableMapOf<String, Int>()
    private var aMap: AMap? = null
    private lateinit var dbHelper: GeoFenceDatabaseHelper
    private lateinit var sharedPreferences: SharedPreferences

    private val MIN_CONSECUTIVE_LOCATIONS = 3
    private val MAX_LOCATION_ACCURACY = 50f
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
            
            val app = application as TrackMasterApplication
            app.startLocationService()
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
            
            // 围栏检测由 GeoFenceLocationService → TrackMasterApplication.checkGeoFences() 统一处理
            // 此处不再重复调用，避免双重事件记录
        } else {
            locationStatus.value = "定位失败"
            locationError.value = "${location.errorInfo} (错误码: ${location.errorCode})"
            Log.e("TrackMaster", "定位错误: ${location.errorInfo}, 错误码: ${location.errorCode}")
        }
    }

    override fun onDestroy() {
        locationClient?.stopLocation()
        locationClient?.onDestroy()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        locationClient?.startLocation()
    }

    override fun onPause() {
        locationClient?.stopLocation()
        super.onPause()
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

        // 检查位置精度，避免使用精度过低的位置
        if (location.accuracy > MAX_LOCATION_ACCURACY) {
            Log.d("TrackMaster", "位置精度不足: ${location.accuracy}m，跳过围栏检测")
            return
        }

        for (fence in geoFences) {
            val isInside = when (fence.type) {
                GeoFenceType.CIRCLE -> isPointInCircle(userPoint, fence)
                GeoFenceType.POLYGON -> isPointInPolygon(userPoint, fence)
            }

            val previousState = currentFenceStates[fence.id] ?: false
            val currentCount = stableLocationCount[fence.id] ?: 0

            if (isInside) {
                if (!previousState) {
                    stableLocationCount[fence.id] = currentCount + 1
                    if (stableLocationCount[fence.id]!! >= MIN_CONSECUTIVE_LOCATIONS) {
                        recordFenceEnter(fence, location)
                        currentFenceStates[fence.id] = true
                        stableLocationCount[fence.id] = 0
                    }
                }
            } else {
                if (previousState) {
                    stableLocationCount[fence.id] = currentCount + 1
                    if (stableLocationCount[fence.id]!! >= MIN_CONSECUTIVE_LOCATIONS) {
                        recordFenceExit(fence, location)
                        currentFenceStates[fence.id] = false
                        stableLocationCount[fence.id] = 0
                    }
                } else {
                    stableLocationCount[fence.id] = 0
                }
            }
        }
    }

    private fun recordFenceEnter(fence: GeoFence, location: AMapLocation) {
        val now = Date()
        fenceEnterTimes[fence.id] = now
        val event = GeoFenceEvent(
            fenceId = fence.id,
            fenceName = fence.name,
            action = GeoFenceAction.ENTER,
            time = now,
            latitude = location.latitude,
            longitude = location.longitude,
            accuracy = location.accuracy
        )
        dbHelper.insertEvent(event)
        Log.d("TrackMaster", "进入围栏: ${fence.name} at $now")
    }

    private fun recordFenceExit(fence: GeoFence, location: AMapLocation) {
        val now = Date()
        val enterTime = fenceEnterTimes[fence.id]
        val durationSeconds = if (enterTime != null) {
            (now.time - enterTime.time) / 1000
        } else null

        if (enterTime != null) {
            val record = FenceStayRecord(
                fenceId = fence.id,
                fenceName = fence.name,
                enterTime = enterTime,
                exitTime = now,
                durationSeconds = durationSeconds
            )
            dbHelper.insertStayRecord(record)
        }

        val event = GeoFenceEvent(
            fenceId = fence.id,
            fenceName = fence.name,
            action = GeoFenceAction.EXIT,
            time = now,
            latitude = location.latitude,
            longitude = location.longitude,
            accuracy = location.accuracy,
            durationSeconds = durationSeconds
        )
        dbHelper.insertEvent(event)
        fenceEnterTimes.remove(fence.id)
        Log.d("TrackMaster", "退出围栏: ${fence.name}, 停留: ${durationSeconds ?: 0}秒")
    }

    // 从数据库加载围栏
    private fun loadGeoFencesFromDB() {
        val fencesFromDB = dbHelper.getAllFences()
        geoFences.clear()
        geoFences.addAll(fencesFromDB)
        fencesFromDB.forEach { fence ->
            currentFenceStates[fence.id] = false
        }
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
        currentFenceStates[fence.id] = false
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
        currentFenceStates[fence.id] = false
        dbHelper.insertFence(fence)
        Log.d("TrackMaster", "创建多边形围栏: $name, 顶点数: ${points.size}")
    }

    // 删除地理围栏
    fun deleteGeoFence(fenceId: String) {
        val fence = geoFences.find { it.id == fenceId }
        if (fence != null) {
            geoFences.remove(fence)
            currentFenceStates.remove(fenceId)
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
        currentFenceStates.clear()
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
        return geoFences.filter { 
            val state = currentFenceStates[it.id]
            state == true
        }
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
                    modifier = Modifier.size(24.dp)
                ) {
                    Text("×", color = MaterialTheme.colorScheme.error)
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
                Spacer(modifier = Modifier.width(4.dp))
                IconButton(
                    onClick = onMove,
                    modifier = Modifier.size(28.dp)
                ) {
                    Text("⇄", color = MaterialTheme.colorScheme.primary, fontSize = 16.sp)
                }
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(
                    onClick = { onDeleteClick(fence) },
                    modifier = Modifier.size(28.dp)
                ) {
                    Text("×", color = MaterialTheme.colorScheme.error, fontSize = 20.sp)
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
fun MainScreen(
    currentLocation: AMapLocation?,
    locationStatus: String,
    locationError: String?
) {
    val context = LocalContext.current
    val mainActivity = context as MainActivity
    val app = context.applicationContext as TrackMasterApplication
    val scope = rememberCoroutineScope()
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
    val showSecondClearConfirmation = remember { mutableStateOf(false) }
    // 控制设置对话框显示
    val showSettingsDialog = remember { mutableStateOf(false) }
    // 控制分类管理对话框显示
    val showCategoryManagerDialog = remember { mutableStateOf(false) }
    // 控制数据管理对话框显示
    val showDataManagementDialog = remember { mutableStateOf(false) }

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

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        floatingActionButton = {
            if (isDrawingFence.value) {
                // 绘制模式下的按钮
                Column {
                    FloatingActionButton(
                        onClick = {
                            if (drawingPoints.size >= 3) {
                                if (fenceName.value.isNotEmpty()) {
                                    mainActivity.createPolygonGeoFence(
                                        name = fenceName.value,
                                        points = drawingPoints.toList()
                                    )
                                    mainActivity.clearDrawingMarkers() // 清除临时标记
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
                            mainActivity.clearDrawingMarkers() // 清除临时标记
                            isDrawingFence.value = false
                            drawingPoints.clear()
                        },
                        containerColor = MaterialTheme.colorScheme.error
                    ) {
                        Text("取消")
                    }
                }
            } else {
                // 正常模式下的可收起菜单
                Column {
                    if (isMenuExpanded.value) {
                        // 展开状态下的按钮
                        FloatingActionButton(
                            onClick = { showGeoFenceDialog.value = true },
                            containerColor = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(48.dp)
                        ) {
                            Text("+")
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        FloatingActionButton(
                            onClick = { showClearConfirmation.value = true },
                            containerColor = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(48.dp)
                        ) {
                            Text("删", color = androidx.compose.ui.graphics.Color.White, fontWeight = FontWeight.Bold)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        FloatingActionButton(
                            onClick = { showLayerDialog.value = true },
                            containerColor = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(48.dp)
                        ) {
                            Text("图")
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        FloatingActionButton(
                            onClick = { showHistoryDialog.value = true },
                            containerColor = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(48.dp)
                        ) {
                            Text("记")
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        FloatingActionButton(
                            onClick = { showSettingsDialog.value = true },
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(48.dp)
                        ) {
                            Text("⚙")
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    // 主按钮
                    FloatingActionButton(
                        onClick = { isMenuExpanded.value = !isMenuExpanded.value },
                        containerColor = if (isMenuExpanded.value) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
                    ) {
                        Text(if (isMenuExpanded.value) "▼" else "≡")
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
                        .padding(8.dp)
                        .shadow(elevation = 4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "轨迹大师",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f)
                            )
                            
                            Button(
                                onClick = { showCategoryManagerDialog.value = true },
                                modifier = Modifier.height(36.dp)
                            ) {
                                Text("添加分类", fontSize = 12.sp)
                            }
                            
                            Spacer(modifier = Modifier.width(8.dp))

                            Badge(
                                containerColor = when {
                                    locationStatus.contains("成功") -> MaterialTheme.colorScheme.primary
                                    locationStatus.contains("失败") || locationStatus.contains("拒绝") -> MaterialTheme.colorScheme.error
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            ) {
                                Text(
                                    text = locationStatus,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            }
                        }

                        if (locationError != null) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f))
                                    .padding(8.dp)
                                    .border(
                                        width = 1.dp,
                                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.5f),
                                        shape = RoundedCornerShape(8.dp)
                                    )
                            ) {
                                Text(
                                    text = locationError,
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                        }

                        if (currentLocation != null && currentLocation.errorCode == 0) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f))
                                    .padding(8.dp)
                                    .border(
                                        width = 1.dp,
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                                        shape = RoundedCornerShape(8.dp)
                                    )
                            ) {
                                Text(
                                    text = "经纬度: ${String.format("%.6f", currentLocation.longitude)}, ${String.format("%.6f", currentLocation.latitude)} | 精度: ${currentLocation.accuracy.toInt()}m",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                        }

                        // 显示当前在哪些围栏内
                        val currentFences = mainActivity.getCurrentFences()
                        if (currentFences.isNotEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.2f))
                                    .padding(8.dp)
                                    .border(
                                        width = 1.dp,
                                        color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f),
                                        shape = RoundedCornerShape(8.dp)
                                    )
                            ) {
                                Text(
                                    text = "当前在围栏内: ${currentFences.joinToString { it.name }}",
                                    color = MaterialTheme.colorScheme.secondary,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                        }

                        // 显示绘制状态
                        if (isDrawingFence.value) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.2f))
                                    .padding(8.dp)
                                    .border(
                                        width = 1.dp,
                                        color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.5f),
                                        shape = RoundedCornerShape(8.dp)
                                    )
                            ) {
                                Text(
                                    text = "绘制模式：已点击 ${drawingPoints.size} 个点（至少需要3个点）",
                                    color = MaterialTheme.colorScheme.tertiary,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
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
                }

                // 地理围栏列表（分类展示）
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp)
                        .heightIn(min = 100.dp, max = 300.dp)
                        .shadow(elevation = 4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f)
                    ),
                    shape = RoundedCornerShape(12.dp)
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
                                color = MaterialTheme.colorScheme.primary
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
                                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
                                    .border(
                                        width = 1.dp,
                                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                                        shape = RoundedCornerShape(8.dp)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "暂无围栏",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
                                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                                        ),
                                        shape = RoundedCornerShape(8.dp),
                                        elevation = CardDefaults.cardElevation(2.dp)
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
                                                    color = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.weight(1f)
                                                )
                                                Text(
                                                    text = "${allFences.size} 个",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(
                                                    text = if (isExpanded) "▲" else "▼",
                                                    fontSize = 14.sp
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
                                                                    text = category.icon ?: "📁",
                                                                    fontSize = 18.sp,
                                                                    modifier = Modifier.padding(end = 8.dp)
                                                                )
                                                                Text(
                                                                    text = category.name,
                                                                    style = MaterialTheme.typography.bodySmall,
                                                                    fontWeight = FontWeight.Medium,
                                                                    color = MaterialTheme.colorScheme.onSurface,
                                                                    modifier = Modifier.weight(1f)
                                                                )
                                                                Text(
                                                                    text = "${fencesInCategory.size} 个",
                                                                    style = MaterialTheme.typography.labelSmall,
                                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                                )
                                                                Spacer(modifier = Modifier.width(4.dp))
                                                                Text(
                                                                    text = if (categoryExpanded) "▲" else "▼",
                                                                    fontSize = 12.sp
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
                                                                            color = MaterialTheme.colorScheme.onSurfaceVariant
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
                            mainActivity.clearDrawingMarkers() // 清除临时标记
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
                        mainActivity.clearDrawingMarkers() // 清除临时标记
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

    // 图层选择对话框
    if (showLayerDialog.value) {
        val mapType = remember { mutableStateOf(mainActivity.getSavedMapType()) }
        val showTraffic = remember { mutableStateOf(mainActivity.getSavedTrafficEnabled()) }
        
        AlertDialog(
            onDismissRequest = { showLayerDialog.value = false },
            title = { Text("选择地图图层") },
            text = {
                Column {
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
            },
            confirmButton = {
                Button(onClick = {
                    // 应用图层设置
                    val aMap = mainActivity.getCurrentMap()
                    mainActivity.setMapType(aMap, mapType.value)
                    mainActivity.setTrafficEnabled(aMap, showTraffic.value)
                    showLayerDialog.value = false
                }) {
                    Text("确定")
                }
            },
            dismissButton = {
                Button(onClick = {
                    showLayerDialog.value = false
                }) {
                    Text("取消")
                }
            }
        )
    }

    // 第一步确认对话框
    if (showClearConfirmation.value) {
        AlertDialog(
            onDismissRequest = { showClearConfirmation.value = false },
            title = { 
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("⚠️", fontSize = 24.sp)
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
                Button(
                    onClick = { 
                        showClearConfirmation.value = false 
                        showSecondClearConfirmation.value = true 
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("继续")
                }
            },
            dismissButton = {
                Button(onClick = { showClearConfirmation.value = false }) {
                    Text("取消")
                }
            }
        )
    }

    // 第二步确认对话框
    if (showSecondClearConfirmation.value) {
        AlertDialog(
            onDismissRequest = { showSecondClearConfirmation.value = false },
            title = { 
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🗑️", fontSize = 24.sp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("最终确认")
                }
            },
            text = { 
                Column {
                    Text("这是最后的确认机会！")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("点击\"确认删除\"将永久删除所有数据。", fontWeight = FontWeight.Bold)
                }
            },
            confirmButton = {
                Button(
                    onClick = { 
                        showSecondClearConfirmation.value = false 
                        mainActivity.clearAllGeoFences() 
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("确认删除")
                }
            },
            dismissButton = {
                Button(onClick = { showSecondClearConfirmation.value = false }) {
                    Text("取消")
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
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = null },
            title = { Text("删除围栏") },
            text = {
                Text("确定要删除「${fence.name}」吗？")
            },
            confirmButton = {
                Button(
                    onClick = {
                        mainActivity.deleteGeoFence(fence.id)
                        showDeleteConfirmDialog = null
                        mainActivity.refreshGeoFences()
                        refreshDataTrigger++
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("确认")
                }
            },
            dismissButton = {
                Button(onClick = { showDeleteConfirmDialog = null }) {
                    Text("取消")
                }
            }
        )
    }

    // 移除分类式围栏列表对话框（已不再需要）
}