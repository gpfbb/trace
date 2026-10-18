package com.trackmaster.app.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

/**
 * 卡片容器。
 *
 * 替代旧的 `GlassCard`——那个实现带两个无限循环动画（每帧重建 Brush）
 * 和发光阴影，在新设计里既不需要也耗电。这里就是纯色圆角卡片。
 */
@Composable
fun SurfaceCard(
    modifier: Modifier = Modifier,
    contentPadding: androidx.compose.ui.unit.Dp = Space.lg,
    content: @Composable ColumnScope.() -> Unit
) {
    val palette = currentPalette
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(Radius.md))
            .background(palette.groupedCell)
            .padding(contentPadding),
        content = content
    )
}

/**
 * 应用内对话框。
 *
 * 替代旧的 `GlassDialog`。旧实现把按钮排成一行右对齐、标题用主题色，
 * 而且依赖 `GlassCard` 的发光底色。这里换成标准的分组卡片样式：
 * 左对齐标题、说明文字、底部操作按钮右对齐。
 *
 * 按钮由调用方传入，保持和旧接口一致，避免迁移时改动业务代码。
 */
@Composable
fun AppDialog(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null
) {
    val palette = currentPalette

    Dialog(onDismissRequest = onDismissRequest) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radius.xl))
                .background(palette.groupedCell)
                .padding(Space.xl)
        ) {
            Box(Modifier.fillMaxWidth()) {
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.material3.LocalContentColor provides palette.label
                ) {
                    title()
                }
            }

            if (text != null) {
                Spacer(Modifier.height(Space.md))
                Box(Modifier.fillMaxWidth()) {
                    androidx.compose.runtime.CompositionLocalProvider(
                        androidx.compose.material3.LocalContentColor provides palette.labelSecondary
                    ) {
                        text()
                    }
                }
            }

            Spacer(Modifier.height(Space.xl))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                dismissButton?.let {
                    it()
                    Spacer(Modifier.width(Space.sm))
                }
                confirmButton()
            }
        }
    }
}

/** 对话框里的次要按钮文字色，给调用方复用。 */
val dialogDismissColor: androidx.compose.ui.graphics.Color
    @Composable get() = currentPalette.labelSecondary

/** 对话框里的强调按钮文字色。 */
val dialogConfirmColor: androidx.compose.ui.graphics.Color
    @Composable get() = currentPalette.accent

/** 供对话框内容使用的 12dp 底部间距。 */
val DialogButtonGap = 12.dp
