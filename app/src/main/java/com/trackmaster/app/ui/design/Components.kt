package com.trackmaster.app.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 分组列表组件库。
 *
 * 结构约定：
 * ```
 * GroupedSection("本月常去") {
 *     InfoRow("公司", "48 小时 20 分")
 *     Divider()
 *     InfoRow("家", "120 小时 05 分")
 * }
 * ```
 * 一个 [GroupedSection] 是一张圆角卡片，卡片内用 [Divider] 分行。
 * 首行和末行会自动获得圆角，中间的行是直角——所以不要手动给行加背景色。
 */

/** 便捷读取当前调色板。 */
val currentPalette: AppPalette
    @Composable get() = LocalPalette.current

// ============================================================
// 分组容器
// ============================================================

/**
 * 分组卡片。
 *
 * @param title section 标题，显示在卡片上方（无标题则不占空间）
 * @param footer section 说明，显示在卡片下方
 */
@Composable
fun GroupedSection(
    modifier: Modifier = Modifier,
    title: String? = null,
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val palette = currentPalette

    Column(modifier.fillMaxWidth()) {
        if (title != null) {
            Text(
                text = title.uppercase(),
                style = AppType.footnote,
                color = palette.labelSecondary,
                modifier = Modifier.padding(
                    start = Space.lg,
                    end = Space.lg,
                    bottom = Space.sm,
                    top = Space.xs
                )
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radius.md))
                .background(palette.groupedCell),
            content = content
        )

        if (footer != null) {
            Text(
                text = footer,
                style = AppType.caption,
                color = palette.labelSecondary,
                modifier = Modifier.padding(
                    start = Space.lg,
                    end = Space.lg,
                    top = Space.sm
                )
            )
        }
    }
}

/** 分组卡片内的分割线。自动跳过首行左侧缩进。 */
@Composable
fun Divider(
    modifier: Modifier = Modifier,
    startInset: Boolean = true
) {
    val palette = currentPalette
    Box(
        modifier
            .fillMaxWidth()
            .padding(start = if (startInset) Space.lg else 0.dp)
            .height(0.5.dp)
            .background(palette.separator)
    )
}

// ============================================================
// 列表行
// ============================================================

/**
 * 标准列表行：图标 + 标题 + 可选副标题 + 右侧内容 + 可选箭头。
 *
 * @param leadingIcon 左侧图标
 * @param leadingIconTint 图标颜色，默认用强调色
 * @param title 主文字
 * @param subtitle 副标题，显示在标题下方
 * @param trailingText 右侧文字（如当前值）
 * @param showChevron 是否显示右侧箭头，表示可以进入下一级
 * @param onClick 点击回调。为 null 时整行不可点
 */
@Composable
fun SettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leadingIcon: ImageVector? = null,
    leadingIconTint: Color? = null,
    trailingText: String? = null,
    trailingContent: (@Composable RowScope.() -> Unit)? = null,
    showChevron: Boolean = false,
    titleColor: Color? = null,
    onClick: (() -> Unit)? = null,
    content: (@Composable RowScope.() -> Unit)? = null
) {
    val palette = currentPalette
    val minHeight = if (subtitle != null) RowSize.twoLineMinHeight else RowSize.minHeight

    val rowModifier = modifier
        .fillMaxWidth()
        .heightIn(min = minHeight)
        .let { if (onClick != null) it.clickable(onClick = onClick) else it }
        .padding(horizontal = Space.lg, vertical = Space.md)

    Row(
        modifier = rowModifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                tint = leadingIconTint ?: palette.accent,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(Space.md))
        }

        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = AppType.body,
                color = titleColor ?: palette.label,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = AppType.footnote,
                    color = palette.labelSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (trailingText != null) {
            Spacer(Modifier.width(Space.sm))
            Text(
                text = trailingText,
                style = AppType.body,
                color = palette.labelSecondary,
                maxLines = 1
            )
        }

        if (trailingContent != null) {
            Spacer(Modifier.width(Space.sm))
            trailingContent()
        }

        if (showChevron) {
            Spacer(Modifier.width(Space.xs))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = palette.labelTertiary,
                modifier = Modifier.size(20.dp)
            )
        }

        if (content != null) {
            Spacer(Modifier.width(Space.sm))
            content()
        }
    }
}

/**
 * 只有主标题和副标题的行，没有图标和右侧内容。
 * 适合"标签 — 值"的展示型信息。
 */
