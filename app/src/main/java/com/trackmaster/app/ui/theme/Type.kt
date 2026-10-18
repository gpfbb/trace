package com.trackmaster.app.ui.theme

import androidx.compose.material3.Typography
import com.trackmaster.app.ui.design.AppType

/**
 * Material 3 排版映射。
 *
 * 字号阶梯的真实定义在 [com.trackmaster.app.ui.design.AppType]。
 * 这里把它映射到 Material 的槽位，让 Material 原生组件（Dialog 标题、
 * TextField 标签等）与新设计保持一致。
 */
val Typography = Typography(
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
