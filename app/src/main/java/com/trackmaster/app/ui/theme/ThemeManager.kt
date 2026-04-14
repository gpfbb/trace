package com.trackmaster.app.ui.theme

import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color

/**
 * 主题管理器
 * 管理应用主题状态和持久化
 * 颜色属性返回 State<Color> 以便 Compose 观察和重组
 */
object ThemeManager {
    private const val PREF_NAME = "theme_prefs"
    private const val KEY_CURRENT_THEME = "current_theme"

    @Volatile
    private var currentThemeValue: AppTheme = AppTheme.DARK_NIGHT

    // 用于触发 Compose 重组的状态
    private val _themeVersion = androidx.compose.runtime.mutableIntStateOf(0)
    private val themeVersion: Int get() = _themeVersion.intValue

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val themeName = prefs.getString(KEY_CURRENT_THEME, AppTheme.DARK_NIGHT.name)
        currentThemeValue = try {
            AppTheme.valueOf(themeName ?: AppTheme.DARK_NIGHT.name)
        } catch (e: Exception) {
            AppTheme.DARK_NIGHT
        }
    }

    fun setTheme(context: Context, theme: AppTheme) {
        currentThemeValue = theme
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CURRENT_THEME, theme.name)
            .apply()
        // 增加版本号以触发 Compose 重组
        _themeVersion.intValue = themeVersion + 1
    }

    fun getCurrentTheme(): AppTheme = currentThemeValue

    // 获取当前主题的颜色 - 返回 State 以便 Compose 观察
    // 使用 derivedStateOf 确保当 themeVersion 变化时，颜色 State 也会更新
    val primaryColor: State<Color> = derivedStateOf {
        themeVersion // 依赖版本号
        getThemePrimary(currentThemeValue)
    }

    val accentColor: State<Color> = derivedStateOf {
        themeVersion
        getThemeAccent(currentThemeValue)
    }

    val backgroundColor: State<Color> = derivedStateOf {
        themeVersion
        getThemeBackground(currentThemeValue)
    }

    val surfaceColor: State<Color> = derivedStateOf {
        themeVersion
        getThemeSurface(currentThemeValue)
    }

    val textPrimaryColor: State<Color> = derivedStateOf {
        themeVersion
        getThemeTextPrimary(currentThemeValue)
    }

    val textSecondaryColor: State<Color> = derivedStateOf {
        themeVersion
        getThemeTextSecondary(currentThemeValue)
    }

    val glassPanelColor: State<Color> = derivedStateOf {
        themeVersion
        getThemeGlassPanel(currentThemeValue)
    }

    // 兼容旧代码的函数版本
    fun getPrimaryColor(): Color = getThemePrimary(currentThemeValue)
    fun getAccentColor(): Color = getThemeAccent(currentThemeValue)
    fun getBackgroundColor(): Color = getThemeBackground(currentThemeValue)
    fun getSurfaceColor(): Color = getThemeSurface(currentThemeValue)
    fun getTextPrimaryColor(): Color = getThemeTextPrimary(currentThemeValue)
    fun getSecondaryTextColor(): Color = getThemeTextSecondary(currentThemeValue)
    fun getGlassPanelColor(): Color = getThemeGlassPanel(currentThemeValue)
}

/**
 * Compose 主题状态
 */
val LocalTheme = compositionLocalOf<AppTheme> { AppTheme.DARK_NIGHT }

// 主题颜色访问函数 - 带 AppTheme 参数

fun getThemePrimary(theme: AppTheme): Color = when (theme) {
    AppTheme.DARK_NIGHT -> Color(0xFFFF7F50)    // 珊瑚橙
    AppTheme.SOFT_EYE -> Color(0xFF7FB3D5)    // 柔和蓝
    AppTheme.CLASSIC_RED -> Color(0xFFE74C3C)    // 经典红
    AppTheme.MINIMAL_GRAPHITE -> Color(0xFF607D8B)    // 石墨蓝灰
    AppTheme.AURORA_GREEN -> Color(0xFF00D9A5)    // 翡翠绿
    AppTheme.DREAM_PURPLE -> Color(0xFFBB86FC)    // 紫罗兰
    AppTheme.SUNSET_ORANGE -> Color(0xFFFF7043)    // 暖橙色
    AppTheme.GLACIER_BLUE -> Color(0xFF4FC3F7)    // 冰蓝色
    AppTheme.CYBER_TEAL -> Color(0xFF00F5D4)    // 电光青
    AppTheme.MINT_FRESH -> Color(0xFF26C485)    // 薄荷绿
    AppTheme.ROSE_GOLD -> Color(0xFFE91E63)    // 玫瑰粉
    AppTheme.SPACE_GRAY -> Color(0xFF90A4AE)    // 银灰
    AppTheme.LAVENDER -> Color(0xFF9C27B0)    // 薰衣草紫
}