@Composable
fun InfoRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color? = null,
    onClick: (() -> Unit)? = null
) {
    val palette = currentPalette
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = RowSize.minHeight)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = Space.lg, vertical = Space.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = AppType.body,
            color = palette.label,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.width(Space.md))
        Text(
            text = value,
            style = AppType.body,
            color = valueColor ?: palette.labelSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ============================================================
// 页面骨架
// ============================================================

/**
 * 页面大标题。放在滚动内容的最顶部。
 *
 * @param subtitle 标题下方的说明
 */
@Composable
fun LargeTitle(
    text: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null
) {
    val palette = currentPalette
    Column(
        modifier
            .fillMaxWidth()
            .padding(
                start = Space.screenHorizontal,
                end = Space.screenHorizontal,
                top = Space.md,
                bottom = Space.lg
            )
    ) {
        Text(text = text, style = AppType.largeTitle, color = palette.label)
        if (subtitle != null) {
            Spacer(Modifier.height(Space.xs))
            Text(text = subtitle, style = AppType.subhead, color = palette.labelSecondary)
        }
    }
}

// ============================================================
// 按钮
// ============================================================

/** 主按钮：强调色填充，胶囊形。 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val palette = currentPalette
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(Radius.pill))
            .background(if (enabled) palette.accent else palette.labelDisabled)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = Space.xl, vertical = Space.md),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = AppType.headline,
            color = if (enabled) palette.onAccent else palette.labelTertiary
        )
    }
}

/** 次按钮：无填充，强调色文字。 */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val palette = currentPalette
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(Radius.pill))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = Space.xl, vertical = Space.md),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = AppType.body,
            color = if (enabled) palette.accent else palette.labelTertiary
        )
    }
}

/**
 * 次按钮的"通用内容"版本。
 *
 * [SecondaryButton] 只接受字符串，无法塞图标或自定义排版。
 * 旧代码里的 `GlassButton` 是 RowScope 内容槽，迁移期需要一个能接
 * 任意内容的等价物，否则按钮里的图标和文字会被丢掉。
 */
@Composable
fun DesignSecondaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    val palette = currentPalette
    Row(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(Radius.pill))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = Space.md, vertical = Space.sm),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

// ============================================================
// 状态徽章
// ============================================================

/**
 * 小号状态标签。用于"追踪中""已停止"这类即时状态。
 *
 * @param color 徽章的底色，默认用成功色
 * @param text 标签文字
 */
@Composable
fun StatusBadge(
    text: String,
    modifier: Modifier = Modifier,
    color: Color? = null
) {
    val palette = currentPalette
    val badgeColor = color ?: palette.success
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(Radius.pill))
            .background(badgeColor.copy(alpha = 0.15f))
            .padding(horizontal = Space.sm, vertical = Space.xs),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = AppType.caption,
            color = badgeColor
        )
    }
}

// ============================================================
// 圆角图标容器
// ============================================================

/**
 * 圆角方块里放一个图标的容器，用于 section 行的左侧图标位。
 * 相比裸图标，视觉上更像 iOS 设置页。
 */
@Composable
fun IconTile(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    tint: Color? = null,
    containerColor: Color? = null,
    size: androidx.compose.ui.unit.Dp = 29.dp
) {
    val palette = currentPalette
    val tileColor = containerColor ?: palette.accent
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.23f))
            .background(tileColor),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint ?: Color.White,
            modifier = Modifier.size(size * 0.58f)
        )
    }
}

// ============================================================
// 可滚动内容容器
// ============================================================

/**
 * 页面内的 section 间距容器。用 Column 包住多个 section，自动加间隔。
 */
@Composable
fun SectionColumn(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Space.sectionGap),
        content = content
    )
}

/**
 * 为分组内容提供默认文字色。让 [Text] 不写 color 时也能拿到正确颜色。
 */
@Composable
fun ProvideContentColors(
    content: @Composable () -> Unit
) {
    val palette = currentPalette
    CompositionLocalProvider(
        LocalContentColor provides palette.label
    ) {
        content()
    }
}

/** 便捷：把 [AppType] 的样式转成可直接用于 Text 的参数组合。 */
@Composable
fun ThemedText(
    text: String,
    style: TextStyle = AppType.body,
    color: Color? = null,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE
) {
    Text(
        text = text,
        style = style,
        color = color ?: currentPalette.label,
        modifier = modifier,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis
    )
}
