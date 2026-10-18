package com.trackmaster.app.ui.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trackmaster.app.R
import com.trackmaster.app.ui.design.AppIcons
import com.trackmaster.app.ui.design.AppType
import com.trackmaster.app.ui.design.DesignSecondaryButton
import com.trackmaster.app.ui.design.Space
import com.trackmaster.app.ui.design.currentPalette

/**
 * 地图页的批量工具弹窗：随机配色、统一填充透明度。
 *
 * 从 `MapScreen` 里拆出来的。原来它直接调 `app.dbHelper.updateFence()`、
 * 手工清空并重建 `app.geoFences`、再自己调 `refreshEngineFences()`——
 * 绕过 Repository 和 Engine 的刷新顺序。现在只接收两个回调，
 * 数据库操作全部走 `MapViewModel`。
 */
@Composable
fun MapToolsPopup(
    onApplyRandomColors: () -> Unit,
    onApplyGlobalAlpha: (Float) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = currentPalette
    // 记住上次拖到的透明度，关掉再打开不会跳回默认值
    var globalAlpha by rememberSaveable { mutableStateOf(0.3f) }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 40.dp),
        shape = RoundedCornerShape(16.dp),
        color = palette.groupedCell,
        // 不要 tonalElevation：Material 会拿主色给表面着色，
        // 在这个设计里会变成粉色调，和分组卡片的纯白对不上。
        tonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier.padding(Space.lg),
            verticalArrangement = Arrangement.spacedBy(Space.md)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.batch_tools),
                    style = AppType.headline,
                    color = palette.label
                )
                TextButton(onClick = onDismiss) {
                    Text(
                        text = stringResource(R.string.close),
                        style = AppType.footnote,
                        color = palette.labelSecondary
                    )
                }
            }

            ToolActionButton(
                text = stringResource(R.string.random_colors),
                icon = AppIcons.palette,
                onClick = onApplyRandomColors
            )

            Text(
                text = stringResource(R.string.global_transparency, (globalAlpha * 100).toInt()),
                style = AppType.caption,
                color = palette.labelSecondary
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "10%",
                    style = AppType.caption,
                    color = palette.labelTertiary
                )
                Slider(
                    value = globalAlpha,
                    onValueChange = { globalAlpha = it },
                    modifier = Modifier
                        .weight(1f)
                        .height(24.dp),
                    valueRange = 0.1f..0.8f,
                    steps = 6,
                    colors = SliderDefaults.colors(
                        thumbColor = palette.accent,
                        activeTrackColor = palette.accent
                    )
                )
                Text(
                    text = "80%",
                    style = AppType.caption,
                    color = palette.labelTertiary
                )
            }

            ToolActionButton(
                text = stringResource(R.string.apply_transparency),
                icon = AppIcons.opacityOutline,
                onClick = { onApplyGlobalAlpha(globalAlpha) }
            )
        }
    }
}

@Composable
private fun ToolActionButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    val palette = currentPalette
    DesignSecondaryButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = palette.accent,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(Space.sm))
        Text(
            text = text,
            style = AppType.subhead,
            color = palette.accent
        )
    }
}
