package com.trackmaster.app.mapstraction.amap

import android.graphics.Color
import android.os.Bundle
import android.util.Log
import com.amap.api.location.AMapLocation as AMapLoc
import com.amap.api.location.AMapLocationListener
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapView
import com.amap.api.maps.model.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.trackmaster.app.locationservice.Location
import com.trackmaster.app.mapstraction.MapBounds
import com.trackmaster.app.mapstraction.MapCircle
import com.trackmaster.app.mapstraction.MapLocation
import com.trackmaster.app.mapstraction.MapMarker
import com.trackmaster.app.mapstraction.MapPolygon
import com.trackmaster.app.mapstraction.MapPresenter

class AMapPresenter(
    private val mapView: MapView,
    private val lifecycleOwner: androidx.lifecycle.LifecycleOwner
) : MapPresenter {

    companion object {
        private const val TAG = "AMapPresenter"
    }

    private val aMap: AMap by lazy { mapView.map }

    private val circleOverlays = mutableMapOf<String, Circle>()
    private val polygonOverlays = mutableMapOf<String, Polygon>()
    private val markerOverlays = mutableMapOf<String, Marker>()

    // 临时绘制标记（绘制模式）
    private val tempMarkerOverlays = mutableMapOf<String, Marker>()

    private var onMapClickListener: ((Double, Double) -> Unit)? = null
    private var onMarkerClickListener: ((String) -> Boolean)? = null
    private var onCameraCenterChangeListener: ((Double, Double) -> Unit)? = null

    private var isDrawingModeEnabled = false
    private var onPolygonCompleteListener: ((List<MapLocation>) -> Unit)? = null
    private val drawingPointsList = mutableListOf<MapLocation>()

    private var fenceStrokeColor = 0xFF00FF00.toInt()
    private var fenceFillColor = 0x4000FF00

    init {
        setupMap()
        setupLifecycle()
    }

    private fun setupMap() {
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
            Log.e(TAG, "定位样式设置失败", e)
        }

        aMap.setOnMapClickListener { latLng ->
            onMapClickListener?.invoke(latLng.latitude, latLng.longitude)
        }

        aMap.setOnCameraChangeListener(object : AMap.OnCameraChangeListener {
            override fun onCameraChange(p0: CameraPosition) {
                // 持续触发：拖动中持续保存相机中心点
                onCameraCenterChangeListener?.invoke(p0.target.latitude, p0.target.longitude)
            }
            override fun onCameraChangeFinish(p0: CameraPosition) {
                // onCameraChange 已经处理持续保存，onCameraChangeFinish 仅作额外保障
                onCameraCenterChangeListener?.invoke(p0.target.latitude, p0.target.longitude)
            }
        })

        aMap.setOnMarkerClickListener { marker ->
            val markerId = markerOverlays.entries.find { it.value == marker }?.key
                ?: tempMarkerOverlays.entries.find { it.value == marker }?.key
            if (markerId != null) {
                onMarkerClickListener?.invoke(markerId) ?: false
            } else {
                false
            }
        }
    }

    private fun setupLifecycle() {
        val observer = LifecycleEventObserver { _, e ->
            try {
                when (e) {
                    Lifecycle.Event.ON_RESUME -> mapView.onResume()
                    Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                    Lifecycle.Event.ON_DESTROY -> {
                        cleanup()
                        mapView.onDestroy()
                    }
                    else -> {}
                }
            } catch (ex: Exception) {
                Log.e(TAG, "生命周期错误: ${e.name}", ex)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
    }

    override fun showLocation(location: MapLocation) {
        try {
            aMap.animateCamera(
                CameraUpdateFactory.newLatLngZoom(
                    LatLng(location.latitude, location.longitude),
                    16f
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "显示位置失败", e)
        }
    }

    override fun addCircle(circle: MapCircle) {
        try {
            val circleOptions = CircleOptions()
                .center(LatLng(circle.center.latitude, circle.center.longitude))
                .radius(circle.radius)
                .strokeColor(circle.strokeColor)
                .fillColor(circle.fillColor)
                .strokeWidth(circle.strokeWidth)

            val amapCircle = aMap.addCircle(circleOptions)
            circleOverlays[circle.id] = amapCircle
        } catch (e: Exception) {
            Log.e(TAG, "添加圆形失败: ${circle.id}", e)
        }
    }

    override fun addPolygon(polygon: MapPolygon) {
        try {
            val points = polygon.points.map { LatLng(it.latitude, it.longitude) }
            val polygonOptions = PolygonOptions()
                .addAll(points)
                .strokeColor(polygon.strokeColor)
                .fillColor(polygon.fillColor)
                .strokeWidth(polygon.strokeWidth)

            val amapPolygon = aMap.addPolygon(polygonOptions)
            polygonOverlays[polygon.id] = amapPolygon
        } catch (e: Exception) {
            Log.e(TAG, "添加多边形失败: ${polygon.id}", e)
        }
    }

    override fun addMarker(marker: MapMarker) {
        try {
            val markerOptions = MarkerOptions()
                .position(LatLng(marker.position.latitude, marker.position.longitude))
                .title(marker.title)
                .snippet(marker.snippet)

            val hue = when (marker.icon.name) {
                "START" -> BitmapDescriptorFactory.HUE_GREEN
                "END" -> BitmapDescriptorFactory.HUE_RED
                "CENTER" -> BitmapDescriptorFactory.HUE_ORANGE
                else -> BitmapDescriptorFactory.HUE_AZURE
            }
            markerOptions.icon(BitmapDescriptorFactory.defaultMarker(hue))

            val amapMarker = aMap.addMarker(markerOptions)
            if (amapMarker != null) {
                markerOverlays[marker.id] = amapMarker
            }
        } catch (e: Exception) {
            Log.e(TAG, "添加标注失败: ${marker.id}", e)
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
        tempMarkerOverlays.values.forEach { it.remove() }
        tempMarkerOverlays.clear()
    }

    override fun setOnMapClickListener(listener: (Double, Double) -> Unit) {
        onMapClickListener = listener
    }

    override fun setOnMarkerClickListener(listener: (String) -> Boolean) {
        onMarkerClickListener = listener
    }

    override fun moveCamera(lat: Double, lng: Double, zoom: Float) {
        try {
            aMap.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lng), zoom))
        } catch (e: Exception) {
            Log.e(TAG, "移动相机失败", e)
        }
    }

    fun getCameraCenter(): Pair<Double, Double>? {
        return try {
            val target = aMap.cameraPosition.target
            target.latitude to target.longitude
        } catch (e: Exception) {
            null
        }
    }

    fun getZoom(): Float? {
        return try { aMap.cameraPosition.zoom } catch (e: Exception) { null }
    }

    override fun moveCameraWithBounds(bounds: MapBounds, padding: Int) {
        try {
            val rect = LatLngBounds.builder()
                .include(LatLng(bounds.northEast.latitude, bounds.northEast.longitude))
                .include(LatLng(bounds.southWest.latitude, bounds.southWest.longitude))
                .build()
            aMap.animateCamera(CameraUpdateFactory.newLatLngBounds(rect, padding))
        } catch (e: Exception) {
            Log.e(TAG, "移动相机到边界失败", e)
        }
    }

    override fun setDrawingMode(enabled: Boolean, onPolygonComplete: (List<MapLocation>) -> Unit) {
        isDrawingModeEnabled = enabled
        onPolygonCompleteListener = onPolygonComplete
        if (!enabled) {
            drawingPointsList.clear()
        }
    }

    override fun isDrawingMode(): Boolean = isDrawingModeEnabled

    override fun getDrawingPoints(): List<MapLocation> = drawingPointsList.toList()

    override fun cancelDrawing() {
        isDrawingModeEnabled = false
        onPolygonCompleteListener = null
        drawingPointsList.clear()
    }

    override fun setFenceStrokeColor(color: Int) {
        fenceStrokeColor = color
    }

    override fun setFenceFillColor(color: Int) {
        fenceFillColor = color
    }

    fun setOnCameraCenterChangeListener(listener: (Double, Double) -> Unit) {
        onCameraCenterChangeListener = listener
    }

    override fun refresh() {
        // Trigger map reload if needed
    }

    /** 内部使用，获取底层 AMap 实例（仅供 MainActivity 配置 mapType/traffic） */
    internal fun getAMapInternal(): AMap = aMap

    // ============================================================
    // 绘制模式标记管理
    // ============================================================

    fun addTempMarker(marker: MapMarker) {
        try {
            val markerOptions = MarkerOptions()
                .position(LatLng(marker.position.latitude, marker.position.longitude))
                .title(marker.title)
                .snippet(marker.snippet)

            val hue = when (marker.icon.name) {
                "START" -> BitmapDescriptorFactory.HUE_GREEN
                "END" -> BitmapDescriptorFactory.HUE_RED
                "CENTER" -> BitmapDescriptorFactory.HUE_ORANGE
                else -> BitmapDescriptorFactory.HUE_AZURE
            }
            markerOptions.icon(BitmapDescriptorFactory.defaultMarker(hue))

            val amapMarker = aMap.addMarker(markerOptions)
            if (amapMarker != null) {
                tempMarkerOverlays[marker.id] = amapMarker
            }
        } catch (e: Exception) {
            Log.e(TAG, "添加临时标注失败: ${marker.id}", e)
        }
    }

    fun clearTempMarkers() {
        tempMarkerOverlays.values.forEach { it.remove() }
        tempMarkerOverlays.clear()
    }

    // ============================================================
    // 编辑模式标记管理
    // ============================================================

    fun addEditingMarker(marker: MapMarker) {
        try {
            val markerOptions = MarkerOptions()
                .position(LatLng(marker.position.latitude, marker.position.longitude))
                .title(marker.title)
                .snippet(marker.snippet)
                .draggable(true)

            val amapMarker = aMap.addMarker(markerOptions)
            if (amapMarker != null) {
                markerOverlays[marker.id] = amapMarker
            }
        } catch (e: Exception) {
            Log.e(TAG, "添加编辑标注失败: ${marker.id}", e)
        }
    }

    fun addEditingCircle(circle: MapCircle) {
        try {
            val circleOptions = CircleOptions()
                .center(LatLng(circle.center.latitude, circle.center.longitude))
                .radius(circle.radius)
                .strokeColor(circle.strokeColor)
                .fillColor(circle.fillColor)
                .strokeWidth(circle.strokeWidth)

            val amapCircle = aMap.addCircle(circleOptions)
            circleOverlays[circle.id] = amapCircle
        } catch (e: Exception) {
            Log.e(TAG, "添加编辑圆形失败: ${circle.id}", e)
        }
    }

    fun addEditingPolygon(polygon: MapPolygon) {
        try {
            val points = polygon.points.map { LatLng(it.latitude, it.longitude) }
            val polygonOptions = PolygonOptions()
                .addAll(points)
                .strokeColor(polygon.strokeColor)
                .fillColor(polygon.fillColor)
                .strokeWidth(polygon.strokeWidth)

            val amapPolygon = aMap.addPolygon(polygonOptions)
            polygonOverlays[polygon.id] = amapPolygon
        } catch (e: Exception) {
            Log.e(TAG, "添加编辑多边形失败: ${polygon.id}", e)
        }
    }

    // ============================================================
    // 热力图管理
    // ============================================================

    // ============================================================
    // 地图配置
    // ============================================================

    fun configureMap(
        mapType: Int,
        zoomControlsEnabled: Boolean = true,
        compassEnabled: Boolean = true,
        scaleControlsEnabled: Boolean = true,
        myLocationEnabled: Boolean = true
    ) {
        aMap.mapType = mapType
        aMap.uiSettings.isZoomControlsEnabled = zoomControlsEnabled
        aMap.uiSettings.isCompassEnabled = compassEnabled
        aMap.uiSettings.isScaleControlsEnabled = scaleControlsEnabled
        aMap.uiSettings.isMyLocationButtonEnabled = myLocationEnabled
    }

    fun setMyLocationEnabled(enabled: Boolean) {
        aMap.isMyLocationEnabled = enabled
    }

    private var myLocationListener: ((Location?) -> Unit)? = null

    fun setOnMyLocationChangeListener(listener: (Location?) -> Unit) {
        myLocationListener = listener
        aMap.setOnMyLocationChangeListener(myLocationConverter)
    }

    private val myLocationConverter = AMap.OnMyLocationChangeListener { androidLoc ->
        if (androidLoc == null) {
            myLocationListener?.invoke(null)
        } else {
            // AMap.OnMyLocationChangeListener passes android.location.Location
            // Convert to our Location type
            val ourLoc = Location(
                latitude = androidLoc.latitude,
                longitude = androidLoc.longitude,
                accuracy = androidLoc.accuracy,
                speed = androidLoc.speed,
                bearing = androidLoc.bearing,
                timestamp = androidLoc.time,
                locationType = when (androidLoc.provider) {
                    "gps" -> com.trackmaster.app.locationservice.LocationType.GPS
                    "network" -> com.trackmaster.app.locationservice.LocationType.NETWORK
                    else -> com.trackmaster.app.locationservice.LocationType.UNKNOWN
                }
            )
            myLocationListener?.invoke(ourLoc)
        }
    }

    // ============================================================
    // 生命周期
    // ============================================================

    fun onCreate(savedInstanceState: Bundle?) {
        mapView.onCreate(savedInstanceState)
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
        onPolygonCompleteListener = null
        drawingPointsList.clear()
    }
}
