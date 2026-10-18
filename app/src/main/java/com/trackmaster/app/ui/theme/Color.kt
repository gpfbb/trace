package com.trackmaster.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.trackmaster.app.ui.design.AppType
import com.trackmaster.app.ui.design.LocalPalette

// ============================================================
// 旧接口 → 新设计系统的适配层
//
// 屏幕代码历史上通过下面这些函数取色。现在它们全部改读
// [LocalPalette]，于是所有调用点在切换到新主题时自动生效，
// 不必逐个改写。
//
// 新代码请直接用 `currentPalette.xxx`，不要再用这里的函数。
// 等所有屏幕迁移完，这个文件可以整体删除。
// ============================================================

/** 主色 = 新系统的强调色 */
@Composable
fun primaryColor(): Color = LocalPalette.current.accent

/** 强调色（旧语义）→ 映射到语义状态色中更醒目的一档 */
@Composable
fun accentColor(): Color = LocalPalette.current.warning

/** 页面底色 */
@Composable
fun backgroundColor(): Color = LocalPalette.current.background

/** 卡片/表面色 */
@Composable
fun surfaceColor(): Color = LocalPalette.current.groupedCell

/** 主文字 */
@Composable
fun textPrimary(): Color = LocalPalette.current.label

/** 次要文字 */
@Composable
fun textSecondary(): Color = LocalPalette.current.labelSecondary

/** 旧"玻璃面板"色 → 新系统的卡片底色 */
@Composable
fun glassPanel(): Color = LocalPalette.current.groupedCell

/**
 * 旧的静态色值别名。
 *
 * 这些是 `Screen.kt` 之外的代码仍在引用的常量。它们无法随主题变化，
 * 所以只在语义明确（成功/警告/危险）且调用方本就不该随主题变的地方保留。
 * 配色来自新 token 的浅色档，保证在两套模式下都可读。
 */
val Success: Color = Color(0xFF34C759)
val Warning: Color = Color(0xFFFF9500)
val Danger: Color = Color(0xFFFF3B30)

// 旧的霓虹色别名：新设计不用霓虹色，统一映射到强调色系。
// 保留名字是为了让尚未迁移的屏幕能编译。
val NeonCoral: Color = Color(0xFFFF7F50)
val NeonAmber: Color = Color(0xFFFF9F0A)
val NeonBlue: Color = Color(0xFF0A84FF)

val DeepChocolate: Color = Color(0xFF1C1C1E)
val EspressoBlack: Color = Color(0xFF000000)

// 旧"玻璃"色值：新设计没有玻璃拟态，这些是给未迁移代码的占位。
// 任何依赖它们做出视觉决策的地方都应该在迁移时删掉。
val GlassPanelRich: Color = Color(0xF21C1C1E)
val GlassPanelLightRich: Color = Color(0x992C2C2E)
val GlassBorder: Color = Color(0xFF38383A)
val GlassBorderLight: Color = Color(0xFF48484A)
val GlassInnerGlow: Color = Color(0x1AFFFFFF)

val TextPrimary: Color = Color(0xFFFFFFFF)
val TextSecondary: Color = Color(0xFFEBEBF5)
val TextTertiary: Color = Color(0xFF9B9BA1)
val TextDisabled: Color = Color(0xFF6C6C70)

// ============================================================
// 旧 Material 色板别名
//
// `TrackMasterTheme.kt` 用这些名字构造 ColorScheme。新的
// [com.trackmaster.app.ui.design.TrackMasterDesignTheme] 已经自己把
// 调色板映射到 Material 了，这里保留常量仅为兼容尚未删除的引用。
// ============================================================

