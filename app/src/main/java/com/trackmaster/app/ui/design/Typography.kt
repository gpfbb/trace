package com.trackmaster.app.ui.design

import androidx.compose.material3.Typography

/**
 * 把 [AppType] 的字号阶梯映射到 Material 3 的排版槽位。
 *
 * 屏幕代码直接用 `AppType.body` 这类语义字号；这里的映射是为了让
 * Material 原生组件（TextField 标签、Dialog 标题、Snackbar 等）
 * 也跟随同一套字号，不会出现"自己写的文字 17sp、Material 的文字 14sp"
 * 这种不一致。
 */
val MaterialTypography = Typography(
    displayLarge = AppType.displayLarge,
    displayMedium = AppType.displayMedium,
    displaySmall = AppType.displaySmall,
    headlineLarge = AppType.largeTitle,
    headlineMedium = AppType.title,
    headlineSmall = AppType.title2,
    titleLarge = AppType.title2,
    titleMedium = AppType.headline,
    titleSmall = AppType.subhead,
    bodyLarge = AppType.body,
    bodyMedium = AppType.subhead,
    bodySmall = AppType.footnote,
    labelLarge = AppType.headline,
    labelMedium = AppType.footnote,
    labelSmall = AppType.caption
)
