package com.trackmaster.app.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.trackmaster.app.R

/**
 * 主题设置对话框：深浅模式 + 强调色。
 *
 * 替代原来的 13 个暗色主题选择器。用户真正的诉求是"换主色"，
 * 深浅模式跟随系统即可，不需要为每种组合单独维护一套配色。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ThemeSettingsDialog(
    settings: ThemeSettings,
    onSettingsChange: (ThemeSettings) -> Unit,
    onDismiss: () -> Unit
) {
    val palette = currentPalette

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radius.xl))
                .background(palette.groupedCell)
                .padding(Space.xl)
        ) {
            Text(
                text = stringResource(R.string.theme),
                style = AppType.title2,
                color = palette.label
            )
            Spacer(Modifier.height(Space.xl))

            // 深浅模式
            Text(
                text = stringResource(R.string.theme_mode),
                style = AppType.footnote,
                color = palette.labelSecondary
            )
            Spacer(Modifier.height(Space.sm))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                ThemeMode.entries.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = settings.mode == mode,
                        onClick = { onSettingsChange(settings.copy(mode = mode)) },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = ThemeMode.entries.size
                        )
                    ) {
                        Text(
                            text = stringResource(mode.labelRes()),
                            style = AppType.footnote
                        )
                    }
                }
            }

            Spacer(Modifier.height(Space.xl))

            // 强调色
            Text(
                text = stringResource(R.string.accent_color),
                style = AppType.footnote,
                color = palette.labelSecondary
            )
            Spacer(Modifier.height(Space.md))
            AccentColorGrid(
                selected = settings.accent,
                dark = palette.isDark,
                onSelect = { onSettingsChange(settings.copy(accent = it)) }
            )

            Spacer(Modifier.height(Space.xl))

            PrimaryButton(
                text = stringResource(R.string.done),
                onClick = onDismiss
            )
        }
    }
}

@Composable
private fun AccentColorGrid(
    selected: AccentColor,
    dark: Boolean,
    onSelect: (AccentColor) -> Unit
) {
    val palette = currentPalette
    // 一行 4 个，两行放下 8 个强调色
    Column(verticalArrangement = Arrangement.spacedBy(Space.md)) {
        AccentColor.entries.chunked(4).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Space.md)
            ) {
                row.forEach { accent ->
                    val isSelected = accent == selected
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .clip(RoundedCornerShape(Radius.md))
                            .background(accent.tint(isDark = palette.isDark))
                            .border(
                                width = if (isSelected) 3.dp else 0.dp,
                                color = if (isSelected) palette.label else Color.Transparent,
                                shape = RoundedCornerShape(Radius.md)
                            )
                            .clickable { onSelect(accent) },
                        contentAlignment = Alignment.Center
                    ) {
                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = null,
                                tint = accent.on(isDark = palette.isDark),
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
                // 不足 4 个时补齐占位，避免最后一行被拉伸
                repeat(4 - row.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

private fun ThemeMode.labelRes(): Int = when (this) {
    ThemeMode.SYSTEM -> R.string.theme_mode_system
    ThemeMode.LIGHT -> R.string.theme_mode_light
    ThemeMode.DARK -> R.string.theme_mode_dark
}

/**
 * 设置页里的主题入口行，显示当前选择。
 */
@Composable
fun ThemeSettingRow(
    settings: ThemeSettings,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = currentPalette
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(RowSize.minHeight)
            .clickable(onClick = onClick)
            .padding(horizontal = Space.lg),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.theme),
            style = AppType.body,
            color = palette.label,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = stringResource(
                when (settings.mode) {
                    ThemeMode.SYSTEM -> R.string.theme_mode_system
                    ThemeMode.LIGHT -> R.string.theme_mode_light
                    ThemeMode.DARK -> R.string.theme_mode_dark
                }
            ),
            style = AppType.body,
            color = palette.labelSecondary
        )
        Spacer(Modifier.width(Space.sm))
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(settings.accent.tint(isDark = palette.isDark))
        )
        Spacer(Modifier.width(Space.xs))
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = palette.labelTertiary,
            modifier = Modifier.size(20.dp)
        )
    }
}