val md_theme_light_primary = Color(0xFFD9461F)
val md_theme_light_onPrimary = Color(0xFFFFFFFF)
val md_theme_light_primaryContainer = Color(0x1FD9461F)
val md_theme_light_onPrimaryContainer = Color(0xFFD9461F)
val md_theme_light_secondary = Color(0xFFD9461F)
val md_theme_light_onSecondary = Color(0xFFFFFFFF)
val md_theme_light_secondaryContainer = Color(0x1FD9461F)
val md_theme_light_onSecondaryContainer = Color(0xFFD9461F)
val md_theme_light_tertiary = Color(0xFF00838F)
val md_theme_light_onTertiary = Color(0xFFFFFFFF)
val md_theme_light_tertiaryContainer = Color(0x1F00838F)
val md_theme_light_onTertiaryContainer = Color(0xFF00838F)
val md_theme_light_error = Color(0xFFFF3B30)
val md_theme_light_onError = Color(0xFFFFFFFF)
val md_theme_light_errorContainer = Color(0x1FFF3B30)
val md_theme_light_onErrorContainer = Color(0xFFFF3B30)
val md_theme_light_outline = Color(0xFFC6C6C8)
val md_theme_light_background = Color(0xFFF2F2F7)
val md_theme_light_onBackground = Color(0xFF000000)
val md_theme_light_surface = Color(0xFFFFFFFF)
val md_theme_light_onSurface = Color(0xFF000000)
val md_theme_light_surfaceVariant = Color(0xFFFFFFFF)
val md_theme_light_onSurfaceVariant = Color(0xFF3C3C43)
val md_theme_light_inverseSurface = Color(0xFF1C1C1E)
val md_theme_light_inverseOnSurface = Color(0xFFFFFFFF)
val md_theme_light_inversePrimary = Color(0xFFFF7F50)
val md_theme_light_shadow = Color(0x80000000)
val md_theme_light_surfaceTint = Color(0xFFD9461F)
val md_theme_light_outlineVariant = Color(0xFFE5E5EA)
val md_theme_light_scrim = Color(0x80000000)

val md_theme_dark_primary = Color(0xFFFF7F50)
val md_theme_dark_onPrimary = Color(0xFFFFFFFF)
val md_theme_dark_primaryContainer = Color(0x33FF7F50)
val md_theme_dark_onPrimaryContainer = Color(0xFFFF7F50)
val md_theme_dark_secondary = Color(0xFFFF7F50)
val md_theme_dark_onSecondary = Color(0xFFFFFFFF)
val md_theme_dark_secondaryContainer = Color(0x33FF7F50)
val md_theme_dark_onSecondaryContainer = Color(0xFFFF7F50)
val md_theme_dark_tertiary = Color(0xFF40CBE0)
val md_theme_dark_onTertiary = Color(0xFF000000)
val md_theme_dark_tertiaryContainer = Color(0x3340CBE0)
val md_theme_dark_onTertiaryContainer = Color(0xFF40CBE0)
val md_theme_dark_error = Color(0xFFFF453A)
val md_theme_dark_onError = Color(0xFFFFFFFF)
val md_theme_dark_errorContainer = Color(0x33FF453A)
val md_theme_dark_onErrorContainer = Color(0xFFFF453A)
val md_theme_dark_outline = Color(0xFF38383A)
val md_theme_dark_background = Color(0xFF000000)
val md_theme_dark_onBackground = Color(0xFFFFFFFF)
val md_theme_dark_surface = Color(0xFF1C1C1E)
val md_theme_dark_onSurface = Color(0xFFFFFFFF)
val md_theme_dark_surfaceVariant = Color(0xFF2C2C2E)
val md_theme_dark_onSurfaceVariant = Color(0xFFEBEBF5)
val md_theme_dark_inverseSurface = Color(0xFFFFFFFF)
val md_theme_dark_inverseOnSurface = Color(0xFF000000)
val md_theme_dark_inversePrimary = Color(0xFFD9461F)
val md_theme_dark_shadow = Color(0x80000000)
val md_theme_dark_surfaceTint = Color(0xFFFF7F50)
val md_theme_dark_outlineVariant = Color(0xFF38383A)
val md_theme_dark_scrim = Color(0x80000000)

/**
 * 旧代码里的排版别名。新的排版在 [AppType]。
 * 保留一个入口，避免迁移中的文件同时 import 两套。
 */
internal val legacyTypography get() = AppType
