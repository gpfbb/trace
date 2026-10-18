package com.trackmaster.app

import android.content.Context
import java.util.Locale

/**
 * 地图服务商可用性判断。
 *
 * ## 背景
 *
 * 高德地图的 Key 分国内版和海外版。国内版 Key 在境外 IP 下请求会返回
 *
 * ```
 * INSUFFICIENT_ABROAD_PRIVILEGES (错误码 7)
 * ```
 *
 * 表现是地图瓦片全部加载失败——用户看到一片灰格子，且没有任何提示。
 * Google Maps 反过来在国内网络下不可用。
 *
 * ## 判断依据
 *
 * 用**设备所在国家的 Locale**，不做网络探测：
 *
 * - Locale 是同步可读的，App 启动时就能决定用哪个 provider，
 *   不用等一次网络往返
 * - 网络探测在冷启动时可能超时，反而让首屏卡住
 * - Locale 判错的后果是"用了另一个 provider"，用户仍能在设置里手动切
 *
 * 注意这是**启发式**，不是精确判断：一个中国用户在海外出差时
 * Locale 仍是 zh-CN，会选中高德，然后地图加载失败。这种情况下
 * 用户可以在设置里手动切到 Google Maps。
 */
object MapRegionDetector {

    /** 高德能正常工作的大中华区国家代码。 */
    private val CHINA_REGION_COUNTRY_CODES = setOf(
        "CN", // 中国大陆
        "HK", // 香港
        "MO", // 澳门
        "TW"  // 台湾
    )

    /**
     * 根据 Locale 推荐的地图服务商。
     *
     * @return 在中国区返回 [MapProvider.AMAP]，其余返回 [MapProvider.GOOGLE]
     */
    fun recommendedProvider(context: Context): MapProvider =
        recommendedProviderForCountry(deviceCountry(context))

    /**
     * 按国家代码推荐服务商。抽成纯函数便于测试。
     *
     * 取不到国家（空串）时返回 Google Maps：它的地理覆盖面更广，
     * 而高德在境外会直接失败，所以未知情况下选 Google 更安全。
     */
    fun recommendedProviderForCountry(country: String): MapProvider {
        val normalized = country.trim().uppercase(Locale.US)
        return if (normalized in CHINA_REGION_COUNTRY_CODES) {
            MapProvider.AMAP
        } else {
            MapProvider.GOOGLE
        }
    }

    /** 设备当前国家代码（ISO 3166-1 alpha-2，大写）。取不到时返回空串。 */
    fun deviceCountry(context: Context): String {
        // 优先用系统 Locale，其次退回默认 Locale。
        // 注意：不要在 App 里用 LanguageManager 设置的 locale——
        // 那是用户选的应用语言（可能是中文），不代表设备在哪里。
        val locales = context.resources.configuration.locales
        for (i in 0 until locales.size()) {
            val country = locales[i]?.country.orEmpty()
            if (country.isNotEmpty()) return country.uppercase(Locale.US)
        }
        return Locale.getDefault().country.uppercase(Locale.US)
    }

    /** 该 provider 在设备所在区域是否预期可用。 */
    fun isExpectedToWork(context: Context, provider: MapProvider): Boolean {
        val recommended = recommendedProvider(context)
        return recommended == provider
    }
}
