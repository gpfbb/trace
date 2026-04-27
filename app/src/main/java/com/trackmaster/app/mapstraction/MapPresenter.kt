package com.trackmaster.app.mapstraction

/**
 * 地图交互接口
 */
interface MapPresenter {

    fun showLocation(location: MapLocation)

    fun addCircle(circle: MapCircle)

    fun addPolygon(polygon: MapPolygon)

    fun addMarker(marker: MapMarker)

    fun removeCircle(circleId: String)

    fun removePolygon(polygonId: String)

    fun removeMarker(markerId: String)

    fun clearAll()

    fun setOnMapClickListener(listener: (lat: Double, lng: Double) -> Unit)

    fun setOnMarkerClickListener(listener: (markerId: String) -> Boolean)

    fun moveCamera(lat: Double, lng: Double, zoom: Float)

    fun moveCameraWithBounds(bounds: MapBounds, padding: Int = 50)

    fun setDrawingMode(enabled: Boolean, onPolygonComplete: (List<MapLocation>) -> Unit)

    fun isDrawingMode(): Boolean

    fun getDrawingPoints(): List<MapLocation>

    fun cancelDrawing()

    fun setFenceStrokeColor(color: Int)

    fun setFenceFillColor(color: Int)

    fun refresh()

    fun getCameraCenter(): Pair<Double, Double>?

    fun getZoom(): Float?
}
