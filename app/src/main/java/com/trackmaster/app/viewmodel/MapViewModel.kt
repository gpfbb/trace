package com.trackmaster.app.viewmodel

import android.app.Application
import androidx.lifecycle.ViewModel
import com.trackmaster.app.GeoFence
import com.trackmaster.app.GeoFencePoint
import com.trackmaster.app.GeoFenceType
import com.trackmaster.app.TrackMasterApplication
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/**
 * 地图 ViewModel - 管理地图绘制模式、覆盖物和编辑状态
 *
 * 职责：
 * - 管理绘制模式（圆形/多边形）
 * - 管理临时绘制点
 * - 管理编辑中的围栏
 * - 管理地图设置（地图类型、交通、热度图）
 */
class MapViewModel(
    private val application: Application
) : ViewModel() {

    private val app: TrackMasterApplication
        get() = application as TrackMasterApplication

    // ============================================================
    // 绘制状态
    // ============================================================

    private val _isDrawingMode = MutableStateFlow(false)
    val isDrawingMode: StateFlow<Boolean> = _isDrawingMode

    private val _drawingPoints = MutableStateFlow<List<GeoFencePoint>>(emptyList())
    val drawingPoints: StateFlow<List<GeoFencePoint>> = _drawingPoints

    private val _selectedFenceType = MutableStateFlow(GeoFenceType.CIRCLE)
    val selectedFenceType: StateFlow<GeoFenceType> = _selectedFenceType

    // ============================================================
    // 编辑状态
    // ============================================================

    private val _isEditingFence = MutableStateFlow(false)
    val isEditingFence: StateFlow<Boolean> = _isEditingFence

    private val _editingFence = MutableStateFlow<GeoFence?>(null)
    val editingFence: StateFlow<GeoFence?> = _editingFence

    // ============================================================
    // 地图设置
    // ============================================================

    private val _mapType = MutableStateFlow(1) // AMap.MAP_TYPE_NORMAL
    val mapType: StateFlow<Int> = _mapType

    private val _trafficEnabled = MutableStateFlow(false)
    val trafficEnabled: StateFlow<Boolean> = _trafficEnabled

    private val _heatmapEnabled = MutableStateFlow(false)
    val heatmapEnabled: StateFlow<Boolean> = _heatmapEnabled

    // ============================================================
    // 围栏操作
    // ============================================================

    /**
     * 切换绘制模式
     */
    fun toggleDrawingMode() {
        _isDrawingMode.value = !_isDrawingMode.value
        if (!_isDrawingMode.value) {
            // 退出绘制模式时清除绘制点
            _drawingPoints.value = emptyList()
        }
    }

    /**
     * 退出绘制模式
     */
    fun exitDrawingMode() {
        _isDrawingMode.value = false
        _drawingPoints.value = emptyList()
    }

    /**
     * 选择围栏类型
     */
    fun selectFenceType(type: GeoFenceType) {
        _selectedFenceType.value = type
        // 切换类型时清除绘制点
        _drawingPoints.value = emptyList()
    }

    /**
     * 添加绘制点
     */
    fun addDrawingPoint(point: GeoFencePoint) {
        _drawingPoints.value = _drawingPoints.value + point
    }

    /**
     * 清除最后一个绘制点
     */
    fun removeLastDrawingPoint() {
        if (_drawingPoints.value.isNotEmpty()) {
            _drawingPoints.value = _drawingPoints.value.dropLast(1)
        }
    }

    /**
     * 创建圆形围栏
     */
    fun createCircleFence(
        name: String,
        center: GeoFencePoint,
        radius: Float,
        color: String = "#FF0000",
        alpha: Float = 0.3f
    ): GeoFence {
        val fence = GeoFence(
            id = UUID.randomUUID().toString(),
            name = name,
            type = GeoFenceType.CIRCLE,
            center = center,
            radius = radius,
            color = color,
            alpha = alpha
        )
        app.geoFences.add(fence)
        app.dbHelper.insertFence(fence)
        app.refreshEngineFences()
        exitDrawingMode()
        return fence
    }

    /**
     * 创建多边形围栏
     */
    fun createPolygonFence(
        name: String,
        points: List<GeoFencePoint>,
        color: String = "#FF0000",
        alpha: Float = 0.3f
    ): GeoFence? {
        if (points.size < 3) {
            return null
        }
        val fence = GeoFence(
            id = UUID.randomUUID().toString(),
            name = name,
            type = GeoFenceType.POLYGON,
            points = points,
            color = color,
            alpha = alpha
        )
        app.geoFences.add(fence)
        app.dbHelper.insertFence(fence)
        app.refreshEngineFences()
        exitDrawingMode()
        return fence
    }

    /**
     * 开始编辑围栏
     */
    fun startEditingFence(fence: GeoFence) {
        _isEditingFence.value = true
        _editingFence.value = fence
    }

    /**
     * 取消编辑
     */
    fun cancelEditing() {
        _isEditingFence.value = false
        _editingFence.value = null
    }

    /**
     * 保存围栏编辑
     */
    fun saveFence(fence: GeoFence): Boolean {
        val result = app.dbHelper.updateFence(fence) > 0
        if (result) {
            val index = app.geoFences.indexOfFirst { it.id == fence.id }
            if (index >= 0) {
                app.geoFences[index] = fence
            }
            app.refreshEngineFences()
        }
        cancelEditing()
        return result
    }

    /**
     * 删除围栏
     */
    fun deleteFence(fenceId: String) {
        app.geoFences.removeIf { it.id == fenceId }
        app.dbHelper.deleteFence(fenceId)
        app.refreshEngineFences()
        cancelEditing()
    }

    // ============================================================
    // 地图设置操作
    // ============================================================

    /**
     * 设置地图类型
     */
    fun setMapType(type: Int) {
        _mapType.value = type
    }

    /**
     * 切换交通图层
     */
    fun toggleTraffic() {
        _trafficEnabled.value = !_trafficEnabled.value
    }

    /**
     * 切换热力图
     */
    fun toggleHeatmap() {
        _heatmapEnabled.value = !_heatmapEnabled.value
    }
}
