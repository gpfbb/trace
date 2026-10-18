package com.trackmaster.app.utils.geometry

import com.trackmaster.app.core.GeoFenceType
import com.trackmaster.app.core.GeoPoint
import com.trackmaster.app.core.Geofence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 围栏面积计算。
 *
 * 这个值被用来决定地图顶部状态栏显示"哪一个"匹配围栏——取面积最小的那个。
 * 如果面积算法在纬度上出错，用户会看到错误围栏的名字。
 */
class FenceAreaTest {

    @Test
    fun `圆形面积等于 pi r 平方`() {
        val fence = circle(radiusM = 100.0)
        val expected = Math.PI * 100.0 * 100.0
        assertEquals(expected, calculateFenceArea(fence), 1.0)
    }

    @Test
    fun `赤道附近的正方形多边形面积接近理论值`() {
        // 边长 0.01 度。赤道处 1 度约 111.32 km，
        // 所以边长约 1113.2 m，面积约 1.239e6 m²
        val fence = square(latCenter = 0.0, halfSizeDeg = 0.005)
        val sideM = 0.01 * 111_320.0
        val expected = sideM * sideM
        val actual = calculateFenceArea(fence)
        assertTrue(
            "赤道处面积 ${"%.0f".format(actual)} 应接近 ${"%.0f".format(expected)}",
            abs(actual - expected) / expected < 0.02
        )
    }

    @Test
    fun `高纬度处同样角尺寸的多边形面积更小`() {
        // 这是之前 MapScreen 里那份实现缺的校正：
        // 它把经纬度平方直接乘 111000²，没有乘 cos(纬度)，
        // 于是南北纬 60 度处会把面积算成赤道的 2 倍。
        val equator = calculateFenceArea(square(latCenter = 0.0, halfSizeDeg = 0.005))
        val highLat = calculateFenceArea(square(latCenter = 60.0, halfSizeDeg = 0.005))

        val ratio = highLat / equator
        // cos(60°) = 0.5，允许 10% 的误差（球面近似）
        assertTrue(
            "北纬60度面积应是赤道的约一半，实际比值 ${"%.3f".format(ratio)}",
            abs(ratio - 0.5) < 0.05
        )
    }

    @Test
    fun `顶点不足三个的多边形面积为零`() {
        val fence = Geofence(
            id = "degenerate",
            name = "degenerate",
            type = GeoFenceType.POLYGON,
            polygon = listOf(GeoPoint(39.9, 116.4), GeoPoint(39.91, 116.41)),
            center = null,
            radius = null
        )
        assertEquals(0.0, calculateFenceArea(fence), 0.0001)
    }

    @Test
    fun `圆形面积与同半径的多边形面积量级可比`() {
        // 用 32 边形近似一个圆，两者面积应接近（多边形内接，略小）
        val circleFence = circle(radiusM = 500.0)
        val circleArea = calculateFenceArea(circleFence)
        val polyArea = calculateFenceArea(
            Geofence(
                id = "approx",
                name = "approx",
                type = GeoFenceType.POLYGON,
                polygon = CirclePolygonCache.circleToPolygon(GeoPoint(0.0, 0.0), 500.0, 64),
                center = null,
                radius = null
            )
        )

        // 64 边形内接圆，面积比约 cos²(π/64) ≈ 0.995
        assertTrue(
            "多边形近似面积 ${"%.0f".format(polyArea)} 应略小于圆 ${"%.0f".format(circleArea)}",
            polyArea < circleArea && polyArea > circleArea * 0.98
        )
    }

    // ------------------------------------------------------------

    private fun circle(radiusM: Double) = Geofence(
        id = "c",
        name = "c",
        type = GeoFenceType.CIRCLE,
        polygon = emptyList(),
        center = GeoPoint(0.0, 0.0),
        radius = radiusM
    )

    private fun square(latCenter: Double, halfSizeDeg: Double) = Geofence(
        id = "p",
        name = "p",
        type = GeoFenceType.POLYGON,
        polygon = listOf(
            GeoPoint(latCenter - halfSizeDeg, -halfSizeDeg),
            GeoPoint(latCenter - halfSizeDeg, halfSizeDeg),
            GeoPoint(latCenter + halfSizeDeg, halfSizeDeg),
            GeoPoint(latCenter + halfSizeDeg, -halfSizeDeg)
        ),
        center = null,
        radius = null
    )
}