fun getThemeAccent(theme: AppTheme): Color = when (theme) {
    AppTheme.DARK_NIGHT -> Color(0xFF2196F3)    // 蓝色
    AppTheme.SOFT_EYE -> Color(0xFFF5B7B1)    // 柔和珊瑚
    AppTheme.CLASSIC_RED -> Color(0xFF3498DB)    // 蓝色
    AppTheme.MINIMAL_GRAPHITE -> Color(0xFFFF7043)    // 浅橙
    AppTheme.AURORA_GREEN -> Color(0xFF00B8D4)    // 青色
    AppTheme.DREAM_PURPLE -> Color(0xFF64FFDA)    // 青色点缀
    AppTheme.SUNSET_ORANGE -> Color(0xFFFF4081)    // 粉红点缀
    AppTheme.GLACIER_BLUE -> Color(0xFF80DEEA)    // 银色点缀
    AppTheme.CYBER_TEAL -> Color(0xFFFF006E)    // 霓虹粉
    AppTheme.MINT_FRESH -> Color(0xFFFFD93D)    // 柠檬黄
    AppTheme.ROSE_GOLD -> Color(0xFFFFAB91)    // 珊瑚橙
    AppTheme.SPACE_GRAY -> Color(0xFFFFCA28)    // 金色
    AppTheme.LAVENDER -> Color(0xFFFFEB3B)    // 黄色
}

fun getThemeBackground(theme: AppTheme): Color = when (theme) {
    AppTheme.DARK_NIGHT -> Color(0xFF0D0D0D)    // 近黑
    AppTheme.SOFT_EYE -> Color(0xFF1E1E2E)    // 深紫灰
    AppTheme.CLASSIC_RED -> Color(0xFF121212)    // 纯黑
    AppTheme.MINIMAL_GRAPHITE -> Color(0xFF0F0F0F)    // 近黑
    AppTheme.AURORA_GREEN -> Color(0xFF0A1A1A)    // 深青黑
    AppTheme.DREAM_PURPLE -> Color(0xFF12101E)    // 深紫黑
    AppTheme.SUNSET_ORANGE -> Color(0xFF1A1010)    // 深棕黑
    AppTheme.GLACIER_BLUE -> Color(0xFF0D1B2A)    // 深海蓝黑
    AppTheme.CYBER_TEAL -> Color(0xFF050A0F)    // 深空黑
    AppTheme.MINT_FRESH -> Color(0xFF0A1F14)    // 深绿黑
    AppTheme.ROSE_GOLD -> Color(0xFF1A0A10)    // 深酒红黑
    AppTheme.SPACE_GRAY -> Color(0xFF0D0D0D)    // 纯黑
    AppTheme.LAVENDER -> Color(0xFF12081A)    // 深紫黑
}

fun getThemeSurface(theme: AppTheme): Color = when (theme) {
    AppTheme.DARK_NIGHT -> Color(0xFF1A120B)    // 深棕
    AppTheme.SOFT_EYE -> Color(0xFF2D2D44)    // 深紫灰卡片
    AppTheme.CLASSIC_RED -> Color(0xFF1E1E1E)    // 深灰卡片
    AppTheme.MINIMAL_GRAPHITE -> Color(0xFF1A1A1A)    // 深灰卡片
    AppTheme.AURORA_GREEN -> Color(0xFF0D2820)    // 深绿卡片
    AppTheme.DREAM_PURPLE -> Color(0xFF1E1A2E)    // 深紫卡片
    AppTheme.SUNSET_ORANGE -> Color(0xFF2D1F1F)    // 深棕卡片
    AppTheme.GLACIER_BLUE -> Color(0xFF1B2838)    // 深蓝灰卡片
    AppTheme.CYBER_TEAL -> Color(0xFF0A1628)    // 深蓝黑卡片
    AppTheme.MINT_FRESH -> Color(0xFF0D2B1C)    // 深绿卡片
    AppTheme.ROSE_GOLD -> Color(0xFF2D0F1A)    // 深玫瑰卡片
    AppTheme.SPACE_GRAY -> Color(0xFF1A1A1A)    // 深灰卡片
    AppTheme.LAVENDER -> Color(0xFF1E0F2E)    // 深紫卡片
}

