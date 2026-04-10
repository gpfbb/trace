package com.trackmaster.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * TrackMaster 配色方案
 * 提供多种主题选择
 */

/** 方案1: 深邃暗夜（默认）- 珊瑚橙与深棕，沉浸感强 */
object DarkNightScheme {
    val primary = NeonCoral        // 珊瑚橙主色
    val primaryVariant = NeonAmber  // 琥珀色变体
    val accent = NeonBlue          // 蓝色点缀

    val background = EspressoBlack // 近黑背景
    val surface = DeepChocolate    // 深棕卡片

    val glassPanel = Color(0xB31C1612)      // 玻璃面板
    val glassBorder = Color(0x1AFFFFFF)      // 玻璃边框

    val textPrimary = Color(0xFFF5F5F5)      // 主文字
    val textSecondary = Color(0xFFE0E0E0)    // 次要文字
    val textTertiary = Color(0xFFBDBDBD)     // 辅助文字
}

/** 方案2: 柔和护眼 - 柔和蓝紫，长时间使用舒适 */
object SoftEyeScheme {
    val primary = Color(0xFF7FB3D5)    // 柔和蓝
    val primaryVariant = Color(0xFF85C1E9)
    val accent = Color(0xFFF5B7B1)      // 柔和珊瑚

    val background = Color(0xFF1E1E2E)   // 深紫灰
    val surface = Color(0xFF2D2D44)     // 卡片

    val glassPanel = Color(0x992D2D44)
    val glassBorder = Color(0x1AFFFFFF)

    val textPrimary = Color(0xFFECF0F1)
    val textSecondary = Color(0xFFBDC3C7)
    val textTertiary = Color(0xFF95A5A6)
}

/** 方案3: 经典红黑 - 经典红与金，稳重大气 */
object ClassicRedScheme {
    val primary = Color(0xFFE74C3C)    // 经典红
    val primaryVariant = Color(0xFFF39C12) // 金色
    val accent = Color(0xFF3498DB)       // 蓝色

    val background = Color(0xFF121212)   // 纯黑
    val surface = Color(0xFF1E1E1E)     // 深灰

    val glassPanel = Color(0xCC1E1E1E)
    val glassBorder = Color(0x26FFFFFF)

    val textPrimary = Color(0xFFFFFFFF)
    val textSecondary = Color(0xFFE0E0E0)
    val textTertiary = Color(0xFFB0B0B0)
}

/** 方案4: 极简石墨 - 黑白灰为主，极简美学 */
object MinimalGraphiteScheme {
    val primary = Color(0xFF607D8B)    // 石墨蓝灰
    val primaryVariant = Color(0xFF78909C)
    val accent = Color(0xFFFF7043)       // 浅橙点缀

    val background = Color(0xFF0F0F0F)   // 近黑
    val surface = Color(0xFF1A1A1A)     // 深灰

    val glassPanel = Color(0x991A1A1A)
    val glassBorder = Color(0x15FFFFFF)

    val textPrimary = Color(0xFFF5F5F5)
    val textSecondary = Color(0xFFBDBDBD)
    val textTertiary = Color(0xFF9E9E9E)
}

/** 方案5: 极光绿 - 翡翠绿与青色，自然清新 */
object AuroraGreenScheme {
    val primary = Color(0xFF00D9A5)    // 翡翠绿
    val primaryVariant = Color(0xFF00E676) // 明亮绿
    val accent = Color(0xFF00B8D4)       // 青色点缀

    val background = Color(0xFF0A1A1A)   // 深青黑
    val surface = Color(0xFF0D2820)     // 深绿卡片

    val glassPanel = Color(0xB30D2820)
    val glassBorder = Color(0x1AFFFFFF)

    val textPrimary = Color(0xFFE0F2F1)   // 浅青白
    val textSecondary = Color(0xFFB2DFDB) // 青灰
    val textTertiary = Color(0xFF80CBC4)  // 青绿
}

