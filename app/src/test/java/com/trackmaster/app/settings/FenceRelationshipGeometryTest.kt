package com.trackmaster.app.settings

import com.trackmaster.app.core.GeoFenceType
import com.trackmaster.app.core.GeoPoint
import com.trackmaster.app.core.Geofence
import com.trackmaster.app.utils.geometry.detectFenceIntersections
import com.trackmaster.app.utils.geometry.isFenceContainedByFence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 父子关系计算依赖的两个几何判断。
 *
 * 这些用例覆盖 `FenceRelationshipRecalculator` 的核心决策：
 * 什么时候建立父子关系、什么时候必须拒绝执行。
 *
 * 算法本身的组装（转模型、清空、写回）需要 Application 实例，属于
 * 集成测试范畴；这里守住的是它依赖的几何语义，一旦这几个函数的行为
 * 变了，重算结果就会静默出错。
 */
class FenceRelationshipGeometryTest {

    // ------------------------------------------------------------
    // 包含关系
    // ------------------------------------------------------------

    @Test
    fun `大圆包含小圆`() {
        val big = circle("big", lat = 39.9, lng = 116.4, radiusM = 1000.0)
        val small = circle("small", lat = 39.9, lng = 116.4, radiusM = 100.0)
        assertTrue("小圆应被大圆包含", isFenceContainedByFence(small, big))
        assertFalse("大圆不应被小圆包含", isFenceContainedByFence(big, small))
    }

    @Test
    fun `同一位置的同尺寸圆不算互相包含`() {
        // 两个完全重合的围栏互相"包含"。如果按包含处理，父子关系会
        // 形成环，叶子优先的计时逻辑会出现歧义。
        val a = circle("a", lat = 39.9, lng = 116.4, radiusM = 100.0)
        val b = circle("b", lat = 39.9, lng = 116.4, radiusM = 100.0)
        // 这个用例记录当前行为：两个方向都会判为包含。
        // 调用方（Recalculator）正是靠 `bInsideA && !aInsideB` 把这种情况排除掉。
        assertTrue(isFenceContainedByFence(a, b))
        assertTrue(isFenceContainedByFence(b, a))
    }

    @Test
    fun `分离的圆互不包含`() {
        val a = circle("a", lat = 39.9, lng = 116.4, radiusM = 100.0)
        val b = circle("b", lat = 40.5, lng = 117.2, radiusM = 100.0)
        assertFalse(isFenceContainedByFence(a, b))
        assertFalse(isFenceContainedByFence(b, a))
    }

    // ------------------------------------------------------------
    // 相交检测
    // ------------------------------------------------------------

    @Test
    fun `部分重叠的两个圆会被判为相交`() {
        // 圆心相距约 555m，半径各 500m → 重叠但互不包含
        val a = circle("a", lat = 39.900, lng = 116.400, radiusM = 500.0)
        val b = circle("b", lat = 39.905, lng = 116.400, radiusM = 500.0)
        assertTrue("应判为相交", fencesIntersectForTest(a, b))
    }

    @Test
    fun `完全包含不算相交`() {
        val big = circle("big", lat = 39.9, lng = 116.4, radiusM = 2000.0)
        val small = circle("small", lat = 39.9, lng = 116.4, radiusM = 100.0)
        assertFalse(
            "包含关系不是相交，否则父子关系永远算不出来",
            fencesIntersectForTest(big, small)
        )
    }

    @Test
    fun `分离的圆不算相交`() {
        val a = circle("a", lat = 39.9, lng = 116.4, radiusM = 100.0)
        val b = circle("b", lat = 40.5, lng = 117.2, radiusM = 100.0)
        assertFalse(fencesIntersectForTest(a, b))
    }

    // ------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------

    private fun fencesIntersectForTest(a: Geofence, b: Geofence): Boolean =
        detectFenceIntersections(listOf(a, b)).isNotEmpty()

    private fun circle(id: String, lat: Double, lng: Double, radiusM: Double) = Geofence(
        id = id,
        name = id,
        type = GeoFenceType.CIRCLE,
        polygon = emptyList(),
        center = GeoPoint(lat, lng),
        radius = radiusM
    )
}
