package com.trackmaster.app.ui.map

import com.trackmaster.app.core.GeoFenceType
import com.trackmaster.app.data.GeoFence
import com.trackmaster.app.data.GeoFencePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 地图绘制/编辑状态机。
 *
 * 这个状态机替代了原来散在 `MapScreen` 里的 8 个布尔/列表状态。
 * 测试的重点不是"UI 长什么样"，而是几条硬性规则：
 *
 * 1. 绘制和编辑不能同时成立（原代码靠 4 处双重判断维持，容易漏）
 * 2. 空闲状态下地图点击不能产生任何副作用
 * 3. 撤销不能把点集撤成负数
 * 4. 颜色/透明度跨会话保留，点集不保留
 */
class MapEditorStateTest {

    // ------------------------------------------------------------
    // 互斥性
    // ------------------------------------------------------------

    @Test
    fun `初始处于空闲状态`() {
        val s = MapEditorState()
        assertTrue(s.mode is MapEditMode.Idle)
        assertFalse(s.isDrawing)
        assertFalse(s.isEditing)
        assertFalse(s.consumesMapClicks)
    }

    @Test
    fun `开始绘制后进入绘制模式`() {
        val s = MapEditorState()
        s.startDrawing()
        assertTrue(s.isDrawing)
        assertFalse("不能同时处于编辑模式", s.isEditing)
        assertEquals(GeoFenceType.CIRCLE, s.drawingType)
    }

    @Test
    fun `开始编辑后进入编辑模式 且不再是绘制模式`() {
        val s = MapEditorState()
        s.startDrawing()
        s.startEditing(fence("f1", points = listOf(GeoFencePoint(1.0, 2.0))))

        assertTrue(s.isEditing)
        assertFalse("切到编辑后绘制模式必须结束", s.isDrawing)
        assertEquals("f1", s.editingFenceOrNull?.id)
    }

    @Test
    fun `编辑模式下再开始绘制会退出编辑`() {
        val s = MapEditorState()
        s.startEditing(fence("f1"))
        s.startDrawing()

        assertTrue(s.isDrawing)
        assertFalse(s.isEditing)
        assertNull(s.editingFenceOrNull)
    }

    // ------------------------------------------------------------
    // 地图点击
    // ------------------------------------------------------------

    @Test
    fun `空闲时地图点击不产生任何点`() {
        val s = MapEditorState()
        s.onMapClick(39.9, 116.4)
        assertTrue(s.activePoints.isEmpty())
        assertTrue(s.mode is MapEditMode.Idle)
    }

    @Test
    fun `绘制模式下点击累加顶点`() {
        val s = MapEditorState()
        s.startDrawing()
        s.onMapClick(39.9, 116.4)
        s.onMapClick(39.91, 116.41)

        assertEquals(2, s.activePoints.size)
        assertEquals(39.9, s.activePoints[0].latitude, 1e-9)
    }

    @Test
    fun `编辑模式下的点击累加到原有顶点之后`() {
        val s = MapEditorState()
        s.startEditing(fence("f1", points = listOf(GeoFencePoint(1.0, 1.0))))
        s.onMapClick(2.0, 2.0)

        assertEquals(2, s.activePoints.size)
        assertEquals(1.0, s.activePoints[0].latitude, 1e-9)
        assertEquals(2.0, s.activePoints[1].latitude, 1e-9)
    }

    // ------------------------------------------------------------
    // 撤销
    // ------------------------------------------------------------

    @Test
    fun `撤销移除最后一个顶点`() {
        val s = MapEditorState()
        s.startDrawing()
        s.onMapClick(1.0, 1.0)
        s.onMapClick(2.0, 2.0)
        s.undoLastPoint()

        assertEquals(1, s.activePoints.size)
        assertEquals(1.0, s.activePoints[0].latitude, 1e-9)
    }

    @Test
    fun `空点集撤销不会出错`() {
        val s = MapEditorState()
        s.startDrawing()
        repeat(3) { s.undoLastPoint() }
        assertTrue(s.activePoints.isEmpty())
    }

    @Test
    fun `空闲时撤销是空操作`() {
        val s = MapEditorState()
        s.undoLastPoint()
        assertTrue(s.mode is MapEditMode.Idle)
    }

    // ------------------------------------------------------------
    // 取消与保留
    // ------------------------------------------------------------

    @Test
    fun `取消后回到空闲并丢弃点集`() {
        val s = MapEditorState()
        s.startDrawing()
        s.onMapClick(1.0, 1.0)
        s.cancel()

        assertTrue(s.mode is MapEditMode.Idle)
        assertTrue(s.activePoints.isEmpty())
        assertNull(s.editingFenceOrNull)
    }

    @Test
    fun `颜色和透明度跨会话保留`() {
        val s = MapEditorState()
        s.startDrawing()
        s.drawingColor = "#123456"
        s.drawingAlpha = 0.5f
        s.cancel()

        // 重新开始绘制，颜色/透明度应沿用上次的选择
        s.startDrawing()
        assertEquals("#123456", s.drawingColor)
        assertEquals(0.5f, s.drawingAlpha, 1e-6f)
        // 但点集必须清空
        assertTrue(s.activePoints.isEmpty())
    }

    @Test
    fun `每次开始绘制都从圆形开始`() {
        val s = MapEditorState()
        s.startDrawing()
        s.setDrawingType(GeoFenceType.POLYGON)
        s.cancel()
        s.startDrawing()

        assertEquals(GeoFenceType.CIRCLE, s.drawingType)
    }

    @Test
    fun `切换围栏类型保留已画的点`() {
        val s = MapEditorState()
        s.startDrawing()
        s.onMapClick(1.0, 1.0)
        s.setDrawingType(GeoFenceType.POLYGON)

        assertEquals(GeoFenceType.POLYGON, s.drawingType)
        assertEquals("画到一半改类型不应丢点", 1, s.activePoints.size)
    }

    // ------------------------------------------------------------
    // 高精度开关
    // ------------------------------------------------------------

    @Test
    fun `两个精度开关独立 但地图只看合并结果`() {
        val s = MapEditorState()
        assertFalse(s.precisionActive)

        s.drawingPrecision = true
        assertTrue(s.precisionActive)

        s.drawingPrecision = false
        s.editingPrecision = true
        assertTrue(s.precisionActive)

        // 两个开关本身互不影响
        s.editingPrecision = false
        assertFalse(s.precisionActive)
    }

    @Test
    fun `空闲时开始绘制会清掉上一次编辑的残留`() {
        val s = MapEditorState()
        s.startEditing(fence("f1", points = listOf(GeoFencePoint(1.0, 1.0))))
        assertEquals(1, s.activePoints.size)

        s.cancel()
        s.startDrawing()
        assertTrue(s.activePoints.isEmpty())
        assertNotEquals(1, s.activePoints.size)
    }

    // ------------------------------------------------------------

    private fun fence(
        id: String,
        points: List<GeoFencePoint>? = null
    ) = GeoFence(
        id = id,
        name = id,
        type = if (points == null) GeoFenceType.CIRCLE else GeoFenceType.POLYGON,
        center = if (points == null) GeoFencePoint(0.0, 0.0) else null,
        radius = if (points == null) 100f else null,
        points = points
    )
}
