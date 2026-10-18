package com.trackmaster.app.ui.map

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.trackmaster.app.core.GeoFenceType
import com.trackmaster.app.data.GeoFence
import com.trackmaster.app.data.GeoFencePoint

/**
 * 地图上的绘制/编辑状态机。
 *
 * ## 为什么要收成一个状态机
 *
 * 原来这块是散在 `MapScreen` 里的 10 个独立状态：
 *
 * ```
 * isDrawingMode, selectedFenceType, drawingPoints, drawingPrecisionMode
 * isEditingFence, editingFence, editingPoints, editingPrecisionMode
 * selectedColor, selectedAlpha
 * ```
 *
 * 它们描述的是同一件事的两个互斥阶段，但用布尔值表达时：
 * - "同时在绘制和编辑" 是能构造出来的非法状态，每处用到的地方都得
 *   自己判断 `isEditingFence && editingFence != null`（这个双重判断
 *   在代码里出现了 4 次）
 * - 点集分别存在两个变量里，地图点击时要先判断当前在哪个模式
 * - 新增一种模式（比如"移动整个围栏"）要到处加分支
 *
 * 现在用 [MapEditMode] 三态表达，非法组合从类型上就不存在。
 *
 * ## 什么没放进来
 *
 * 弹窗可见性（showFenceList / showCreateDialog / …）不在这里——它们和
 * 绘制流程没有互斥关系，是独立的 UI 开关，留在屏幕里更直观。
 */
@Stable
class MapEditorState {

    /** 当前所处的编辑阶段。 */
    var mode: MapEditMode by mutableStateOf(MapEditMode.Idle)
        private set

    /**
     * 上一次绘制用的填充颜色。
     *
     * 跨会话保留：用户画完一个围栏再画第二个，多半还想用同一个颜色。
     * 所以它不在 [MapEditMode.Drawing] 里，取消/完成后不清空。
     */
    var drawingColor: String by mutableStateOf(DEFAULT_COLOR)

    /** 上一次绘制用的透明度，同样跨会话保留。 */
    var drawingAlpha: Float by mutableStateOf(DEFAULT_ALPHA)

    /** 绘制模式的高精度开关。地图组件把两个开关做或运算后使用。 */
    var drawingPrecision: Boolean by mutableStateOf(false)

    /** 编辑模式的高精度开关，与绘制模式独立。 */
    var editingPrecision: Boolean by mutableStateOf(false)

    // ------------------------------------------------------------
    // 派生查询
    // ------------------------------------------------------------

    val isDrawing: Boolean get() = mode is MapEditMode.Drawing
    val isEditing: Boolean get() = mode is MapEditMode.Editing

    /** 当前模式需要在图上参与命中/渲染的点集。 */
    val activePoints: List<GeoFencePoint>
        get() = when (val m = mode) {
            is MapEditMode.Drawing -> m.points
            is MapEditMode.Editing -> m.points
            MapEditMode.Idle -> emptyList()
        }

    /** 当前是否处于某个会拦截地图点击的模式。 */
    val consumesMapClicks: Boolean get() = mode !is MapEditMode.Idle

    /** 高精度是否开启（两种模式共用一个地图参数）。 */
    val precisionActive: Boolean get() = drawingPrecision || editingPrecision

    /** 当前正在编辑的围栏，仅编辑模式非空。 */
    val editingFenceOrNull: GeoFence?
        get() = (mode as? MapEditMode.Editing)?.fence

    /** 当前绘制类型，仅绘制模式有意义；其余情况返回圆形作为默认。 */
    val drawingType: GeoFenceType
        get() = (mode as? MapEditMode.Drawing)?.fenceType ?: GeoFenceType.CIRCLE

    // ------------------------------------------------------------
    // 状态迁移
    // ------------------------------------------------------------

    /** 进入绘制模式。每次都从空点集和圆形开始，颜色/透明度沿用上次。 */
    fun startDrawing() {
        mode = MapEditMode.Drawing(
            fenceType = GeoFenceType.CIRCLE,
            points = emptyList()
        )
    }

    /**
     * 进入编辑模式。
     *
     * @param fence 要编辑的围栏；其顶点作为初始点集（多边形有顶点，
     *   圆形没有，所以圆形进入后点集为空，靠半径滑块调整）
     */
    fun startEditing(fence: GeoFence) {
        mode = MapEditMode.Editing(
            fence = fence,
            points = fence.points?.toList() ?: emptyList()
        )
    }

    /** 退出当前模式，回到空闲。绘制/编辑的点集一并丢弃。 */
    fun cancel() {
        mode = MapEditMode.Idle
    }

    /** 地图点击：在绘制模式加顶点，编辑模式加顶点，空闲模式忽略。 */
    fun onMapClick(latitude: Double, longitude: Double) {
        val point = GeoFencePoint(latitude, longitude)
        mode = when (val m = mode) {
            is MapEditMode.Drawing -> m.copy(points = m.points + point)
            is MapEditMode.Editing -> m.copy(points = m.points + point)
            MapEditMode.Idle -> return
        }
    }

    /** 撤销最后一个顶点。点集为空时无操作。 */
    fun undoLastPoint() {
        mode = when (val m = mode) {
            is MapEditMode.Drawing ->
                if (m.points.isEmpty()) m else m.copy(points = m.points.dropLast(1))

            is MapEditMode.Editing ->
                if (m.points.isEmpty()) m else m.copy(points = m.points.dropLast(1))

            MapEditMode.Idle -> return
        }
    }

    /** 绘制模式切换围栏类型。切换会保留已画的点（用户可能画到一半改主意）。 */
    fun setDrawingType(type: GeoFenceType) {
        val m = mode as? MapEditMode.Drawing ?: return
        mode = m.copy(fenceType = type)
    }

    companion object {
        const val DEFAULT_COLOR = "#FF0000"
        const val DEFAULT_ALPHA = 0.3f
    }
}

/**
 * 地图编辑的三个阶段。
 *
 * 用密封类型而不是"两个布尔值"：绘制和编辑天然互斥，
 * 让非法组合在类型层面无法表达。
 */
sealed interface MapEditMode {

    /** 既不在绘制也不在编辑。 */
    data object Idle : MapEditMode

    /** 正在绘制新围栏。 */
    data class Drawing(
        val fenceType: GeoFenceType,
        val points: List<GeoFencePoint>
    ) : MapEditMode

    /** 正在编辑已有围栏的顶点。 */
    data class Editing(
        val fence: GeoFence,
        val points: List<GeoFencePoint>
    ) : MapEditMode
}
