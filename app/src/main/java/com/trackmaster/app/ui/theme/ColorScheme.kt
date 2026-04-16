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

/** 方案9: 赛博青 - 赛博朋克风格，青绿光效 */
object CyberTealScheme {
    val primary = Color(0xFF00F5D4)    // 电光青
    val primaryVariant = Color(0xFF00D9FF) // 亮青
    val accent = Color(0xFFFF006E)       // 霓虹粉点缀

    val background = Color(0xFF050A0F)   // 深空黑
    val surface = Color(0xFF0A1628)     // 深蓝黑卡片

    val glassPanel = Color(0xB30A1628)
    val glassBorder = Color(0x3300F5D4)

    val textPrimary = Color(0xFFE0FFFF)   // 青白
    val textSecondary = Color(0xFF7FDBFF) // 浅青
    val textTertiary = Color(0xFF00CED1)  // 青灰
}

/** 方案10: 薄荷绿 - 小清新薄荷，清爽自然 */
object MintFreshScheme {
    val primary = Color(0xFF26C485)    // 薄荷绿
    val primaryVariant = Color(0xFF4AE29A) // 亮薄荷
    val accent = Color(0xFFFFD93D)       // 柠檬黄点缀

    val background = Color(0xFF0A1F14)   // 深绿黑
    val surface = Color(0xFF0D2B1C)     // 深绿卡片

    val glassPanel = Color(0xB30D2B1C)
    val glassBorder = Color(0x3326C485)

    val textPrimary = Color(0xFFE8F5E9)   // 浅绿白
    val textSecondary = Color(0xFFA5D6A7) // 浅绿灰
    val textTertiary = Color(0xFF81C784)  // 绿灰
}

/** 方案11: 玫瑰金 - 玫瑰金色系，优雅温柔 */
object RoseGoldScheme {
    val primary = Color(0xFFE91E63)    // 玫瑰粉
    val primaryVariant = Color(0xFFF48FB1) // 浅玫瑰
    val accent = Color(0xFFFFAB91)       // 珊瑚橙点缀

    val background = Color(0xFF1A0A10)   // 深酒红黑
    val surface = Color(0xFF2D0F1A)     // 深玫瑰卡片

    val glassPanel = Color(0xB32D0F1A)
    val glassBorder = Color(0x33E91E63)

    val textPrimary = Color(0xFFFCE4EC)   // 粉白
    val textSecondary = Color(0xFFF8BBD0) // 浅粉
    val textTertiary = Color(0xFFF48FB1)  // 粉灰
}

/** 方案12: 深空灰 - 深空灰色系，科技质感 */
object SpaceGrayScheme {
    val primary = Color(0xFF90A4AE)    // 银灰
    val primaryVariant = Color(0xFFB0BEC5) // 浅银灰
    val accent = Color(0xFFFFCA28)       // 金色点缀

    val background = Color(0xFF0D0D0D)   // 纯黑
    val surface = Color(0xFF1A1A1A)     // 深灰卡片

    val glassPanel = Color(0xB31A1A1A)
    val glassBorder = Color(0x1AFFFFFF)

    val textPrimary = Color(0xFFECEFF1)   // 银白
    val textSecondary = Color(0xFFCFD8DC) // 浅银灰
    val textTertiary = Color(0xFFB0BEC5)  // 银灰
}

/** 方案13: 薰衣草紫 - 薰衣草紫色，文艺浪漫 */
object LavenderScheme {
    val primary = Color(0xFF9C27B0)    // 薰衣草紫
    val primaryVariant = Color(0xFFBA68C8) // 浅紫
    val accent = Color(0xFFFFEB3B)       // 黄色点缀

    val background = Color(0xFF12081A)   // 深紫黑
    val surface = Color(0xFF1E0F2E)     // 深紫卡片

    val glassPanel = Color(0xB31E0F2E)
    val glassBorder = Color(0x339C27B0)

    val textPrimary = Color(0xFFF3E5F5)   // 紫白
    val textSecondary = Color(0xFFE1BEE7) // 浅紫
    val textTertiary = Color(0xFFCE93D8)  // 紫灰
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
    GLACIER_BLUE("冰川蓝", "冰蓝银色，清冷优雅"),
    CYBER_TEAL("赛博青", "电光青绿，赛博朋克"),
    MINT_FRESH("薄荷绿", "清爽薄荷，清新自然"),
    ROSE_GOLD("玫瑰金", "玫瑰粉金，优雅温柔"),
    SPACE_GRAY("深空灰", "银灰科技，简约质感"),
    LAVENDER("薰衣草紫", "薰衣草紫，文艺浪漫")
}

/** 获取主题配色 */
@Suppress("IMPLICIT_CAST_TO_ANY")
fun getThemeColors(theme: AppTheme) = when (theme) {
    AppTheme.DARK_NIGHT -> DarkNightScheme
    AppTheme.SOFT_EYE -> SoftEyeScheme
    AppTheme.CLASSIC_RED -> ClassicRedScheme
    AppTheme.MINIMAL_GRAPHITE -> MinimalGraphiteScheme
    AppTheme.AURORA_GREEN -> AuroraGreenScheme
    AppTheme.DREAM_PURPLE -> DreamPurpleScheme
    AppTheme.SUNSET_ORANGE -> SunsetOrangeScheme
    AppTheme.GLACIER_BLUE -> GlacierBlueScheme
    AppTheme.CYBER_TEAL -> CyberTealScheme
    AppTheme.MINT_FRESH -> MintFreshScheme
    AppTheme.ROSE_GOLD -> RoseGoldScheme
    AppTheme.SPACE_GRAY -> SpaceGrayScheme
    AppTheme.LAVENDER -> LavenderScheme
}