/** 方案6: 梦幻紫 - 紫罗兰与品红，魔法感 */
object DreamPurpleScheme {
    val primary = Color(0xFFBB86FC)    // 紫罗兰
    val primaryVariant = Color(0xFFE040FB) // 品红
    val accent = Color(0xFF64FFDA)       // 青色点缀

    val background = Color(0xFF12101E)   // 深紫黑
    val surface = Color(0xFF1E1A2E)     // 深紫卡片

    val glassPanel = Color(0xB31E1A2E)
    val glassBorder = Color(0x26E040FB)

    val textPrimary = Color(0xFFEDE7F6)   // 浅紫白
    val textSecondary = Color(0xFFD1C4E9) // 浅紫
    val textTertiary = Color(0xFFB39DDB)  // 紫灰
}

/** 方案7: 落日橙 - 暖橙与粉红，温馨浪漫 */
object SunsetOrangeScheme {
    val primary = Color(0xFFFF7043)    // 暖橙色
    val primaryVariant = Color(0xFFFF8A65) // 浅橙
    val accent = Color(0xFFFF4081)       // 粉红点缀

    val background = Color(0xFF1A1010)   // 深棕黑
    val surface = Color(0xFF2D1F1F)     // 深棕卡片

    val glassPanel = Color(0xB32D1F1F)
    val glassBorder = Color(0x26FF7043)

    val textPrimary = Color(0xFFFFF3E0)   // 暖白
    val textSecondary = Color(0xFFFFCCBC) // 浅橙
    val textTertiary = Color(0xFFFFAB91)  // 橙灰
}

/** 方案8: 冰川蓝 - 冰蓝与银白，清冷优雅 */
object GlacierBlueScheme {
    val primary = Color(0xFF4FC3F7)    // 冰蓝色
    val primaryVariant = Color(0xFF81D4FA) // 浅冰蓝
    val accent = Color(0xFF80DEEA)       // 银色点缀

    val background = Color(0xFF0D1B2A)   // 深海蓝黑
    val surface = Color(0xFF1B2838)     // 深蓝灰卡片

    val glassPanel = Color(0xB31B2838)
    val glassBorder = Color(0x264FC3F7)

    val textPrimary = Color(0xFFE3F2FD)   // 冰白
    val textSecondary = Color(0xFFB3E5FC) // 浅冰蓝
    val textTertiary = Color(0xFF81D4FA)  // 冰蓝灰
}

/** 主题枚举 */
enum class AppTheme(
    val displayName: String,
    val description: String
) {
    DARK_NIGHT("深邃暗夜", "珊瑚橙与深棕，沉浸深邃"),
    SOFT_EYE("柔和护眼", "蓝紫柔和，长时间舒适"),
    CLASSIC_RED("经典红黑", "红金配色，稳重大气"),
    MINIMAL_GRAPHITE("极简石墨", "黑白灰极简美学"),
    AURORA_GREEN("极光绿", "翡翠翠绿，自然清新"),
    DREAM_PURPLE("梦幻紫", "紫罗兰色，魔法浪漫"),
    SUNSET_ORANGE("落日橙", "暖橙粉色，温馨浪漫"),
    GLACIER_BLUE("冰川蓝", "冰蓝银色，清冷优雅")
}

/** 获取主题配色 */
fun getThemeColors(theme: AppTheme) = when (theme) {
    AppTheme.DARK_NIGHT -> DarkNightScheme
    AppTheme.SOFT_EYE -> SoftEyeScheme
    AppTheme.CLASSIC_RED -> ClassicRedScheme
    AppTheme.MINIMAL_GRAPHITE -> MinimalGraphiteScheme
    AppTheme.AURORA_GREEN -> AuroraGreenScheme
    AppTheme.DREAM_PURPLE -> DreamPurpleScheme
    AppTheme.SUNSET_ORANGE -> SunsetOrangeScheme
    AppTheme.GLACIER_BLUE -> GlacierBlueScheme
}
