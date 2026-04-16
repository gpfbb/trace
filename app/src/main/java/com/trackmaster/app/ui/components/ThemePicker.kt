package com.trackmaster.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import com.trackmaster.app.R
import com.trackmaster.app.ui.theme.*

/**
 * 主题选择器对话框
 */
@Composable
fun ThemePickerDialog(
    currentTheme: AppTheme,
    onThemeSelected: (AppTheme) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedTheme by remember { mutableStateOf(currentTheme) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(R.string.select_theme),
                fontWeight = FontWeight.Bold,
                color = getThemeAccent(currentTheme)
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    stringResource(R.string.select_theme_desc),
                    fontSize = 13.sp,
                    color = Color(0xFFBDBDBD)
                )

                Spacer(modifier = Modifier.height(8.dp))

                AppTheme.entries.forEach { theme ->
                    ThemeOptionCard(
                        theme = theme,
                        isSelected = selectedTheme == theme,
                        onClick = { selectedTheme = theme }
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onThemeSelected(selectedTheme)
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = getThemeAccent(currentTheme)
                )
            ) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel), color = Color.Gray)
            }
        }
    )
}

@Composable
private fun ThemeOptionCard(
    theme: AppTheme,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val primary = getThemePrimary(theme)
    val accent = getThemeAccent(theme)
    val background = getThemeBackground(theme)
    val textPrimary = getThemeTextPrimary(theme)
    val textTertiary = getThemeTextTertiary(theme)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = background
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 主题预览色块
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(primary)
                    )
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(accent)
                    )
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(background)
                    )
                }

                // 选中指示
                if (isSelected) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(primary),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "✓",
                            color = Color.White,
                            fontSize = 14.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = theme.displayName,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                color = textPrimary
            )

            Text(
                text = theme.description,
                fontSize = 12.sp,
                color = textTertiary
            )
        }
    }
}

/**
 * 简洁的主题选择器（适合设置页面）
 */
@Composable
fun CompactThemeSelector(
    currentTheme: AppTheme,
    onThemeSelected: (AppTheme) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        AppTheme.entries.forEach { theme ->
            val primary = getThemePrimary(theme)
            val accent = getThemeAccent(theme)
            val background = getThemeBackground(theme)
            val isSelected = currentTheme == theme

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onThemeSelected(theme) }
                    .padding(8.dp)
            ) {
                // 主题色预览
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(background),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                brush = androidx.compose.ui.graphics.Brush.linearGradient(
                                    colors = listOf(primary, accent, background)
                                )
                            )
                    )
                    if (isSelected) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .border(2.dp, primary, RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("✓", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = theme.displayName,
                    fontSize = 10.sp,
                    color = if (isSelected) primary else Color.Gray,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}

// 主题颜色访问函数 - 委托给 ThemeManager
@Composable
fun getThemePrimary(theme: AppTheme): Color = com.trackmaster.app.ui.theme.getThemePrimary(theme)
@Composable
fun getThemeAccent(theme: AppTheme): Color = com.trackmaster.app.ui.theme.getThemeAccent(theme)
@Composable
fun getThemeBackground(theme: AppTheme): Color = com.trackmaster.app.ui.theme.getThemeBackground(theme)
@Composable
fun getThemeTextPrimary(theme: AppTheme): Color = com.trackmaster.app.ui.theme.getThemeTextPrimary(theme)

@Composable
fun getThemeTextTertiary(theme: AppTheme): Color = when (theme) {
    AppTheme.DARK_NIGHT -> Color(0xFFBDBDBD)
    AppTheme.SOFT_EYE -> Color(0xFF95A5A6)
    AppTheme.CLASSIC_RED -> Color(0xFFB0B0B0)
    AppTheme.MINIMAL_GRAPHITE -> Color(0xFF9E9E9E)
    AppTheme.AURORA_GREEN -> Color(0xFF80CBC4)    // 青绿
    AppTheme.DREAM_PURPLE -> Color(0xFFB39DDB)    // 浅紫
    AppTheme.SUNSET_ORANGE -> Color(0xFFFFAB91)    // 橙灰
    AppTheme.GLACIER_BLUE -> Color(0xFF81D4FA)    // 冰蓝灰
    AppTheme.CYBER_TEAL -> Color(0xFF00CED1)    // 青灰
    AppTheme.MINT_FRESH -> Color(0xFF81C784)    // 绿灰
    AppTheme.ROSE_GOLD -> Color(0xFFF48FB1)    // 粉灰
    AppTheme.SPACE_GRAY -> Color(0xFFB0BEC5)    // 银灰
    AppTheme.LAVENDER -> Color(0xFFCE93D8)    // 紫灰
}
