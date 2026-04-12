package com.trackmaster.app

import com.trackmaster.app.data.GeoFence
import com.trackmaster.app.data.GeoFencePoint
import com.trackmaster.app.core.GeoFenceType
import com.trackmaster.app.data.GeoFenceAction
import com.trackmaster.app.data.GeoFenceEvent
import com.trackmaster.app.data.FenceStayRecord
import com.trackmaster.app.data.DailyFenceStats
import com.trackmaster.app.ui.components.RadioButtonWithLabel
import com.trackmaster.app.ui.components.FenceItem
import com.trackmaster.app.ui.components.FenceItemWithMove

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.*
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.graphicsLayer
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
import com.trackmaster.app.ui.navigation.TrackMasterNavHost
import java.util.*

class MainActivity : ComponentActivity() {

    private val geoFences = mutableStateListOf<GeoFence>()
    private var aMap: AMap? = null
    private lateinit var dbHelper: GeoFenceDatabaseHelper
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var appPreferences: AppPreferences

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
    private var editingCircleOverlay: Circle? = null // 编辑模式下的圆形覆盖层
    private var editingPolygonOverlay: Polygon? = null // 编辑模式下的多边形覆盖层
    private var highlightedFenceId: String? = null // 当前高亮的围栏ID

    private val currentLocation = mutableStateOf<AMapLocation?>(null)
    private val locationStatus = mutableStateOf("正在初始化...")
    private val locationError = mutableStateOf<String?>(null)

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineLocationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseLocationGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
        // 只要任一定位权限被授予即可（精确定位优先，但模糊定位也可接受）
        val granted = fineLocationGranted || coarseLocationGranted
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
        appPreferences = AppPreferences(this)

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
                // 使用新的导航结构
                // 注意：MapScreen 中的 AMap 地图将在 Week 3 从 MainActivity 提取
                TrackMasterNavHost()
            }
        }

        // 限制帧率到 90fps，降低功耗 (Android 11+)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val params = window.attributes
            try {
                // 尝试使用 setPreferredFrameRate (API 34+)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    val method = window.javaClass.getMethod(
                        "setPreferredFrameRate",
                        Float::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType
                    )
                    method.invoke(window, 90f, 1) // 1 = FRAME_RATE_PREFERENCE_FIXED_SOURCE
                } else {
                    // Android 11-12 使用 preferredDisplayModeId
                    val mode = display?.supportedModes?.find { it.refreshRate <= 90f }
                    if (mode != null) {
                        params.preferredDisplayModeId = mode.modeId
                        window.attributes = params
                    }
                }
            } catch (e: Exception) {
                Log.w("TrackMaster", "无法设置帧率限制: ${e.message}")
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

        // 保存应用关闭时的状态
        val app = application as TrackMasterApplication
        app.saveStateOnAppClose()

        // 移除定位服务监听器
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
    fun createCircleGeoFence(name: String, latitude: Double, longitude: Double, radius: Float, color: String = "#FF0000", alpha: Float = 0.3f) {
        val fence = GeoFence(
            id = UUID.randomUUID().toString(),
            name = name,
            type = GeoFenceType.CIRCLE,
            center = GeoFencePoint(latitude, longitude),
            radius = radius,
            color = color,
            alpha = alpha
        )
        geoFences.add(fence)
        dbHelper.insertFence(fence)
        // 刷新引擎围栏数据，使新围栏立即生效
        (application as TrackMasterApplication).refreshEngineFences()
        Log.d("TrackMaster", "创建圆形围栏: $name, 半径: ${radius}米, 颜色: $color")
    }

    // 创建多边形地理围栏
    fun createPolygonGeoFence(name: String, points: List<GeoFencePoint>, color: String = "#FF0000", alpha: Float = 0.3f) {
        if (points.size < 3) {
            Log.e("TrackMaster", "多边形围栏至少需要3个点")
            return
        }
        val fence = GeoFence(
            id = UUID.randomUUID().toString(),
            name = name,
            type = GeoFenceType.POLYGON,
            points = points,
            color = color,
            alpha = alpha
        )
        geoFences.add(fence)
        dbHelper.insertFence(fence)
        // 刷新引擎围栏数据，使新围栏立即生效
        (application as TrackMasterApplication).refreshEngineFences()
        Log.d("TrackMaster", "创建多边形围栏: $name, 顶点数: ${points.size}, 颜色: $color, 透明度: $alpha")
    }

    // 更新地理围栏
    fun updateGeoFence(fence: GeoFence): Boolean {
        val existingFence = geoFences.find { it.id == fence.id }
        if (existingFence == null) {
            Log.e("TrackMaster", "找不到要更新的围栏: ${fence.id}")
            return false
        }

        // 更新数据库
        val rowsUpdated = dbHelper.updateFence(fence)
        if (rowsUpdated <= 0) {
            Log.e("TrackMaster", "数据库更新失败: ${fence.id}")
            return false
        }

        // 更新内存中的列表
        val index = geoFences.indexOf(existingFence)
        if (index >= 0) {
            geoFences[index] = fence
        }

        // 更新地图上的覆盖物
        // 先移除旧的覆盖物
        circleOverlays[fence.id]?.remove()
        circleOverlays.remove(fence.id)
        polygonOverlays[fence.id]?.remove()
        polygonOverlays.remove(fence.id)

        // 添加新的覆盖物（地图控件会在下次刷新时重新绘制）
        // 刷新引擎围栏数据
        (application as TrackMasterApplication).refreshEngineFences()
        Log.d("TrackMaster", "更新围栏: ${fence.name} (${fence.id})")
        return true
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

            // 刷新引擎围栏数据
            (application as TrackMasterApplication).refreshEngineFences()
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

        // 刷新引擎围栏数据
        (application as TrackMasterApplication).refreshEngineFences()
        Log.d("TrackMaster", "清除所有围栏")
    }

    // 获取所有地理围栏
    fun getGeoFences(): List<GeoFence> {
        return geoFences.toList()
    }

    // 获取数据库帮助类（用于导入导出）
    fun getDbHelper(): GeoFenceDatabaseHelper {
        return dbHelper
    }

    // 从数据库刷新围栏数据
    fun refreshGeoFences() {
        val fencesFromDb = dbHelper.getAllFences()
        geoFences.clear()
        geoFences.addAll(fencesFromDb)
        Log.d("TrackMaster", "MainActivity刷新了 ${geoFences.size} 个围栏（从数据库读取）")
    }

    // 获取地理围栏事件
    fun getGeoFenceEvents(): List<GeoFenceEvent> {
        return dbHelper.getEventsByTimeRange(Date(0), Date())
    }

    // 获取用户当前在哪些围栏内
    fun getCurrentFences(): List<GeoFence> {
        val app = applicationContext as? TrackMasterApplication ?: return emptyList()
        return app.currentMatchedFences.toList()
    }

    // 获取所有围栏列表
    fun getAllFences(): List<GeoFence> {
        return geoFences.toList()
    }

    // 检查围栏是否被高亮
    fun isFenceHighlighted(fenceId: String): Boolean {
        return highlightedFenceId == fenceId
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
            val fenceColor = AndroidColor.parseColor(fence.color)
            val fillColorWithAlpha = AndroidColor.argb((fence.alpha * 255).toInt(), AndroidColor.red(fenceColor), AndroidColor.green(fenceColor), AndroidColor.blue(fenceColor))
            when (fence.type) {
                GeoFenceType.CIRCLE -> {
                    if (!circleOverlays.containsKey(fence.id)) {
                        fence.center?.let { center ->
                            fence.radius?.let { radius ->
                                val circleOptions = CircleOptions()
                                    .center(LatLng(center.latitude, center.longitude))
                                    .radius(radius.toDouble())
                                    .strokeColor(fenceColor)
                                    .fillColor(fillColorWithAlpha)
                                    .strokeWidth(2f)
                                val circle = aMap.addCircle(circleOptions)
                                circleOverlays[fence.id] = circle
                            }
                        }
                    } else {
                        // 更新已有圆形围栏的填充颜色
                        circleOverlays[fence.id]?.let { circle ->
                            circle.fillColor = fillColorWithAlpha
                        }
                    }
                }
                GeoFenceType.POLYGON -> {
                    if (!polygonOverlays.containsKey(fence.id)) {
                        fence.points?.let { pointsList ->
                            val points = pointsList.map { LatLng(it.latitude, it.longitude) }
                            val polygonOptions = PolygonOptions()
                                .addAll(points)
                                .strokeColor(fenceColor)
                                .fillColor(fillColorWithAlpha)
                                .strokeWidth(2f)
                            val polygon = aMap.addPolygon(polygonOptions)
                            polygonOverlays[fence.id] = polygon
                        }
                    } else {
                        // 更新已有多边形围栏的填充颜色
                        polygonOverlays[fence.id]?.let { polygon ->
                            polygon.fillColor = fillColorWithAlpha
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

    // 高亮显示指定围栏（再次点击取消高亮）
    fun highlightGeoFence(fenceId: String) {
        val app = applicationContext as? TrackMasterApplication

        // 如果点击的是同一个围栏，取消高亮
        if (highlightedFenceId == fenceId) {
            highlightedFenceId = null
            app?.setHighlightedFenceId(null)
            // 重置所有围栏的样式
            circleOverlays.forEach { (_, circle) ->
                circle.strokeColor = AndroidColor.parseColor("#FF0000")
                circle.strokeWidth = 2f
            }
            polygonOverlays.forEach { (_, polygon) ->
                polygon.strokeColor = AndroidColor.parseColor("#FF0000")
                polygon.strokeWidth = 2f
            }
            app?.notifyFenceStateChanged()
            return
        }

        // 取消之前的高亮，恢复围栏原始颜色
        val oldId = highlightedFenceId
        if (oldId != null) {
            // 查找围栏原始颜色
            val oldFence = geoFences.find { it.id == oldId }
            val originalColor = oldFence?.color ?: "#FF0000"
            val colorInt = AndroidColor.parseColor(originalColor)

            if (circleOverlays.containsKey(oldId)) {
                circleOverlays[oldId]?.apply {
                    strokeColor = colorInt
                    strokeWidth = 2f
                }
            } else if (polygonOverlays.containsKey(oldId)) {
                polygonOverlays[oldId]?.apply {
                    strokeColor = colorInt
                    strokeWidth = 2f
                }
            }
        }

        // 设置新的高亮（使用黄色高亮）
        highlightedFenceId = fenceId
        app?.setHighlightedFenceId(fenceId)
        if (circleOverlays.containsKey(fenceId)) {
            val circle = circleOverlays[fenceId]
            circle?.strokeColor = AndroidColor.parseColor("#FFFF00")
            circle?.strokeWidth = 4f
        } else if (polygonOverlays.containsKey(fenceId)) {
            val polygon = polygonOverlays[fenceId]
            polygon?.strokeColor = AndroidColor.parseColor("#FFFF00")
            polygon?.strokeWidth = 4f
        }

        app?.notifyFenceStateChanged()
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
    isEditingMode: Boolean = false,
    editingPoints: List<GeoFencePoint> = emptyList(),
    editingVersion: Int = 0,
    editingFenceType: GeoFenceType? = null,
    editingFenceRadius: Float? = null,
    onMapReady: (AMap) -> Unit = {},
    onMapClick: (LatLng) -> Unit = {},
    refreshDataTrigger: Int = 0
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
    val lastEditingPointsSize = remember { mutableStateOf(0) }
    val editingMarkers = remember { mutableListOf<Marker>() }
    var editingCircleOverlay by remember { mutableStateOf<Circle?>(null) }
    var editingPolygonOverlay by remember { mutableStateOf<Polygon?>(null) }

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
                // 地图蓝点定位间隔改为 10 秒，与 SmartScheduler 的基础间隔保持一致，省电
                myLocationStyle.interval(10000)
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

    LaunchedEffect(mainActivity.getGeoFences().size, refreshDataTrigger, mapView) {
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

    // 处理编辑模式下的顶点标记和围栏覆盖层
    LaunchedEffect(editingPoints.size, isEditingMode, mapView, editingVersion, editingFenceType, editingFenceRadius) {
        val aMap = mapView.map
        if (aMap != null) {
            if (isEditingMode) {
                // 清除之前的编辑标记
                editingMarkers.forEach { it.remove() }
                editingMarkers.clear()

                // 清除之前的编辑覆盖层
                editingCircleOverlay?.remove()
                editingCircleOverlay = null
                editingPolygonOverlay?.remove()
                editingPolygonOverlay = null

                // 根据围栏类型添加覆盖层
                when (editingFenceType) {
                    GeoFenceType.POLYGON -> {
                        // 添加多边形覆盖层
                        if (editingPoints.isNotEmpty()) {
                            val points = editingPoints.map { LatLng(it.latitude, it.longitude) }
                            val polygonOptions = PolygonOptions()
                                .addAll(points)
                                .strokeColor(AndroidColor.parseColor("#00FF00"))
                                .fillColor(AndroidColor.argb(80, 0, 255, 0))
                                .strokeWidth(3f)
                            editingPolygonOverlay = aMap.addPolygon(polygonOptions)

                            // 添加顶点标记
                            editingPoints.forEachIndexed { index, point ->
                                val markerOptions = MarkerOptions()
                                    .position(LatLng(point.latitude, point.longitude))
                                    .title("顶点 ${index + 1}")
                                    .snippet("${point.latitude}, ${point.longitude}")
                                    .draggable(true)
                                val marker = aMap.addMarker(markerOptions)
                                if (marker != null) {
                                    editingMarkers.add(marker)
                                }
                            }
                        }
                    }
                    GeoFenceType.CIRCLE -> {
                        // 添加圆形覆盖层
                        if (editingPoints.isNotEmpty() && editingFenceRadius != null) {
                            val center = editingPoints.first()
                            val circleOptions = CircleOptions()
                                .center(LatLng(center.latitude, center.longitude))
                                .radius(editingFenceRadius.toDouble())
                                .strokeColor(AndroidColor.parseColor("#00FF00"))
                                .fillColor(AndroidColor.argb(80, 0, 255, 0))
                                .strokeWidth(3f)
                            editingCircleOverlay = aMap.addCircle(circleOptions)

                            // 添加圆心标记
                            val markerOptions = MarkerOptions()
                                .position(LatLng(center.latitude, center.longitude))
                                .title("圆心")
                                .snippet("${center.latitude}, ${center.longitude}")
                                .draggable(true)
                            val marker = aMap.addMarker(markerOptions)
                            if (marker != null) {
                                editingMarkers.add(marker)
                            }
                        }
                    }
                    else -> {}
                }
                lastEditingPointsSize.value = editingPoints.size
            } else {
                // 退出编辑模式，清除所有编辑标记和覆盖层
                if (lastEditingPointsSize.value > 0) {
                    editingMarkers.forEach { it.remove() }
                    editingMarkers.clear()
                    editingCircleOverlay?.remove()
                    editingCircleOverlay = null
                    editingPolygonOverlay?.remove()
                    editingPolygonOverlay = null
                    lastEditingPointsSize.value = 0
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
fun MainScreen(
    currentLocation: AMapLocation?,
    locationStatus: String,
    locationError: String?
) {
    val context = LocalContext.current
    val appPreferences = remember { AppPreferences(context) }
    val mainActivity = context as MainActivity
    val app = context.applicationContext as TrackMasterApplication
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val showGeoFenceDialog = remember { mutableStateOf(false) }
    val fenceName = remember { mutableStateOf("") }
    val fenceRadius = remember { mutableStateOf(100f) }
    val selectedFenceType = remember { mutableStateOf(GeoFenceType.CIRCLE) }
    val selectedFenceColor = remember { mutableStateOf("#FF0000") }
    val fillAlpha = remember { mutableFloatStateOf(appPreferences.fillAlpha) }
    val isDrawingFence = remember { mutableStateOf(false) }
    val drawingPoints = remember { mutableStateListOf<GeoFencePoint>() }

    // 围栏颜色选项
    val fenceColorOptions = listOf(
        "#FF0000", // 红色
        "#E91E63", // 粉色
        "#9C27B0", // 紫色
        "#673AB7", // 深紫
        "#3F51B5", // 靛蓝
        "#2196F3", // 蓝色
        "#00BCD4", // 青色
        "#009688", // 蓝绿色
        "#4CAF50", // 绿色
        "#FF9800", // 橙色
        "#FF5722", // 深橙
        "#795548"  // 棕色
    )

    // 控制菜单展开状态
    val isMenuExpanded = remember { mutableStateOf(false) }
    // 控制灵动岛展开状态
    val isDynamicIslandExpanded = remember { mutableStateOf(false) }
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
    // 控制数据导入导出对话框显示
    val showDataImportExportDialog = remember { mutableStateOf(false) }
    // 热力图开关
    val heatmapEnabled = remember { mutableStateOf(false) }
    // 保存热力图覆盖层引用（避免每次创建新实例）
    var heatmapOverlay by remember { mutableStateOf<com.trackmaster.app.ui.HeatmapOverlay?>(null) }
    // 围栏日志状态
    val geoFenceEvents = remember { mutableStateOf<List<GeoFenceEvent>>(emptyList()) }
    val refreshEventsTrigger = remember { mutableStateOf(0) }

    // 分类相关状态
    var categories by remember { mutableStateOf<List<Category>>(emptyList()) }
    var expandedCategories by remember { mutableStateOf<Set<String>>(emptySet()) }
    var categoryDialogClosed by remember { mutableStateOf(false) }
    var showMoveFenceDialog by remember { mutableStateOf<GeoFence?>(null) }
    var refreshDataTrigger by remember { mutableStateOf(0) }
    var showDeleteConfirmDialog by remember { mutableStateOf<GeoFence?>(null) }
    var showEditFenceDialog by remember { mutableStateOf<GeoFence?>(null) }

    // 围栏编辑模式状态
    var isEditingFence by remember { mutableStateOf(false) }
    var editingFence by remember { mutableStateOf<GeoFence?>(null) }
    val editingPoints = remember { mutableStateListOf<GeoFencePoint>() }
    var editingVersion by remember { mutableStateOf(0) } // 用于触发编辑模式重新渲染

    suspend fun loadCategories() {
        val userCategories = app.categoryManager.getAllCategories()
        categories = listOf(Category.UNASSIGNED) + userCategories
    }

    suspend fun loadEvents() {
        geoFenceEvents.value = mainActivity.getGeoFenceEvents()
    }

    fun calculateFenceArea(fence: GeoFence): Double {
        return when (fence.type) {
            GeoFenceType.CIRCLE -> {
                val radius = fence.radius ?: 0f
                Math.PI * radius * radius
            }
            GeoFenceType.POLYGON -> {
                val points = fence.points ?: emptyList()
                if (points.size < 3) return 0.0
                var area = 0.0
                for (i in points.indices) {
                    val j = (i + 1) % points.size
                    area += points[i].latitude * points[j].longitude
                    area -= points[j].latitude * points[i].longitude
                }
                Math.abs(area) / 2.0
            }
        }
    }

    fun getSmallestFence(fences: List<GeoFence>): GeoFence? {
        if (fences.isEmpty()) return null
        return fences.minByOrNull { calculateFenceArea(it) }
    }

    LaunchedEffect(Unit) {
        loadCategories()
        loadEvents()
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

    LaunchedEffect(refreshEventsTrigger) {
        if (refreshEventsTrigger.value > 0) {
            loadEvents()
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
                // .then(
                //     if (isBottomSheetExpanded.value) {
                //         Modifier.blur(radius = 8.dp)
                //     } else {
                //         Modifier
                //     }
                // )
        ) {
            AMapView(
                currentLocation = currentLocation,
                isDrawingMode = isDrawingFence.value,
                drawingPoints = drawingPoints.toList(),
                isEditingMode = isEditingFence,
                editingPoints = editingPoints.toList(),
                editingVersion = editingVersion,
                editingFenceType = editingFence?.type,
                editingFenceRadius = editingFence?.radius,
                onMapClick = { latLng ->
                    if (isDrawingFence.value) {
                        drawingPoints.add(GeoFencePoint(latLng.latitude, latLng.longitude))
                    } else if (isEditingFence && editingFence != null) {
                        when (editingFence!!.type) {
                            GeoFenceType.POLYGON -> {
                                editingPoints.add(GeoFencePoint(latLng.latitude, latLng.longitude))
                            }
                            GeoFenceType.CIRCLE -> {
                                val updatedFence = editingFence!!.copy(
                                    center = GeoFencePoint(latLng.latitude, latLng.longitude)
                                )
                                editingFence = updatedFence
                            }
                        }
                    }
                },
                refreshDataTrigger = refreshDataTrigger
            )

            // 深色叠加层 - 仅在非编辑模式下显示，让地图更安静
            // if (!isEditingFence) {
            //     Box(
            //         modifier = Modifier
            //             .fillMaxSize()
            //             .background(color = Color(0x1A000000))
            //     )
            // }

            // 编辑模式工具栏
            AnimatedVisibility(
                visible = isEditingFence && editingFence != null,
                enter = fadeIn() + slideInVertically { -40 },
                exit = fadeOut() + slideOutVertically { -40 }
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 80.dp)
                ) {
                    GlassCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "编辑模式: ${editingFence?.name}",
                                style = MaterialTheme.typography.labelMedium,
                                color = NeonBlue,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = when (editingFence?.type) {
                                    GeoFenceType.POLYGON -> "点击地图添加顶点。当前: ${editingPoints.size}"
                                    GeoFenceType.CIRCLE -> "点击地图设置新的圆心位置"
                                    else -> ""
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White,
                                fontSize = 13.sp
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                // 完成按钮
                                GlassButton(
                                    onClick = {
                                        // 保存编辑结果
                                        val fence = editingFence ?: return@GlassButton
                                        val updatedFence = when (fence.type) {
                                            GeoFenceType.POLYGON -> {
                                                if (editingPoints.size < 3) {
                                                    scope.launch {
                                                        snackbarHostState.showSnackbar(
                                                            message = "多边形需要至少3个顶点",
                                                            duration = SnackbarDuration.Long
                                                        )
                                                    }
                                                    return@GlassButton
                                                }
                                                fence.copy(points = editingPoints.toList())
                                            }
                                            GeoFenceType.CIRCLE -> fence // 圆心已实时更新
                                        }
                                        val success = mainActivity.updateGeoFence(updatedFence)
                                        if (success) {
                                            isEditingFence = false
                                            editingFence = null
                                            editingPoints.clear()
                                            mainActivity.refreshGeoFences()
                                            refreshDataTrigger++
                                            scope.launch {
                                                snackbarHostState.showSnackbar(
                                                    message = "围栏「${fence.name}」已更新",
                                                    duration = SnackbarDuration.Short
                                                )
                                            }
                                        } else {
                                            scope.launch {
                                                snackbarHostState.showSnackbar(
                                                    message = "围栏更新失败",
                                                    duration = SnackbarDuration.Long
                                                )
                                            }
                                        }
                                    },
                                    modifier = Modifier.weight(1f).height(40.dp)
                                ) {
                                    Icon(Icons.Default.Check, contentDescription = "完成", tint = Color.White, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("完成", color = Color.White, fontSize = 13.sp)
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                // 取消按钮
                                GlassButton(
                                    onClick = {
                                        isEditingFence = false
                                        editingFence = null
                                        editingPoints.clear()
                                    },
                                    modifier = Modifier.weight(1f).height(40.dp)
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = "取消", tint = Color.White, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("取消", color = Color.White, fontSize = 13.sp)
                                }
                            }
                            // 多边形编辑：删除最后一个顶点按钮
                            if (editingFence?.type == GeoFenceType.POLYGON && editingPoints.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(6.dp))
                                GlassButton(
                                    onClick = {
                                        if (editingPoints.isNotEmpty()) {
                                            editingPoints.removeAt(editingPoints.size - 1)
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth().height(40.dp)
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "删除最后一个顶点", tint = Danger, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("删除最后一个顶点", color = Danger, fontSize = 13.sp)
                                }
                            }
                        }
                    }
                }
            }
        }

        // SnackbarHost
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = if (isBottomSheetExpanded.value) 400.dp else 200.dp)
        )

        // 灵动岛 (z-index: 10) - 编辑模式下隐藏
        AnimatedVisibility(
            visible = !isEditingFence,
            enter = fadeIn() + slideInVertically { -100 },
            exit = fadeOut() + slideOutVertically { -100 },
            modifier = Modifier
                .padding(top = 8.dp)
                .align(Alignment.TopCenter)
        ) {
            val config = androidx.compose.ui.platform.LocalConfiguration.current
            val screenWidth = config.screenWidthDp.dp
            val islandWidth by animateDpAsState(
                targetValue = if (isDynamicIslandExpanded.value) screenWidth * 0.95f else 160.dp,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessLow
                ),
                label = "islandWidth"
            )
            val islandHeight by animateDpAsState(
                targetValue = if (isDynamicIslandExpanded.value) 320.dp else 44.dp,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessLow
                ),
                label = "islandHeight"
            )

            Box(
                modifier = Modifier
                    .width(islandWidth)
                    .height(islandHeight)
                    .clip(RoundedCornerShape(24.dp))
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color(0xFF1A1A2E),
                                Color(0xFF0F0F1A)
                            )
                        )
                    )
                    .border(
                        width = 1.dp,
                        color = NeonCoral.copy(alpha = 0.3f),
                        shape = RoundedCornerShape(24.dp)
                    )
                    .clickable(
                        onClick = {
                            isDynamicIslandExpanded.value = !isDynamicIslandExpanded.value
                        },
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    )
            ) {
                // 观察围栏状态版本，触发 UI 刷新
                @Suppress("UNUSED_VARIABLE")
                val fenceVersion by app.fenceStateVersion.collectAsState()
                val currentFences = mainActivity.getCurrentFences()
                val smallestFence = getSmallestFence(currentFences)
                if (isDynamicIslandExpanded.value) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "日志",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = NeonCoral
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "${geoFenceEvents.value.size}条",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White.copy(alpha = 0.7f)
                                )
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
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        if (currentLocation != null && currentLocation.errorCode == 0) {
                            Text(
                                text = "经纬度: ${String.format("%.6f", currentLocation.longitude)}, ${String.format("%.6f", currentLocation.latitude)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = NeonCoral.copy(alpha = 0.9f)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "精度: ${currentLocation.accuracy.toInt()}m",
                                style = MaterialTheme.typography.bodySmall,
                                color = NeonCoral.copy(alpha = 0.7f)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }

                        if (smallestFence != null) {
                            Text(
                                text = "当前在: ${smallestFence.name}",
                                style = MaterialTheme.typography.bodySmall,
                                color = NeonAmber.copy(alpha = 0.9f),
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }

                        // 围栏选择区域
                        val allFences = mainActivity.getAllFences()
                        if (allFences.isNotEmpty()) {
                            Text(
                                text = "选择围栏高亮",
                                style = MaterialTheme.typography.labelMedium,
                                color = NeonCoral.copy(alpha = 0.8f),
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 150.dp)
                            ) {
                                allFences.take(10).forEach { fence ->
                                    val isHighlighted = mainActivity.isFenceHighlighted(fence.id)
                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 2.dp)
                                            .clickable {
                                                mainActivity.highlightGeoFence(fence.id)
                                            },
                                        colors = CardDefaults.cardColors(
                                            containerColor = if (isHighlighted)
                                                NeonCoral.copy(alpha = 0.3f)
                                            else
                                                Color.White.copy(alpha = 0.1f)
                                        ),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 10.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                text = fence.name,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = if (isHighlighted) NeonCoral else Color.White,
                                                fontWeight = if (isHighlighted) FontWeight.Bold else FontWeight.Normal,
                                                modifier = Modifier.weight(1f)
                                            )
                                            if (isHighlighted) {
                                                Icon(
                                                    Icons.Default.LocationOn,
                                                    contentDescription = "已高亮",
                                                    tint = NeonCoral,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                        }

                        if (isDrawingFence.value) {
                            Text(
                                text = "绘制模式: 已选 ${drawingPoints.size} 个点",
                                style = MaterialTheme.typography.bodySmall,
                                color = Info,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }

                        if (locationError != null) {
                            Text(
                                text = locationError,
                                style = MaterialTheme.typography.bodySmall,
                                color = Danger
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }

                        if (geoFenceEvents.value.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "暂无日志",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.White.copy(alpha = 0.5f)
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(geoFenceEvents.value) { event ->
                                    val sdf = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                                    val timeStr = sdf.format(event.time)
                                    val dateSdf = java.text.SimpleDateFormat("MM-dd", java.util.Locale.getDefault())
                                    val dateStr = dateSdf.format(event.time)
                                    
                                    val eventColor = when (event.action) {
                                        GeoFenceAction.ENTER -> Success
                                        GeoFenceAction.EXIT -> Danger
                                        GeoFenceAction.STAY -> Info
                                    }
                                    
                                    val eventIcon = when (event.action) {
                                        GeoFenceAction.ENTER -> Icons.AutoMirrored.Filled.ArrowForward
                                        GeoFenceAction.EXIT -> Icons.AutoMirrored.Filled.ArrowBack
                                        GeoFenceAction.STAY -> Icons.Default.AccessTime
                                    }
                                    
                                    val eventText = when (event.action) {
                                        GeoFenceAction.ENTER -> "进入"
                                        GeoFenceAction.EXIT -> "离开"
                                        GeoFenceAction.STAY -> "停留"
                                    }

                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = CardDefaults.cardColors(
                                            containerColor = eventColor.copy(alpha = 0.15f)
                                        ),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(32.dp)
                                                    .clip(CircleShape)
                                                    .background(eventColor),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    imageVector = eventIcon,
                                                    contentDescription = null,
                                                    tint = Color.White,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                            
                                            Column(
                                                modifier = Modifier.weight(1f),
                                                verticalArrangement = Arrangement.spacedBy(2.dp)
                                            ) {
                                                Text(
                                                    text = "$eventText ${event.fenceName}",
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color.White
                                                )
                                                Text(
                                                    text = "$dateStr $timeStr",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = Color.White.copy(alpha = 0.6f)
                                                )
                                            }

                                            if (event.durationSeconds != null && event.action == GeoFenceAction.EXIT) {
                                                val minutes = event.durationSeconds / 60
                                                val seconds = event.durationSeconds % 60
                                                GlassBadge(
                                                    containerColor = NeonAmber
                                                ) {
                                                    Text(
                                                        text = if (minutes > 0) "${minutes}分${seconds}秒" else "${seconds}秒",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = Color.White,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        GlassButton(
                            onClick = { 
                                refreshEventsTrigger.value = refreshEventsTrigger.value + 1
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "刷新日志",
                                fontSize = 13.sp,
                                color = Color.White,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(
                                    brush = Brush.radialGradient(
                                        colors = listOf(
                                            NeonCoral,
                                            NeonCoral.copy(alpha = 0f)
                                        )
                                    )
                                )
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = if (smallestFence != null) smallestFence.name else "无围栏",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(
                                    brush = Brush.radialGradient(
                                        colors = listOf(
                                            NeonCoral,
                                            NeonCoral.copy(alpha = 0f)
                                        )
                                    )
                                )
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
                    // 撤销按钮 - 删除最后一个点
                    if (drawingPoints.isNotEmpty()) {
                        GlassFloatingActionButton(
                            onClick = {
                                drawingPoints.removeLast()
                                mainActivity.clearDrawingMarkers()
                                // 重新添加剩余的标记
                                mainActivity.getCurrentMap()?.let { map ->
                                    drawingPoints.forEachIndexed { index, point ->
                                        mainActivity.addDrawingMarker(map, point, index)
                                    }
                                }
                            },
                            modifier = Modifier.size(44.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "撤销", tint = Color.Black, modifier = Modifier.size(20.dp))
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    // 完成按钮
                    GlassFloatingActionButton(
                        onClick = {
                            if (drawingPoints.size >= 3) {
                                if (fenceName.value.isNotEmpty()) {
                                    mainActivity.createPolygonGeoFence(
                                        name = fenceName.value,
                                        points = drawingPoints.toList(),
                                        color = selectedFenceColor.value,
                                        alpha = fillAlpha.value
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
                    // 取消按钮
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
                        enter = fadeIn(animationSpec = tween(350, easing = FastOutSlowInEasing)) + 
                                scaleIn(
                                    initialScale = 0.6f,
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioMediumBouncy,
                                        stiffness = Spring.StiffnessLow
                                    )
                                ) +
                                slideInVertically(
                                    initialOffsetY = { it / 2 },
                                    animationSpec = tween(350, easing = FastOutSlowInEasing)
                                ) +
                                expandVertically(
                                    expandFrom = Alignment.Bottom,
                                    animationSpec = tween(350, easing = FastOutSlowInEasing)
                                ),
                        exit = fadeOut(animationSpec = tween(250, easing = FastOutSlowInEasing)) + 
                                scaleOut(
                                    targetScale = 0.6f,
                                    animationSpec = tween(250, easing = FastOutSlowInEasing)
                                ) +
                                slideOutVertically(
                                    targetOffsetY = { it / 2 },
                                    animationSpec = tween(250, easing = FastOutSlowInEasing)
                                ) +
                                shrinkVertically(
                                    shrinkTowards = Alignment.Bottom,
                                    animationSpec = tween(250, easing = FastOutSlowInEasing)
                                )
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

        // 底部一体化悬浮卡片 (z-index: 10) - 编辑模式下隐藏
        AnimatedVisibility(
            visible = !isEditingFence,
            enter = fadeIn() + slideInVertically { 100 },
            exit = fadeOut() + slideOutVertically { 100 },
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
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
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
                            // 分类管理按钮
                            IconButton(
                                onClick = { showCategoryManagerDialog.value = true },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.FolderOpen,
                                    contentDescription = "分类管理",
                                    tint = NeonCoral,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
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
                                                    // 合上"全部分类"时，也要收起所有分类的折叠状态，避免下次打开卡顿
                                                    emptySet()
                                                } else {
                                                    // 展开"全部分类"时，保持其他分类的展开状态
                                                    expandedCategories + "all"
                                                }
                                            },
                                        colors = CardDefaults.cardColors(
                                            containerColor = GlassPanelRich
                                        ),
                                        shape = RoundedCornerShape(12.dp),
                                        elevation = CardDefaults.cardElevation(0.dp)
                                    ) {
                                        Column(modifier = Modifier.padding(12.dp)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(32.dp)
                                                        .clip(RoundedCornerShape(8.dp))
                                                        .background(
                                                            brush = Brush.linearGradient(
                                                                colors = listOf(NeonCoral, NeonAmber),
                                                                start = androidx.compose.ui.geometry.Offset(0f, 0f),
                                                                end = androidx.compose.ui.geometry.Offset(100f, 100f)
                                                            )
                                                        ),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Text(
                                                        text = "📋",
                                                        fontSize = 16.sp
                                                    )
                                                }
                                                Spacer(modifier = Modifier.width(12.dp))
                                                Text(
                                                    text = "全部分类",
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color(0xFFE0E0E0),
                                                    modifier = Modifier.weight(1f)
                                                )
                                                Box(
                                                    modifier = Modifier
                                                        .size(28.dp)
                                                        .clip(RoundedCornerShape(6.dp))
                                                        .background(Color(0x332A221C)),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    val arrowRotation by animateFloatAsState(
                                                        targetValue = if (isExpanded) 180f else 0f,
                                                        animationSpec = tween(
                                                            durationMillis = 300,
                                                            easing = FastOutSlowInEasing
                                                        ),
                                                        label = "arrowRotation"
                                                    )
                                                    Icon(
                                                        imageVector = Icons.Default.ExpandMore,
                                                        contentDescription = if (isExpanded) "收起" else "展开",
                                                        modifier = Modifier
                                                            .size(20.dp)
                                                            .graphicsLayer { rotationZ = arrowRotation },
                                                        tint = NeonCoral
                                                    )
                                                }
                                            }
                                            AnimatedVisibility(
                                                visible = isExpanded,
                                                enter = fadeIn(animationSpec = tween(200)) + expandVertically(
                                                    animationSpec = tween(300, easing = FastOutSlowInEasing)
                                                ),
                                                exit = fadeOut(animationSpec = tween(150)) + shrinkVertically(
                                                    animationSpec = tween(200, easing = FastOutSlowInEasing)
                                                )
                                            ) {
                                                Column {
                                                    Spacer(modifier = Modifier.height(12.dp))
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
                                                            shape = RoundedCornerShape(10.dp),
                                                            elevation = CardDefaults.cardElevation(0.dp)
                                                        ) {
                                                            Column(modifier = Modifier.padding(10.dp)) {
                                                                Row(
                                                                    modifier = Modifier.fillMaxWidth(),
                                                                    verticalAlignment = Alignment.CenterVertically
                                                                ) {
                                                                    Box(
                                                                        modifier = Modifier
                                                                            .size(28.dp)
                                                                            .clip(RoundedCornerShape(6.dp))
                                                                            .background(Color(0x332A221C)),
                                                                        contentAlignment = Alignment.Center
                                                                    ) {
                                                                        Text(
                                                                            text = category.icon ?: "📁",
                                                                            fontSize = 16.sp
                                                                        )
                                                                    }
                                                                    Spacer(modifier = Modifier.width(10.dp))
                                                                    Text(
                                                                        text = category.name,
                                                                        style = MaterialTheme.typography.bodySmall,
                                                                        fontWeight = FontWeight.Medium,
                                                                        color = Color(0xFFE0E0E0),
                                                                        modifier = Modifier.weight(1f)
                                                                    )
                                                                    Box(
                                                                        modifier = Modifier
                                                                            .size(24.dp)
                                                                            .clip(RoundedCornerShape(4.dp))
                                                                            .background(Color(0x332A221C)),
                                                                        contentAlignment = Alignment.Center
                                                                    ) {
                                                                        val arrowRotation by animateFloatAsState(
                                                                            targetValue = if (categoryExpanded) 180f else 0f,
                                                                            animationSpec = tween(
                                                                                durationMillis = 300,
                                                                                easing = FastOutSlowInEasing
                                                                            ),
                                                                            label = "arrowRotation"
                                                                        )
                                                                        Icon(
                                                                            imageVector = Icons.Default.ExpandMore,
                                                                            contentDescription = if (categoryExpanded) "收起" else "展开",
                                                                            modifier = Modifier
                                                                                .size(16.dp)
                                                                                .graphicsLayer { rotationZ = arrowRotation },
                                                                            tint = NeonAmber
                                                                        )
                                                                    }
                                                                }
                                                                AnimatedVisibility(
                                                                    visible = categoryExpanded,
                                                                    enter = fadeIn(animationSpec = tween(200)) + expandVertically(
                                                                        animationSpec = tween(300, easing = FastOutSlowInEasing)
                                                                    ),
                                                                    exit = fadeOut(animationSpec = tween(150)) + shrinkVertically(
                                                                        animationSpec = tween(200, easing = FastOutSlowInEasing)
                                                                    )
                                                                ) {
                                                                    Column {
                                                                        Spacer(modifier = Modifier.height(8.dp))
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
                                                                            LazyColumn(
                                                                                modifier = Modifier.heightIn(max = 300.dp)
                                                                            ) {
                                                                                items(
                                                                                    items = fencesInCategory,
                                                                                    key = { it.id }
                                                                                ) { fence ->
                                                                                    FenceItemWithMove(
                                                                                        fence = fence,
                                                                                        mainActivity = mainActivity,
                                                                                        onEditClick = { showEditFenceDialog = fence },
                                                                                        onCategoryClick = { showMoveFenceDialog = fence },
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
    }

    // 创建地理围栏对话框
    if (showGeoFenceDialog.value) {
        GlassDialog(
            onDismissRequest = {
                // 关闭对话框时重置所有状态
                showGeoFenceDialog.value = false
                if (isDrawingFence.value) {
                    // 如果在绘制模式下关闭，也需要取消绘制状态
                    isDrawingFence.value = false
                    drawingPoints.clear()
                }
                // 重置围栏名称
                fenceName.value = ""
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

                        // 颜色选择器（可横向滚动）
                        Text(
                            text = "选择颜色",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(fenceColorOptions) { color ->
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(Color(android.graphics.Color.parseColor(color)))
                                        .border(
                                            width = if (selectedFenceColor.value == color) 3.dp else 0.dp,
                                            color = if (selectedFenceColor.value == color) NeonCoral else Color.Transparent,
                                            shape = CircleShape
                                        )
                                        .clickable { selectedFenceColor.value = color }
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))

                        // 透明度选择器
                        Text(
                            text = "填充透明度: ${(fillAlpha.value * 100).toInt()}%",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Slider(
                            value = fillAlpha.value,
                            onValueChange = {
                                fillAlpha.value = it
                                appPreferences.fillAlpha = it
                                refreshDataTrigger++
                            },
                            valueRange = 0.1f..0.8f,
                            steps = 6,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        // 滑动标签切换器 - 优化状态管理
                        val isCircleSelected = selectedFenceType.value == GeoFenceType.CIRCLE
                        val isPolygonSelected = selectedFenceType.value == GeoFenceType.POLYGON
                        
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp)
                                .background(
                                    color = Color(0x4D2A221C),
                                    shape = RoundedCornerShape(22.dp)
                                )
                        ) {
                            // 滑动背景指示器 - 直接根据状态定位，不使用动画
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth(0.5f)
                                    .align(
                                        if (isCircleSelected) 
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
                                        color = if (isCircleSelected) 
                                            Color.White 
                                            else 
                                            Color(0xFFBDBDBD),
                                        fontWeight = if (isCircleSelected) 
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
                                        color = if (isPolygonSelected) 
                                            Color.White 
                                            else 
                                            Color(0xFFBDBDBD),
                                        fontWeight = if (isPolygonSelected) 
                                            FontWeight.Bold 
                                            else 
                                            FontWeight.Normal
                                    )
                                }
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        // 半径输入框 - 始终存在，使用 alpha 控制可见性
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .alpha(if (selectedFenceType.value == GeoFenceType.CIRCLE) 1f else 0f)
                        ) {
                            GlassTextField(
                                value = fenceRadius.value.toString(),
                                onValueChange = { 
                                    fenceRadius.value = it.toFloatOrNull() ?: 100f 
                                },
                                label = { Text("半径 (米)") },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        
                        // 绘制按钮 - 始终存在，使用 alpha 控制可见性
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .alpha(if (selectedFenceType.value == GeoFenceType.POLYGON) 1f else 0f)
                        ) {
                            GlassButton(
                                onClick = {
                                    isDrawingFence.value = true
                                    drawingPoints.clear()
                                    showGeoFenceDialog.value = false
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("开始绘制多边形围栏", color = Color.White)
                            }
                        }
                        // 绘制提示 - 始终存在，使用 alpha 控制可见性
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .alpha(if (selectedFenceType.value == GeoFenceType.POLYGON) 1f else 0f)
                        ) {
                            Text(
                                text = "提示：点击地图选点，点会按顺序连接成围栏",
                                style = MaterialTheme.typography.bodySmall,
                                color = NeonCoral.copy(alpha = 0.8f)
                            )
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
                                points = drawingPoints.toList(),
                                color = selectedFenceColor.value
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
                                radius = fenceRadius.value,
                                color = selectedFenceColor.value,
                                alpha = fillAlpha.value
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
                                        text = "紫色越深表示访问越频繁",
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
                        // 确保有热力图覆盖层引用
                        if (heatmapOverlay == null) {
                            heatmapOverlay = com.trackmaster.app.ui.HeatmapOverlay(aMap)
                        }
                        
                        if (heatmapEnabled.value) {
                            scope.launch {
                                val summaries = try {
                                    val today = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                                    app.aggregator.aggregate(today)
                                } catch (_: Exception) { emptyList() }
                                heatmapOverlay?.update(app.geoFences.mapNotNull { f ->
                                    try {
                                        com.trackmaster.app.core.Geofence(
                                            id = f.id, name = f.name,
                                            type = com.trackmaster.app.core.GeoFenceType.valueOf(f.type.name),
                                            polygon = if (f.type == com.trackmaster.app.core.GeoFenceType.CIRCLE) {
                                                val center = f.center ?: return@mapNotNull null
                                                com.trackmaster.app.core.GeoMath.circleToPolygon(
                                                    com.trackmaster.app.core.GeoPoint(center.latitude, center.longitude),
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
                            // 关闭热力图时清除
                            heatmapOverlay?.clear()
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
            onOpenDataImportExport = {
                showSettingsDialog.value = false
                showDataImportExportDialog.value = true
            }
        )
    }

    // 数据导入导出对话框（统一入口）
    if (showDataImportExportDialog.value) {
        DataManagementScreen(
            onDismiss = {
                showDataImportExportDialog.value = false
                mainActivity.refreshGeoFences()
                refreshDataTrigger++
            },
            dataManager = app.dataManager,
            app = app
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
        var selectedCategoryId by remember {
            mutableStateOf<String?>(if (fence.categoryId == Category.UNASSIGNED_ID) null else fence.categoryId)
        }

        AlertDialog(
            onDismissRequest = { showMoveFenceDialog = null },
            title = { Text("移动围栏到分类") },
            text = {
                Column {
                    Text("将围栏「${fence.name}」移动到：", modifier = Modifier.padding(bottom = 8.dp))

                    // 无分类选项
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = selectedCategoryId == null,
                            onClick = { selectedCategoryId = null }
                        )
                        Text("无分类", modifier = Modifier.padding(start = 8.dp))
                    }

                    // 分类选项
                    categories.forEach { category ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = selectedCategoryId == category.id,
                                onClick = { selectedCategoryId = category.id }
                            )
                            Text(category.name, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            },
            confirmButton = {
                GlassButton(
                    onClick = {
                        val normalizedSelected = selectedCategoryId ?: Category.UNASSIGNED_ID
                        scope.launch {
                            val result = app.categoryManager.moveFenceToCategory(fence.id, normalizedSelected)
                            if (result.isSuccess) {
                                showMoveFenceDialog = null
                                mainActivity.refreshGeoFences()
                                refreshDataTrigger++
                            }
                        }
                    }
                ) {
                    Text("移动", color = Color.White)
                }
            },
            dismissButton = {
                GlassButton(onClick = { showMoveFenceDialog = null }) {
                    Text("取消", color = Color.White)
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

    // 编辑围栏对话框
    showEditFenceDialog?.let { fence ->
        var editedFenceName by remember { mutableStateOf(fence.name) }
        var editedFenceRadius by remember { mutableStateOf(fence.radius ?: 100f) }
        var fenceColor by remember { mutableStateOf(fence.color) }
        var fenceAlpha by remember { mutableStateOf(fence.alpha) }
        // 对于多边形围栏，使用可变的点列表
        val fencePoints = remember { mutableStateListOf<GeoFencePoint>() }

        // 初始化点列表
        LaunchedEffect(fence) {
            fencePoints.clear()
            fenceColor = fence.color
            fenceAlpha = fence.alpha
            if (fence.type == GeoFenceType.POLYGON && fence.points != null) {
                fencePoints.addAll(fence.points)
            }
        }

        AlertDialog(
            onDismissRequest = { showEditFenceDialog = null },
            title = { Text("编辑围栏") },
            text = {
                Column {
                    // 围栏名称
                    OutlinedTextField(
                        value = editedFenceName,
                        onValueChange = { editedFenceName = it },
                        label = { Text("围栏名称") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = NeonCoral,
                            unfocusedBorderColor = Color.Gray.copy(alpha = 0.5f)
                        )
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // 颜色选择
                    Text("围栏颜色", style = MaterialTheme.typography.labelMedium)
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        items(fenceColorOptions) { color ->
                            val isSelected = fenceColor == color
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .border(
                                        width = if (isSelected) 3.dp else 1.dp,
                                        color = if (isSelected) Color.White else Color.Gray,
                                        shape = CircleShape
                                    )
                                    .background(Color(android.graphics.Color.parseColor(color)))
                                    .clickable { fenceColor = color }
                            )
                        }
                    }

                    // 透明度选择
                    Text("填充透明度: ${(fenceAlpha * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = fenceAlpha,
                        onValueChange = {
                            fenceAlpha = it
                            refreshDataTrigger++
                        },
                        valueRange = 0.1f..0.8f,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // 类型特定编辑
                    when (fence.type) {
                        GeoFenceType.CIRCLE -> {
                            Text("圆形围栏半径", style = MaterialTheme.typography.labelMedium)
                            Slider(
                                value = editedFenceRadius,
                                onValueChange = { editedFenceRadius = it },
                                valueRange = 50f..500f,
                                steps = 9,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text("${editedFenceRadius.toInt()} 米", style = MaterialTheme.typography.bodySmall)
                            
                            Spacer(modifier = Modifier.height(16.dp))
                            
                            GlassButton(
                                onClick = {
                                    val updatedFence = fence.copy(
                                        name = editedFenceName,
                                        radius = editedFenceRadius,
                                        color = fenceColor,
                                        alpha = fenceAlpha
                                    )
                                    editingFence = updatedFence
                                    editingVersion++
                                    isEditingFence = true
                                    showEditFenceDialog = null
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    Icons.Default.EditLocation,
                                    contentDescription = "在地图上调整位置",
                                    tint = NeonBlue,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("在地图上调整位置", color = NeonBlue)
                            }
                        }
                        GeoFenceType.POLYGON -> {
                            Text("多边形围栏顶点编辑", style = MaterialTheme.typography.labelMedium)
                            Text("当前顶点数: ${fencePoints.size}", style = MaterialTheme.typography.bodySmall)

                            Spacer(modifier = Modifier.height(16.dp))

                            GlassButton(
                                onClick = {
                                    val updatedFence = fence.copy(
                                        name = editedFenceName,
                                        points = fencePoints.toList(),
                                        color = fenceColor,
                                        alpha = fenceAlpha
                                    )
                                    editingFence = updatedFence
                                    editingPoints.clear()
                                    editingPoints.addAll(fencePoints)
                                    editingVersion++
                                    isEditingFence = true
                                    showEditFenceDialog = null
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    Icons.Default.EditLocation,
                                    contentDescription = "在地图上编辑顶点",
                                    tint = NeonBlue,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("在地图上编辑顶点", color = NeonBlue)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                GlassButton(
                    onClick = {
                        val updatedFence = when (fence.type) {
                            GeoFenceType.CIRCLE -> fence.copy(
                                name = editedFenceName,
                                radius = editedFenceRadius,
                                color = fenceColor,
                                alpha = fenceAlpha
                            )
                            GeoFenceType.POLYGON -> {
                                // 检查多边形是否至少有3个顶点
                                if (fencePoints.size < 3) {
                                    scope.launch {
                                        snackbarHostState.showSnackbar(
                                            message = "多边形需要至少3个顶点",
                                            duration = SnackbarDuration.Long
                                        )
                                    }
                                    return@GlassButton
                                }
                                fence.copy(
                                    name = editedFenceName,
                                    points = fencePoints.toList(),
                                    color = fenceColor,
                                    alpha = fenceAlpha
                                )
                            }
                        }

                        val success = mainActivity.updateGeoFence(updatedFence)
                        if (success) {
                            showEditFenceDialog = null
                            mainActivity.refreshGeoFences()
                            refreshDataTrigger++
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    message = "围栏「${editedFenceName}」已更新",
                                    duration = SnackbarDuration.Short
                                )
                            }
                        } else {
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    message = "围栏更新失败",
                                    duration = SnackbarDuration.Long
                                )
                            }
                        }
                    }
                ) {
                    Text("保存", color = Color.White)
                }
            },
            dismissButton = {
                GlassButton(onClick = { showEditFenceDialog = null }) {
                    Text("取消", color = Color.White)
                }
            }
        )
    }

}