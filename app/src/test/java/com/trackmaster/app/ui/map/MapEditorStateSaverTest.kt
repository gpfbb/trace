package com.trackmaster.app.ui.map

import androidx.compose.runtime.saveable.SaverScope
import com.trackmaster.app.core.GeoFenceType
import com.trackmaster.app.data.GeoFencePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 编辑状态的保存/恢复。
 *
 * 场景：用户在地图上画多边形，切到别的 app，系统把进程杀了；
 * 回来时画到一半的围栏必须还在。
 */
class MapEditorStateSaverTest {

    /** 模拟 Compose 的 SaverScope：这里只需要 canBeSaved 恒真。 */
    private val scope = SaverScope { true }

    @Test
    fun `空闲状态可以往返`() {
        val original = MapEditorState()
        val restored = roundTrip(original)
        assertTrue(restored.mode is MapEditMode.Idle)
    }

    @Test
    fun `绘制中的点集与类型可以恢复`() {
        val original = MapEditorState()
        original.startDrawing()
        original.setDrawingType(GeoFenceType.POLYGON)
        original.onMapClick(39.9, 116.4)
        original.onMapClick(39.91, 116.41)
        original.onMapClick(39.92, 116.42)

        val restored = roundTrip(original)

        assertTrue(restored.isDrawing)
        assertEquals(GeoFenceType.POLYGON, restored.drawingType)
        assertEquals(3, restored.activePoints.size)
        assertEquals(39.9, restored.activePoints[0].latitude, 1e-9)
        assertEquals(116.42, restored.activePoints[2].longitude, 1e-9)
    }

    @Test
    fun `颜色与精度开关一并保留`() {
        val original = MapEditorState()
        original.startDrawing()
        original.drawingColor = "#123456"
        original.drawingAlpha = 0.55f
        original.drawingPrecision = true
        original.editingPrecision = false

        val restored = roundTrip(original)

        assertEquals("#123456", restored.drawingColor)
        assertEquals(0.55f, restored.drawingAlpha, 1e-6f)
        assertTrue(restored.drawingPrecision)
        assertTrue(!restored.editingPrecision)
    }

    @Test
    fun `空点集也能往返`() {
        val original = MapEditorState()
        original.startDrawing()

        val restored = roundTrip(original)

        assertTrue(restored.isDrawing)
        assertTrue(restored.activePoints.isEmpty())
    }

    @Test
    fun `恢复后仍可继续画点`() {
        val original = MapEditorState()
        original.startDrawing()
        original.onMapClick(1.0, 1.0)

        val restored = roundTrip(original)
        restored.onMapClick(2.0, 2.0)

        assertEquals(2, restored.activePoints.size)
    }

    // ------------------------------------------------------------

    private fun roundTrip(state: MapEditorState): MapEditorState {
        val saved = with(MapEditorState.Saver) { scope.save(state) }
        requireNotNull(saved) { "Saver 返回了 null，状态没能保存" }
        return requireNotNull(MapEditorState.Saver.restore(saved)) {
            "Saver 恢复失败"
        }
    }
}
