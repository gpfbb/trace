package com.trackmaster.app.ui.theme

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color

// ============================================
// 主题感知颜色 - 这些颜色会根据当前主题动态变化
// ============================================

/**
 * 主色调 - 根据当前主题返回对应颜色
 * DARK_NIGHT: 珊瑚橙, SOFT_EYE: 柔和蓝, CLASSIC_RED: 经典红, MINIMAL_GRAPHITE: 石墨蓝灰
 */
@Composable
fun primaryColor(): Color = ThemeManager.primaryColor.value

/**
 * 强调色 - 根据当前主题返回对应颜色
 */
@Composable
fun accentColor(): Color = ThemeManager.accentColor.value

/**
 * 背景色 - 根据当前主题返回对应颜色
 */
@Composable
fun backgroundColor(): Color = ThemeManager.backgroundColor.value

/**
 * 表面色 - 根据当前主题返回对应颜色（卡片、面板等）
 */
@Composable
fun surfaceColor(): Color = ThemeManager.surfaceColor.value

/**
 * 主文字色 - 根据当前主题返回对应颜色
 */
@Composable
fun textPrimary(): Color = ThemeManager.textPrimaryColor.value

/**
 * 次文字色 - 根据当前主题返回对应颜色
 */
@Composable
fun textSecondary(): Color = ThemeManager.textSecondaryColor.value

/**
 * 玻璃面板色 - 根据当前主题返回对应颜色
 */
@Composable
fun glassPanel(): Color = ThemeManager.glassPanelColor.value

// ============================================
// 旧版静态颜色别名 - 为了向后兼容
// 这些直接使用 DARK_NIGHT 主题的颜色
// ============================================

// 主色调 - 霓虹珊瑚橙
val NeonCoral = Color(0xFFFF7F50)
val NeonAmber = Color(0xFFFFBF00)
val NeonBlue = Color(0xFF2196F3)

// 背景色 - 深巧克力棕
val DeepChocolate = Color(0xFF1A120B)
val EspressoBlack = Color(0xFF0D0D0D)

// 玻璃面板色 - 更高级的液态玻璃质感
val GlassPanelRich = Color(0xB31C1612) // rgba(28, 22, 18, 0.7)
val GlassPanelLightRich = Color(0x992A221C) // rgba(42, 34, 28, 0.6)

// 边框色
val GlassBorder = Color(0x1AFFFFFF)
val GlassBorderLight = Color(0x25FFFFFF)
val GlassInnerGlow = Color(0x26FFFFFF) // 内发光边缘

// 高级玻璃颜色（用于响应式文字）
val TextPrimary = Color(0xFFF5F5F5) // 高对比度主文字 (对比度 ~12:1)
val TextSecondary = Color(0xFFE0E0E0) // 次要文字 (对比度 ~10:1)
val TextTertiary = Color(0xFFBDBDBD) // 辅助文字 (对比度 ~8:1)
val TextDisabled = Color(0xFF757575) // 禁用文字 (对比度 ~5:1)

// 状态色
val Success = Color(0xFF4CAF50)
val Warning = NeonAmber
val Danger = Color(0xFFFF5252)

// 标准Material 3颜色映射（基于新复古液态玻璃拟态 + WCAG 2.1 AA对比度）
val md_theme_light_primary = NeonCoral
val md_theme_light_onPrimary = Color(0xFFFFFFFF)
val md_theme_light_primaryContainer = Color(0x30FF7F50)
val md_theme_light_onPrimaryContainer = Color(0xFFFFD4C7)
val md_theme_light_secondary = NeonAmber
val md_theme_light_onSecondary = Color(0xFF000000)
val md_theme_light_secondaryContainer = Color(0x30FFBF00)
val md_theme_light_onSecondaryContainer = Color(0xFFFFF3CD)
val md_theme_light_tertiary = Color(0xFF00BCD4)
val md_theme_light_onTertiary = Color(0xFFFFFFFF)
val md_theme_light_tertiaryContainer = Color(0x3000BCD4)
val md_theme_light_onTertiaryContainer = Color(0xFFB2EBF2)
val md_theme_light_error = Color(0xFFFF5252)
val md_theme_light_onError = Color(0xFFFFFFFF)
val md_theme_light_errorContainer = Color(0x30FF5252)
val md_theme_light_onErrorContainer = Color(0xFFFFCDD2)
val md_theme_light_outline = GlassBorder
val md_theme_light_background = DeepChocolate
val md_theme_light_onBackground = TextPrimary
val md_theme_light_surface = GlassPanelRich
val md_theme_light_onSurface = TextPrimary
val md_theme_light_surfaceVariant = GlassPanelLightRich
val md_theme_light_onSurfaceVariant = TextSecondary
val md_theme_light_inverseSurface = TextPrimary
val md_theme_light_inverseOnSurface = DeepChocolate
val md_theme_light_inversePrimary = NeonCoral
val md_theme_light_shadow = Color(0x80000000)
val md_theme_light_surfaceTint = NeonCoral
val md_theme_light_outlineVariant = GlassBorderLight
val md_theme_light_scrim = Color(0x80000000)

// 暗色主题与亮色主题相同（因为我们采用深色背景设计）
val md_theme_dark_primary = NeonCoral
val md_theme_dark_onPrimary = Color(0xFFFFFFFF)
val md_theme_dark_primaryContainer = Color(0x30FF7F50)
val md_theme_dark_onPrimaryContainer = Color(0xFFFFD4C7)
val md_theme_dark_secondary = NeonAmber
val md_theme_dark_onSecondary = Color(0xFF000000)
val md_theme_dark_secondaryContainer = Color(0x30FFBF00)
val md_theme_dark_onSecondaryContainer = Color(0xFFFFF3CD)
val md_theme_dark_tertiary = Color(0xFF00BCD4)
val md_theme_dark_onTertiary = Color(0xFFFFFFFF)
val md_theme_dark_tertiaryContainer = Color(0x3000BCD4)
val md_theme_dark_onTertiaryContainer = Color(0xFFB2EBF2)
val md_theme_dark_error = Color(0xFFFF5252)
val md_theme_dark_onError = Color(0xFFFFFFFF)
val md_theme_dark_errorContainer = Color(0x30FF5252)
val md_theme_dark_onErrorContainer = Color(0xFFFFCDD2)
val md_theme_dark_outline = GlassBorder
val md_theme_dark_background = EspressoBlack
val md_theme_dark_onBackground = TextPrimary
val md_theme_dark_surface = GlassPanelRich
val md_theme_dark_onSurface = TextPrimary
val md_theme_dark_surfaceVariant = GlassPanelLightRich
val md_theme_dark_onSurfaceVariant = TextSecondary
val md_theme_dark_inverseSurface = TextPrimary
val md_theme_dark_inverseOnSurface = EspressoBlack
val md_theme_dark_inversePrimary = NeonCoral
val md_theme_dark_shadow = Color(0x80000000)
val md_theme_dark_surfaceTint = NeonCoral
val md_theme_dark_outlineVariant = GlassBorderLight
val md_theme_dark_scrim = Color(0x80000000)
