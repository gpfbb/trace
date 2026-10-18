package com.trackmaster.app.ui.design

import android.app.Activity
import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.trackmaster.app.AppPreferences

/**
 * 当前生效的调色板。屏幕代码通过 [currentPalette] 读取。
 *
 * 用 `staticCompositionLocalOf`：主题变化时整棵子树重组，这符合预期
 * （主题切换本来就是全局事件），且读取方不需要订阅快照状态，开销最低。
 */
val LocalPalette = staticCompositionLocalOf<AppPalette> {
    // 默认值只在没有任何 Provider 时生效（如 Compose Preview）
    darkPalette(AccentColor.CORAL)
}

/** 当前主题设置（强调色 + 深浅模式）。 */
val LocalThemeSettings = staticCompositionLocalOf {
    ThemeSettings(AccentColor.CORAL, ThemeMode.SYSTEM)
}

data class ThemeSettings(
    val accent: AccentColor,
    val mode: ThemeMode
)

/**
 * 主题设置的持久化与读取。
 *
 * 存在 [AppPreferences] 的同一个 SharedPreferences 文件里，
 * 不另开文件（`ThemeManager` 之前用独立的 `theme_prefs`，没必要）。
 */
object ThemeStore {
    private const val KEY_ACCENT = "ui_accent_color"
    private const val KEY_MODE = "ui_theme_mode"

    fun load(prefs: AppPreferences): ThemeSettings {
        val sp = prefs.sharedPreferences
        val accent = sp.getString(KEY_ACCENT, null)
            ?.let { runCatching { AccentColor.valueOf(it) }.getOrNull() }
            ?: AccentColor.CORAL
        val mode = sp.getString(KEY_MODE, null)
            ?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
            ?: ThemeMode.SYSTEM
        return ThemeSettings(accent, mode)
    }

    fun save(prefs: AppPreferences, settings: ThemeSettings) {
        prefs.sharedPreferences.edit()
            .putString(KEY_ACCENT, settings.accent.name)
            .putString(KEY_MODE, settings.mode.name)
            .apply()
    }
}

/**
 * 主题状态持有者。用 `remember` 挂在 Activity 上，切换时触发重组。
 */
class ThemeController(
    private val prefs: AppPreferences,
    initial: ThemeSettings
) {
    var settings by mutableStateOf(initial)
        private set

    fun update(newSettings: ThemeSettings) {
        settings = newSettings
        ThemeStore.save(prefs, newSettings)
    }
}

@Composable
fun rememberThemeController(): ThemeController {
    val context = LocalContext.current
    val prefs = remember(context) {
        (context.applicationContext as com.trackmaster.app.TrackMasterApplication).appPreferences
    }
    return remember(prefs) { ThemeController(prefs, ThemeStore.load(prefs)) }
}

/**
 * 新主题入口。替代 `TrackMasterTheme` + `ThemeManager`。
 *
 * @param controller 由 [rememberThemeController] 创建，供设置页读写
 */
@Composable
fun TrackMasterDesignTheme(
    controller: ThemeController,
    content: @Composable () -> Unit
) {
    val settings = controller.settings
    val dark = settings.mode.resolveDark(isSystemInDarkTheme())
    val palette = remember(settings.accent, dark) { paletteFor(settings.accent, dark) }

    val colorScheme = remember(palette) { palette.toMaterialColorScheme() }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // 状态栏透明，内容从状态栏下方开始绘制由 Scaffold 的 insets 处理
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = Color.Transparent.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }

    CompositionLocalProvider(
        LocalPalette provides palette,
        LocalThemeSettings provides settings
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = MaterialTheme.typography,
            content = content
        )
    }
}

/**
 * 把调色板映射到 Material 3 的 ColorScheme。
 *
 * 保留这层映射是为了让 Material 原生组件（TextField、Switch、Dialog 等）
 * 自动跟随主题，不必逐个手写颜色。
 */
private fun AppPalette.toMaterialColorScheme() = if (isDark) {
    darkColorScheme(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = accentSubtle,
        onPrimaryContainer = accent,
        secondary = accent,
        onSecondary = onAccent,
        background = background,
        onBackground = label,
        surface = groupedCell,
        onSurface = label,
        surfaceVariant = elevated,
        onSurfaceVariant = labelSecondary,
        outline = border,
        outlineVariant = separator,
        error = danger,
        onError = onDanger
    )
} else {
    lightColorScheme(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = accentSubtle,
        onPrimaryContainer = accent,
        secondary = accent,
        onSecondary = onAccent,
        background = background,
        onBackground = label,
        surface = groupedCell,
        onSurface = label,
        surfaceVariant = elevated,
        onSurfaceVariant = labelSecondary,
        outline = border,
        outlineVariant = separator,
        error = danger,
        onError = onDanger
    )
}