fun getThemeTextPrimary(theme: AppTheme): Color = when (theme) {
    AppTheme.DARK_NIGHT -> Color(0xFFF5F5F5)    // 高对比度白
    AppTheme.SOFT_EYE -> Color(0xFFECF0F1)    // 柔和白
    AppTheme.CLASSIC_RED -> Color(0xFFFFFFFF)    // 纯白
    AppTheme.MINIMAL_GRAPHITE -> Color(0xFFF5F5F5)    // 近白
    AppTheme.AURORA_GREEN -> Color(0xFFE0F2F1)    // 浅青白
    AppTheme.DREAM_PURPLE -> Color(0xFFEDE7F6)    // 浅紫白
    AppTheme.SUNSET_ORANGE -> Color(0xFFFFF3E0)    // 暖白
    AppTheme.GLACIER_BLUE -> Color(0xFFE3F2FD)    // 冰白
    AppTheme.CYBER_TEAL -> Color(0xFFE0FFFF)    // 青白
    AppTheme.MINT_FRESH -> Color(0xFFE8F5E9)    // 浅绿白
    AppTheme.ROSE_GOLD -> Color(0xFFFCE4EC)    // 粉白
    AppTheme.SPACE_GRAY -> Color(0xFFECEFF1)    // 银白
    AppTheme.LAVENDER -> Color(0xFFF3E5F5)    // 紫白
}

fun getThemeTextSecondary(theme: AppTheme): Color = when (theme) {
    AppTheme.DARK_NIGHT -> Color(0xFFE0E0E0)
    AppTheme.SOFT_EYE -> Color(0xFFBDC3C7)
    AppTheme.CLASSIC_RED -> Color(0xFFE0E0E0)
    AppTheme.MINIMAL_GRAPHITE -> Color(0xFFBDBDBD)
    AppTheme.AURORA_GREEN -> Color(0xFFB2DFDB)    // 青灰
    AppTheme.DREAM_PURPLE -> Color(0xFFD1C4E9)    // 浅紫
    AppTheme.SUNSET_ORANGE -> Color(0xFFFFCCBC)    // 浅橙
    AppTheme.GLACIER_BLUE -> Color(0xFFB3E5FC)    // 浅冰蓝
    AppTheme.CYBER_TEAL -> Color(0xFF7FDBFF)    // 浅青
    AppTheme.MINT_FRESH -> Color(0xFFA5D6A7)    // 浅绿灰
    AppTheme.ROSE_GOLD -> Color(0xFFF8BBD0)    // 浅粉
    AppTheme.SPACE_GRAY -> Color(0xFFCFD8DC)    // 浅银灰
    AppTheme.LAVENDER -> Color(0xFFE1BEE7)    // 浅紫
}

fun getThemeGlassPanel(theme: AppTheme): Color = when (theme) {
    AppTheme.DARK_NIGHT -> Color(0xB31C1612)    // 玻璃面板
    AppTheme.SOFT_EYE -> Color(0x992D2D44)    // 玻璃面板
    AppTheme.CLASSIC_RED -> Color(0xCC1E1E1E)    // 玻璃面板
    AppTheme.MINIMAL_GRAPHITE -> Color(0x991A1A1A)    // 玻璃面板
    AppTheme.AURORA_GREEN -> Color(0xB30D2820)    // 深绿玻璃
    AppTheme.DREAM_PURPLE -> Color(0xB31E1A2E)    // 深紫玻璃
    AppTheme.SUNSET_ORANGE -> Color(0xB32D1F1F)    // 深棕玻璃
    AppTheme.GLACIER_BLUE -> Color(0xB31B2838)    // 深蓝玻璃
    AppTheme.CYBER_TEAL -> Color(0xB30A1628)    // 深蓝黑玻璃
    AppTheme.MINT_FRESH -> Color(0xB30D2B1C)    // 深绿玻璃
    AppTheme.ROSE_GOLD -> Color(0xB32D0F1A)    // 深玫瑰玻璃
    AppTheme.SPACE_GRAY -> Color(0xB31A1A1A)    // 深灰玻璃
    AppTheme.LAVENDER -> Color(0xB31E0F2E)    // 深紫玻璃
}
