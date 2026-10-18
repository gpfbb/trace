package com.trackmaster.app.mapstraction.google

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location as AndroidLocation
import android.os.SystemClock
import android.os.Bundle
import com.trackmaster.app.utils.AppLog
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.LocationSource
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.android.gms.maps.GoogleMap.OnMyLocationButtonClickListener
import com.google.android.gms.maps.GoogleMap.OnMyLocationClickListener
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.Circle
import com.google.android.gms.maps.model.CircleOptions
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.Polygon
import com.google.android.gms.maps.model.PolygonOptions
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.trackmaster.app.mapstraction.MapBounds
import com.trackmaster.app.mapstraction.MapCircle
import com.trackmaster.app.mapstraction.MapLocation
import com.trackmaster.app.mapstraction.MapMarker
import com.trackmaster.app.mapstraction.MapPolygon
import com.trackmaster.app.mapstraction.MapPresenter
import com.trackmaster.app.mapstraction.MarkerIcon
import com.trackmaster.app.locationservice.Location
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class GoogleMapPresenter(
    private val mapView: MapView,
    private val lifecycleOwner: Lifecycle
) : MapPresenter, OnMapReadyCallback, LocationSource {

    companion object {
        private const val TAG = "GoogleMapPresenter"
    }

    private var googleMap: GoogleMap? = null
    private val _isMapReady = MutableStateFlow(false)
    val isMapReady: StateFlow<Boolean> = _isMapReady
    private var lifecycleObserver: LifecycleEventObserver? = null

    // 记录 configureMap 的参数，等地图就绪后应用
    private var pendingMapType: Int? = null
    private val circleOverlays = mutableMapOf<String, Circle>()
    private val polygonOverlays = mutableMapOf<String, Polygon>()
    private val markerOverlays = mutableMapOf<String, Marker>()

    private var onMapClickListener: ((Double, Double) -> Unit)? = null
    private var onMarkerClickListener: ((String) -> Boolean)? = null
    private var onCameraCenterChangeListener: ((Double, Double) -> Unit)? = null

    // getMapAsync 失败时标记
    private var isMapAsyncFailed = false

    // LocationSource 回调（由 Google Maps SDK 调用）
    private var locationSourceListener: LocationSource.OnLocationChangedListener? = null
    // 保存最后已知位置，用于 showMyLocation 自动跳转
    private var lastKnownLocation: Location? = null

    init {
        AppLog.d(TAG, "GoogleMapPresenter 初始化")
        setupLifecycle()
    }

    // ============================================================
    // LocationSource 实现 — 接收外部位置（来自 AMap）并传给 Google Maps 渲染蓝点
    // ============================================================
    override fun activate(listener: LocationSource.OnLocationChangedListener) {
        AppLog.d(TAG, "LocationSource.activate() 被 Google Maps SDK 调用")
        locationSourceListener = listener
    }

    override fun deactivate() {
        AppLog.d(TAG, "LocationSource.deactivate() 被 Google Maps SDK 调用")
        locationSourceListener = null
    }

    /**
     * 外部调用（来自 GoogleMapWrapper）将 AMap 的位置送入 LocationSource
     * Google Maps 会把这个位置当作"我的位置"蓝点显示
     */
    fun setExternalLocation(location: Location) {
        lastKnownLocation = location
        val listener = locationSourceListener
        if (listener == null) {
            AppLog.w(TAG, "setExternalLocation: listener 还未注册（LocationSource 未激活），跳过")
            return
        }
        val googleLoc = AndroidLocation(location.locationType.name).apply {
            latitude = location.latitude
            longitude = location.longitude
            accuracy = location.accuracy
            bearing = location.bearing
            speed = location.speed
            time = location.timestamp
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }
        AppLog.d(TAG, "setExternalLocation: 喂入位置 lat=${location.latitude} lng=${location.longitude} accuracy=${location.accuracy} bearing=${location.bearing} speed=${location.speed}")
        listener.onLocationChanged(googleLoc)
        // 相机跳转（只在首次 LocationSource 激活时触发一次）
        navigateCameraToLocation(location)
    }

    private fun getMap(): GoogleMap? = googleMap

    override fun onMapReady(map: GoogleMap) {
        AppLog.d(TAG, "=== onMapReady() 被调用 ===")
        googleMap = map
        _isMapReady.value = true
        AppLog.d(TAG, "onMapReady: googleMap 已赋值，isMapReady=true")

        // 应用之前积压的 mapType 配置
        pendingMapType?.let { type ->
            AppLog.d(TAG, "onMapReady: 应用积压的 mapType=$type")
            map.mapType = type
            pendingMapType = null
        } ?: AppLog.d(TAG, "onMapReady: 无积压 mapType")

        setupMapOnReady(map)
    }

    fun onMapAsyncError(errorMessage: String) {
        AppLog.e(TAG, "getMapAsync 失败: $errorMessage")
        isMapAsyncFailed = true
    }

    private fun setupMapOnReady(map: GoogleMap) {
        map.uiSettings.apply {
            isZoomControlsEnabled = true
            isCompassEnabled = true
            isMyLocationButtonEnabled = true
        }
        map.isBuildingsEnabled = true

        // 设置 LocationSource：用 AMap 的位置数据（通过 setExternalLocation 喂入）
        // Google Maps 会把这个位置当作"我的位置"蓝点显示
        map.setLocationSource(this)

        map.setOnMapClickListener { latLng ->
            onMapClickListener?.invoke(latLng.latitude, latLng.longitude)
        }

        map.setOnCameraMoveStartedListener {
            // not used
        }

        map.setOnCameraMoveListener {
            val position = map.cameraPosition
            onCameraCenterChangeListener?.invoke(position.target.latitude, position.target.longitude)
        }

        map.setOnMapLoadedCallback {
            val position = map.cameraPosition
            onCameraCenterChangeListener?.invoke(position.target.latitude, position.target.longitude)
        }

        map.setOnMarkerClickListener { marker ->
            val markerId = markerOverlays.entries.find { it.value == marker }?.key
            if (markerId != null) {
                onMarkerClickListener?.invoke(markerId) ?: false
            } else {
                false
            }
        }

        // 点击"我的位置"按钮时，SDK 使用 setExternalLocation 喂入的位置执行相机动画
        map.setOnMyLocationButtonClickListener {
            AppLog.d(TAG, "用户点击了我的位置按钮")
            false
        }
    }

    private fun tryEnableMyLocation(map: GoogleMap, context: Context) {
        AppLog.d(TAG, "tryEnableMyLocation: 开始检查")

        // 1. 检查 Google Play Services 是否可用
        val gmsResult = GoogleApiAvailability.getInstance()
        val gmsStatus = gmsResult.isGooglePlayServicesAvailable(context)
        val gmsSuccess = gmsStatus == com.google.android.gms.common.ConnectionResult.SUCCESS
        AppLog.d(TAG, "tryEnableMyLocation: GMS状态码=$gmsStatus (SUCCESS=0, 含义=${gmsResult.getErrorString(gmsStatus)})")
        if (!gmsSuccess) {
            AppLog.e(TAG, "tryEnableMyLocation: Google Play Services 不可用($gmsStatus)，MyLocation 无法启用")
            return
        }
        AppLog.d(TAG, "tryEnableMyLocation: Google Play Services 可用")

        // 2. 检查位置权限
        val hasFineLocation = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasCoarseLocation = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        AppLog.d(TAG, "tryEnableMyLocation: ACCESS_FINE_LOCATION=$hasFineLocation, ACCESS_COARSE_LOCATION=$hasCoarseLocation")

        if (!hasFineLocation && !hasCoarseLocation) {
            AppLog.w(TAG, "tryEnableMyLocation: 位置权限均未授予，MyLocation 无法启用")
            return
        }
        AppLog.d(TAG, "tryEnableMyLocation: 权限检查通过，尝试启用蓝点")

        // 3. 启用蓝点和定位按钮
        try {
            map.isMyLocationEnabled = true
            map.uiSettings.isMyLocationButtonEnabled = true
            AppLog.d(TAG, "✅ tryEnableMyLocation: 蓝点+定位按钮已启用（Fine=$hasFineLocation, Coarse=$hasCoarseLocation）")
        } catch (e: Exception) {
            AppLog.e(TAG, "❌ tryEnableMyLocation: 蓝点启用失败: ${e.message}", e)
        }
    }

    private fun setupLifecycle() {
        lifecycleObserver = LifecycleEventObserver { _, e ->
            try {
                when (e) {
                    Lifecycle.Event.ON_START -> {
                        mapView.onStart()
                    }
                    Lifecycle.Event.ON_RESUME -> {
                        mapView.onResume()
                    }
                    Lifecycle.Event.ON_PAUSE -> {
                        mapView.onPause()
                    }
                    Lifecycle.Event.ON_STOP -> {
                        mapView.onStop()
                    }
                    Lifecycle.Event.ON_DESTROY -> {
                        AppLog.d(TAG, "lifecycle: ON_DESTROY")
                        cleanup()
                        lifecycleObserver?.let { lifecycleOwner.removeObserver(it) }  // 【P1修复】移除observer，防止内存泄漏
                        mapView.onDestroy()
                    }
                    else -> {}
                }
            } catch (ex: Exception) {
                AppLog.e(TAG, "生命周期错误: ${e.name}", ex)
            }
        }
        lifecycleObserver?.let { lifecycleOwner.addObserver(it) }
    }

    override fun showLocation(location: MapLocation) {
        getMap()?.animateCamera(
            CameraUpdateFactory.newLatLngZoom(
                LatLng(location.latitude, location.longitude),
                16f
            )
        )
    }

    override fun addCircle(circle: MapCircle) {
        val map = getMap() ?: return
        try {
            val options = CircleOptions()
                .center(LatLng(circle.center.latitude, circle.center.longitude))
                .radius(circle.radius)
                .strokeColor(circle.strokeColor)
                .fillColor(circle.fillColor)
                .strokeWidth(circle.strokeWidth)

            val gmsCircle = map.addCircle(options)
            if (gmsCircle != null) {
                circleOverlays[circle.id] = gmsCircle
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "添加圆形失败: ${circle.id}", e)
        }
    }

    override fun addPolygon(polygon: MapPolygon) {
        val map = getMap() ?: return
        try {
            val options = PolygonOptions()
                .addAll(polygon.points.map { LatLng(it.latitude, it.longitude) })
                .strokeColor(polygon.strokeColor)
                .fillColor(polygon.fillColor)
                .strokeWidth(polygon.strokeWidth)

            val gmsPolygon = map.addPolygon(options)
            if (gmsPolygon != null) {
                polygonOverlays[polygon.id] = gmsPolygon
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "添加多边形失败: ${polygon.id}", e)
        }
    }

    override fun addMarker(marker: MapMarker) {
        val map = getMap() ?: return
        try {
            val options = MarkerOptions()
                .position(LatLng(marker.position.latitude, marker.position.longitude))
                .title(marker.title)
                .snippet(marker.snippet)

            val hue = when (marker.icon.name) {
                "START" -> BitmapDescriptorFactory.HUE_GREEN
                "END" -> BitmapDescriptorFactory.HUE_RED
                "CENTER" -> BitmapDescriptorFactory.HUE_ORANGE
                else -> BitmapDescriptorFactory.HUE_AZURE
            }
            options.icon(BitmapDescriptorFactory.defaultMarker(hue))

            val gmsMarker = map.addMarker(options)
            if (gmsMarker != null) {
                markerOverlays[marker.id] = gmsMarker
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "添加标注失败: ${marker.id}", e)
        }
    }

    override fun removeCircle(circleId: String) {
        circleOverlays[circleId]?.remove()
        circleOverlays.remove(circleId)
    }

    override fun removePolygon(polygonId: String) {
        polygonOverlays[polygonId]?.remove()
        polygonOverlays.remove(polygonId)
    }

    override fun removeMarker(markerId: String) {
        markerOverlays[markerId]?.remove()
        markerOverlays.remove(markerId)
    }

    override fun clearAll() {
        circleOverlays.values.forEach { it.remove() }
        circleOverlays.clear()
        polygonOverlays.values.forEach { it.remove() }
        polygonOverlays.clear()
        markerOverlays.values.forEach { it.remove() }
        markerOverlays.clear()
    }

    override fun setOnMapClickListener(listener: (Double, Double) -> Unit) {
        onMapClickListener = listener
    }

    override fun setOnMarkerClickListener(listener: (String) -> Boolean) {
        onMarkerClickListener = listener
    }

    fun setOnCameraCenterChangeListener(listener: (Double, Double) -> Unit) {
        onCameraCenterChangeListener = listener
    }

    override fun moveCamera(lat: Double, lng: Double, zoom: Float) {
        getMap()?.animateCamera(
            CameraUpdateFactory.newLatLngZoom(LatLng(lat, lng), zoom)
        )
    }

    override fun moveCameraWithBounds(bounds: MapBounds, padding: Int) {
        val map = getMap() ?: return
        try {
            val latLngBounds = LatLngBounds.builder()
                .include(LatLng(bounds.northEast.latitude, bounds.northEast.longitude))
                .include(LatLng(bounds.southWest.latitude, bounds.southWest.longitude))
                .build()
            map.animateCamera(
                CameraUpdateFactory.newLatLngBounds(latLngBounds, padding)
            )
        } catch (e: Exception) {
            AppLog.e(TAG, "移动相机到边界失败", e)
        }
    }

    override fun setDrawingMode(enabled: Boolean, onPolygonComplete: (List<MapLocation>) -> Unit) {
        // handled by UI layer
    }

    override fun isDrawingMode(): Boolean = false

    override fun getDrawingPoints(): List<MapLocation> = emptyList()

    override fun cancelDrawing() {}

    override fun setFenceStrokeColor(color: Int) {}

    override fun setFenceFillColor(color: Int) {}

    override fun refresh() {}

    fun showMyLocation() {
        AppLog.d(TAG, "=== showMyLocation() 被调用 ===")
        val map = googleMap ?: run {
            AppLog.w(TAG, "showMyLocation: googleMap 未就绪，跳过")
            return
        }
        val ctx = mapView.context
        tryEnableMyLocation(map, ctx)
        // 自动跳转到用户最后已知位置
        // 如果 lastKnownLocation 为空（权限刚批/地图刚就绪），等待 setExternalLocation 触发跳转
        lastKnownLocation?.let { loc ->
            AppLog.d(TAG, "showMyLocation: 自动跳转至用户位置 lat=${loc.latitude} lng=${loc.longitude}")
            map.animateCamera(
                CameraUpdateFactory.newLatLngZoom(
                    LatLng(loc.latitude, loc.longitude), 17f
                )
            )
        } ?: AppLog.d(TAG, "showMyLocation: lastKnownLocation 为空，等待 setExternalLocation 触发跳转")
    }

    /**
     * 相机跳转锁定标记：避免重复触发动画
     */
    private var hasCameraLockedToUser = false

    /** 锁定相机跳转（恢复上次位置时调用，防止 setExternalLocation 再次跳转） */
    fun lockCameraToUser() {
        hasCameraLockedToUser = true
    }

    /**
     * setExternalLocation 时检查是否需要相机跳转
     */
    private fun navigateCameraToLocation(location: Location) {
        val map = googleMap ?: return
        if (!hasCameraLockedToUser) {
            hasCameraLockedToUser = true
            AppLog.d(TAG, "setExternalLocation: 首次跳转相机至用户 lat=${location.latitude} lng=${location.longitude}")
            map.animateCamera(
                CameraUpdateFactory.newLatLngZoom(
                    LatLng(location.latitude, location.longitude), 17f
                )
            )
        }
    }

    /**
     * 当位置权限被授予时调用，尝试启用蓝点
     * 由 GoogleMapWrapper 在权限授予后的回调里调用
     */
    fun onLocationPermissionGranted() {
        AppLog.d(TAG, "=== onLocationPermissionGranted() 被调用 ===")
        val map = googleMap ?: run {
            AppLog.w(TAG, "onLocationPermissionGranted: googleMap 还未就续，跳过")
            return
        }
        val ctx = mapView.context
        tryEnableMyLocation(map, ctx)
        lastKnownLocation?.let { loc ->
            AppLog.d(TAG, "onLocationPermissionGranted: 自动跳转至用户位置 lat=${loc.latitude} lng=${loc.longitude}")
            map.animateCamera(
                CameraUpdateFactory.newLatLngZoom(
                    LatLng(loc.latitude, loc.longitude), 17f
                )
            )
        }
    }

    fun hideMyLocation() {
        // 这个 setter 在缺定位权限时会抛 SecurityException（Google Maps SDK 的
        // 契约如此，即使写的是 false）。这里只是关掉蓝点，失败不影响其他功能。
        try {
            googleMap?.isMyLocationEnabled = false
        } catch (e: SecurityException) {
            AppLog.w(TAG, "hideMyLocation: 缺少定位权限，跳过: ${e.message}")
        }
    }

    fun configureMap(mapType: Int) {
        val map = googleMap ?: run {
            pendingMapType = mapType
            return
        }
        // Google Maps SDK mapType 常量：
        // MAP_TYPE_NORMAL = 1, MAP_TYPE_SATELLITE = 2, MAP_TYPE_TERRAIN = 3, MAP_TYPE_HYBRID = 4
        // AMap: 1=normal, 2=satellite, 3=night, 4=navigation
        when (mapType) {
            1 -> {
                map.mapType = GoogleMap.MAP_TYPE_NORMAL
                map.setMapStyle(null)  // 清除夜景样式
                AppLog.d(TAG, "configureMap: mapType=1 → NORMAL")
            }
            2 -> {
                map.mapType = GoogleMap.MAP_TYPE_SATELLITE
                map.setMapStyle(null)
                AppLog.d(TAG, "configureMap: mapType=2 → SATELLITE")
            }
            3 -> {
                // 夜景模式：使用深色 JSON style
                map.mapType = GoogleMap.MAP_TYPE_NORMAL
                try {
                    val styleJson = mapView.context.assets
                        .open("night_style.json")
                        .bufferedReader()
                        .use { it.readText() }
                    val success = map.setMapStyle(MapStyleOptions(styleJson))
                    if (success) {
                        AppLog.d(TAG, "configureMap: 夜景样式应用成功")
                    } else {
                        AppLog.e(TAG, "configureMap: 夜景样式应用失败（未知错误）")
                    }
                } catch (e: Exception) {
                    AppLog.e(TAG, "configureMap: 夜景样式加载异常: ${e.message}")
                }
            }
            else -> {
                map.mapType = GoogleMap.MAP_TYPE_NORMAL
            }
        }
    }

    // ============================================================
    // 生命周期
    // ============================================================

    fun onCreate(savedInstanceState: Bundle?) {
        AppLog.d(TAG, "=== onCreate() 被调用 ===")
        mapView.onCreate(savedInstanceState)
        AppLog.d(TAG, "onCreate: MapView.onCreate 完成，调用 getMapAsync")
        try {
            mapView.getMapAsync(this)
            AppLog.d(TAG, "onCreate: getMapAsync 已调用，等待 onMapReady 回调")
        } catch (e: Exception) {
            AppLog.e(TAG, "onCreate: getMapAsync 调用失败: ${e.message}", e)
            onMapAsyncError(e.message ?: "未知错误")
        }
    }

    fun onResume() {
        mapView.onResume()
    }

    fun onPause() {
        mapView.onPause()
    }

    fun onDestroy() {
        cleanup()
        mapView.onDestroy()
    }

    fun onSaveInstanceState(outState: Bundle) {
        mapView.onSaveInstanceState(outState)
    }

    private fun cleanup() {
        clearAll()
        onMapClickListener = null
        onMarkerClickListener = null
        onCameraCenterChangeListener = null
        hasCameraLockedToUser = false
    }

    internal fun getGoogleMapInternal(): GoogleMap? = googleMap

    override fun getCameraCenter(): Pair<Double, Double>? {
        val map = googleMap ?: return null
        val target = map.cameraPosition.target
        return target.latitude to target.longitude
    }

    override fun getZoom(): Float? {
        return try { googleMap?.cameraPosition?.zoom } catch (e: Exception) { null }
    }
}
