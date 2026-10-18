package com.trackmaster.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 地图服务商地区判断。
 *
 * 这不是"测试 UI"，而是守住一条会导致**地图完全不可用**的规则：
 * 高德国内版 Key 在境外返回 INSUFFICIENT_ABROAD_PRIVILEGES，
 * Google Maps 在国内不可用。选错任何一个，用户看到的是灰格子。
 */
class MapRegionDetectorTest {

    @Test
    fun `中国大陆应推荐高德`() {
        assertEquals(MapProvider.AMAP, recommendedFor("CN"))
    }

    @Test
    fun `港澳台应推荐高德`() {
        // 高德的国内版 Key 覆盖这三个地区
        listOf("HK", "MO", "TW").forEach { code ->
            assertEquals("$code 应推荐高德", MapProvider.AMAP, recommendedFor(code))
        }
    }

    @Test
    fun `其他国家应推荐 Google Maps`() {
        listOf("US", "GB", "JP", "DE", "SG", "MY", "AU").forEach { code ->
            assertEquals("$code 应推荐 Google Maps", MapProvider.GOOGLE, recommendedFor(code))
        }
    }

    @Test
    fun `空白或取不到国家时退回 Google Maps`() {
        // 取不到国家说明设备信息异常。Google Maps 的覆盖面更广，
        // 选它是更安全的默认值——高德在境外会直接失败。
        assertEquals(MapProvider.GOOGLE, recommendedFor(""))
    }

    @Test
    fun `国家代码大小写不影响判断`() {
        assertEquals(
            MapRegionDetector.recommendedProviderForCountry("cn"),
            MapRegionDetector.recommendedProviderForCountry("CN")
        )
    }

    // ------------------------------------------------------------

    private fun recommendedFor(country: String): MapProvider =
        MapRegionDetector.recommendedProviderForCountry(country)
}
