package com.trackmaster.app.ui.theme

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.*
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

// 液态玻璃拟态卡片组件（高级质感版）
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "liquidWave")
    val waveOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(8000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "waveOffset"
    )

    val glowPulse by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowPulse"
    )

    Card(
        modifier = modifier
            .shadow(
                elevation = 12.dp,
                shape = RoundedCornerShape(24.dp),
                ambientColor = primaryColor().copy(alpha = glowPulse * 0.8f),
                spotColor = primaryColor().copy(alpha = glowPulse * 0.6f)
            )
            .clip(RoundedCornerShape(24.dp))
            .border(
                width = 0.5.dp,
                color = GlassInnerGlow,
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
            )
            .border(
                width = 1.dp,
                color = GlassBorder,
                shape = RoundedCornerShape(24.dp)
            )
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(
                        glassPanel(),
                        surfaceColor(),
                        glassPanel()
                    ),
                    start = androidx.compose.ui.geometry.Offset(
                        waveOffset * 1000f,
                        waveOffset * 1000f
                    ),
                    end = androidx.compose.ui.geometry.Offset(
                        (waveOffset + 1f) * 1000f,
                        (waveOffset + 1f) * 1000f
                    )
                )
            )
            .graphicsLayer {
                renderEffect = null
            },
        colors = CardDefaults.cardColors(
            containerColor = Color.Transparent
        ),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            content = content
        )
    }
}

// 精简的顶部悬浮卡片（高级质感）
@Composable
fun GlassTopCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "topGlow")
    val glowPulse by infiniteTransition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.55f,
        animationSpec = infiniteRepeatable(
            animation = tween(2500, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "topGlowPulse"
    )

    Card(
        modifier = modifier
            .shadow(
                elevation = 10.dp,
                shape = RoundedCornerShape(24.dp),
                ambientColor = primaryColor().copy(alpha = glowPulse),
                spotColor = primaryColor().copy(alpha = glowPulse * 0.7f)
            )
            .clip(RoundedCornerShape(24.dp))
            .border(
                width = 0.5.dp,
                color = GlassInnerGlow,
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
            )
            .border(
                width = 0.8.dp,
                color = GlassBorderLight,
                shape = RoundedCornerShape(24.dp)
            )
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(
                        glassPanel(),
                        surfaceColor()
                    ),
                    start = androidx.compose.ui.geometry.Offset(0f, 0f),
                    end = androidx.compose.ui.geometry.Offset(100f, 100f)
                )
            )
            .graphicsLayer {
                renderEffect = null
            },
        colors = CardDefaults.cardColors(
            containerColor = Color.Transparent
        ),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            content = content
        )
    }
}

// 真正的 Bottom Sheet 组件 - 固定底部，改变高度，支持自由拖拽
@Composable
fun GlassBottomSheetCard(
    isExpanded: Boolean,
    onExpandChange: (Boolean) -> Unit,
    @Suppress("UNUSED_PARAMETER") peekHeight: Float = 0.22f,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val screenHeight = configuration.screenHeightDp.dp
    val maxHeight = screenHeight * 0.50f // 最大高度 50%（半屏）
    val minHeight = 60.dp // 只显示顶部箭头按钮的高度

    // 直接使用 isExpanded 控制高度，不再需要拖拽状态
    val targetHeight = if (isExpanded) maxHeight else minHeight
    
    // 平滑动画到目标高度
    val displayHeight by animateDpAsState(
        targetValue = targetHeight,
        animationSpec = tween(
            durationMillis = 400,
            easing = FastOutSlowInEasing
        ),
        label = "sheetHeight"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "bottomGlow")
    val glowPulse by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(2800, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bottomGlowPulse"
    )

    // 固定在底部的容器 - 只改变高度，无整体位移
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(displayHeight)
            .graphicsLayer {
                // 启用硬件加速
                clip = true
            }
    ) {
        // 玻璃卡片 - 固定在底部，向下延伸填满
        Card(
            modifier = Modifier
                .fillMaxSize()
                .shadow(
                    elevation = 14.dp,
                    shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                    ambientColor = primaryColor().copy(alpha = glowPulse),
                    spotColor = primaryColor().copy(alpha = glowPulse * 0.7f)
                )
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .border(
                    width = 0.5.dp,
                    color = GlassInnerGlow,
                    shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
                )
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            glassPanel(),
                            surfaceColor(),
                            glassPanel()
                        )
                    )
                ),
            colors = CardDefaults.cardColors(
                containerColor = Color.Transparent
            ),
            elevation = CardDefaults.cardElevation(0.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                // 点击切换区域 - 整个顶部栏都可以点击
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(60.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {
                                onExpandChange(!isExpanded)
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    // 点击指示器 - 使用旋转动画的箭头
                    val arrowRotation by animateFloatAsState(
                        targetValue = if (isExpanded) 180f else 0f,
                        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
                        label = "arrowRotation"
                    )

                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(
                                brush = Brush.linearGradient(
                                    colors = listOf(primaryColor(), accentColor()),
                                    start = androidx.compose.ui.geometry.Offset(0f, 0f),
                                    end = androidx.compose.ui.geometry.Offset(100f, 100f)
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowUpward,
                            contentDescription = if (isExpanded) "收起" else "展开",
                            modifier = Modifier
                                .size(24.dp)
                                .graphicsLayer { rotationZ = arrowRotation },
                            tint = Color.White
                        )
                    }
                }
                // 内容区域 - 让内容自己处理滚动（如 LazyColumn）
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 16.dp),
                    content = content
                )
            }
        }
    }
}

// 液态玻璃拟态按钮组件（高级质感版）
@Composable
fun GlassButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    var isPressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.93f else 1f,
        animationSpec = spring(
            dampingRatio = 0.85f,
            stiffness = 400f
        ),
        label = "buttonScale"
    )

    Box(
        modifier = modifier
            .scale(scale)
            .shadow(
                elevation = if (isPressed) 4.dp else 10.dp,
                shape = RoundedCornerShape(20.dp),
                ambientColor = primaryColor().copy(alpha = 0.4f),
                spotColor = primaryColor().copy(alpha = 0.3f)
            )
            .clip(RoundedCornerShape(20.dp))
            .border(
                width = 0.5.dp,
                color = GlassInnerGlow,
                shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
            )
            .border(
                width = 1.dp,
                color = GlassBorder,
                shape = RoundedCornerShape(20.dp)
            )
            .background(
                brush = if (isPressed) {
                    Brush.linearGradient(
                        colors = listOf(primaryColor(), accentColor()),
                        start = androidx.compose.ui.geometry.Offset(0f, 0f),
                        end = androidx.compose.ui.geometry.Offset(100f, 100f)
                    )
                } else {
                    Brush.linearGradient(
                        colors = listOf(glassPanel(), glassPanel()),
                        start = androidx.compose.ui.geometry.Offset(0f, 0f),
                        end = androidx.compose.ui.geometry.Offset(100f, 100f)
                    )
                }
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = rememberRipple(color = primaryColor()),
                enabled = enabled,
                onClick = {
                    isPressed = true
                    onClick()
                    isPressed = false
                }
            )
            .graphicsLayer {
                if (isPressed) {
                    shadowElevation = 15f
                    shape = RoundedCornerShape(20.dp)
                    clip = true
                }
            }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            content = content
        )
    }
}

// 液态玻璃拟态浮动操作按钮（统一霓虹珊瑚橙）
@Composable
fun GlassFloatingActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = primaryColor(),
    content: @Composable () -> Unit
) {
    var isPressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.91f else 1f,
        animationSpec = spring(
            dampingRatio = 0.85f,
            stiffness = 400f
        ),
        label = "fabScale"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "fabGlow")
    val glowPulse by infiniteTransition.animateFloat(
        initialValue = 0.45f,
        targetValue = 0.75f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "fabGlowPulse"
    )

    Box(
        modifier = modifier
            .scale(scale)
            .shadow(
                elevation = if (isPressed) 12.dp else 18.dp,
                shape = RoundedCornerShape(50),
                ambientColor = containerColor.copy(alpha = glowPulse),
                spotColor = containerColor.copy(alpha = glowPulse * 0.85f)
            )
            .clip(RoundedCornerShape(50))
            .border(
                width = 0.5.dp,
                color = GlassInnerGlow,
                shape = RoundedCornerShape(50)
            )
            .border(
                width = 1.5.dp,
                color = GlassBorderLight,
                shape = RoundedCornerShape(50)
            )
            .background(
                brush = Brush.radialGradient(
                    colors = listOf(
                        containerColor,
                        containerColor.copy(alpha = 0.9f)
                    )
                )
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = rememberRipple(color = Color.White),
                onClick = {
                    isPressed = true
                    onClick()
                    isPressed = false
                }
            )
    ) {
        Box(
            modifier = Modifier.padding(16.dp),
            contentAlignment = Alignment.Center,
            content = { content() }
        )
    }
}

// 页面入场动画包装器
@Composable
fun ScreenEnterAnimation(
    visible: Boolean = true,
    content: @Composable () -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(
            animationSpec = tween(400, easing = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f))
        ) + scaleIn(
            initialScale = 0.95f,
            animationSpec = tween(400, easing = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f))
        ),
        exit = fadeOut() + scaleOut()
    ) {
        content()
    }
}

// 液态玻璃拟态对话框
@Composable
fun GlassDialog(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null
) {
    var showDialog by remember { mutableStateOf(true) }
    
    AnimatedVisibility(
        visible = showDialog,
        enter = fadeIn(animationSpec = tween(300)) + scaleIn(
            initialScale = 0.9f,
            animationSpec = spring(dampingRatio = 0.85f, stiffness = 300f)
        ),
        exit = fadeOut(animationSpec = tween(200)) + scaleOut(targetScale = 0.9f)
    ) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = {
                showDialog = false
                onDismissRequest()
            }
        ) {
            GlassCard(
                modifier = modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp)
                    ) {
                        ProvideTextStyle(
                            MaterialTheme.typography.headlineSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = primaryColor()
                            )
                        ) {
                            title()
                        }
                    }
                    
                    text?.let {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 24.dp)
                        ) {
                            ProvideTextStyle(
                                MaterialTheme.typography.bodyMedium.copy(
                                    color = Color(0xFFE0E0E0)
                                )
                            ) {
                                it()
                            }
                        }
                    }
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        dismissButton?.let {
                            it()
                            Spacer(modifier = Modifier.width(12.dp))
                        }
                        confirmButton()
                    }
                }
            }
        }
    }
}

// 液态玻璃拟态徽章
@Composable
fun GlassBadge(
    containerColor: Color = primaryColor(),
    content: @Composable () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "badgeGlow")
    val glowPulse by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "badgeGlowPulse"
    )

    Surface(
        modifier = Modifier
            .shadow(
                elevation = 4.dp,
                shape = RoundedCornerShape(12.dp),
                ambientColor = containerColor.copy(alpha = glowPulse),
                spotColor = containerColor.copy(alpha = glowPulse * 0.7f)
            )
            .border(
                width = 0.5.dp,
                color = GlassInnerGlow,
                shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
            )
            .border(
                width = 1.dp,
                color = GlassBorder,
                shape = RoundedCornerShape(12.dp)
            ),
        color = containerColor.copy(alpha = 0.95f),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 0.dp
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            ProvideTextStyle(
                MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Medium,
                    color = if (containerColor == primaryColor() || containerColor == accentColor()) 
                        Color.Black else Color.White
                )
            ) {
                content()
            }
        }
    }
}

// 液态玻璃拟态输入框
@Composable
fun GlassTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: (@Composable () -> Unit)? = null,
    placeholder: (@Composable () -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    singleLine: Boolean = true
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .shadow(
                elevation = 4.dp,
                shape = RoundedCornerShape(16.dp),
                ambientColor = primaryColor().copy(alpha = 0.15f),
                spotColor = primaryColor().copy(alpha = 0.1f)
            ),
        label = label,
        placeholder = placeholder,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        singleLine = singleLine,
        shape = RoundedCornerShape(16.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = glassPanel(),
            unfocusedContainerColor = glassPanel(),
            focusedBorderColor = primaryColor(),
            unfocusedBorderColor = GlassBorder,
            cursorColor = primaryColor(),
            focusedLabelColor = primaryColor(),
            unfocusedLabelColor = Color(0xFFBDBDBD)
        )
    )
}

// ============================================================
// 自适应文字组件库 - 符合WCAG 2.1 AA标准
// ============================================================

/**
 * 文字优先级枚举，用于自适应对比度选择
 */
enum class TextPriority {
    PRIMARY,    // 主文字 - 最高对比度
    SECONDARY,  // 次要文字 - 高对比度
    TERTIARY,   // 辅助文字 - 中等对比度
    DISABLED,   // 禁用文字 - 最低对比度
    ACCENT      // 强调文字 - 使用主题色
}

/**
 * 响应式文字组件
 * - 自动适应屏幕尺寸
 * - 自动切换主题颜色
 * - 平滑的动画过渡
 * - 符合WCAG 2.1 AA对比度标准
 */
@Composable
fun ResponsiveText(
    text: String,
    priority: TextPriority = TextPriority.PRIMARY,
    fontSize: androidx.compose.ui.unit.TextUnit = androidx.compose.ui.unit.TextUnit.Unspecified,
    fontWeight: FontWeight = FontWeight.Normal,
    maxLines: Int = Int.MAX_VALUE,
    modifier: Modifier = Modifier,
    textAlign: androidx.compose.ui.text.style.TextAlign? = null,
    softWrap: Boolean = true,
    overflow: androidx.compose.ui.text.style.TextOverflow = TextOverflow.Ellipsis,
    lineHeight: androidx.compose.ui.unit.TextUnit = androidx.compose.ui.unit.TextUnit.Unspecified
) {
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val screenWidthDp = configuration.screenWidthDp
    
    // 根据屏幕宽度自动调整字体大小
    val autoFontSize = if (fontSize == androidx.compose.ui.unit.TextUnit.Unspecified) {
        when {
            screenWidthDp < 360 -> 12.sp
            screenWidthDp < 480 -> 14.sp
            screenWidthDp < 600 -> 15.sp
            else -> 16.sp
        }
    } else {
        fontSize
    }
    
    // 根据优先级选择文字颜色
    val textColor = when (priority) {
        TextPriority.PRIMARY -> TextPrimary
        TextPriority.SECONDARY -> TextSecondary
        TextPriority.TERTIARY -> TextTertiary
        TextPriority.DISABLED -> TextDisabled
        TextPriority.ACCENT -> primaryColor()
    }
    
    // 平滑的颜色动画
    val animatedColor by androidx.compose.animation.animateColorAsState(
        targetValue = textColor,
        animationSpec = tween(durationMillis = 300, easing = EaseInOut),
        label = "textColor"
    )
    
    Text(
        text = text,
        color = animatedColor,
        fontSize = autoFontSize,
        fontWeight = fontWeight,
        maxLines = maxLines,
        modifier = modifier,
        textAlign = textAlign,
        softWrap = softWrap,
        overflow = overflow,
        lineHeight = lineHeight
    )
}

/**
 * 响应式标题文字组件
 * - 更大的字体
 * - 自动适应屏幕尺寸
 */
@Composable
fun ResponsiveTitleText(
    text: String,
    priority: TextPriority = TextPriority.PRIMARY,
    fontWeight: FontWeight = FontWeight.Bold,
    maxLines: Int = Int.MAX_VALUE,
    modifier: Modifier = Modifier,
    textAlign: androidx.compose.ui.text.style.TextAlign? = null
) {
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val screenWidthDp = configuration.screenWidthDp
    
    val titleFontSize = when {
        screenWidthDp < 360 -> 18.sp
        screenWidthDp < 480 -> 20.sp
        screenWidthDp < 600 -> 22.sp
        else -> 24.sp
    }
    
    ResponsiveText(
        text = text,
        priority = priority,
        fontSize = titleFontSize,
        fontWeight = fontWeight,
        maxLines = maxLines,
        modifier = modifier,
        textAlign = textAlign
    )
}

/**
 * 响应式小标题文字组件
 */
@Composable
fun ResponsiveSubtitleText(
    text: String,
    priority: TextPriority = TextPriority.SECONDARY,
    fontWeight: FontWeight = FontWeight.Medium,
    maxLines: Int = Int.MAX_VALUE,
    modifier: Modifier = Modifier,
    textAlign: androidx.compose.ui.text.style.TextAlign? = null
) {
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val screenWidthDp = configuration.screenWidthDp
    
    val subtitleFontSize = when {
        screenWidthDp < 360 -> 14.sp
        screenWidthDp < 480 -> 15.sp
        screenWidthDp < 600 -> 16.sp
        else -> 17.sp
    }
    
    ResponsiveText(
        text = text,
        priority = priority,
        fontSize = subtitleFontSize,
        fontWeight = fontWeight,
        maxLines = maxLines,
        modifier = modifier,
        textAlign = textAlign
    )
}

/**
 * 响应式标签文字组件
 */
@Composable
fun ResponsiveLabelText(
    text: String,
    priority: TextPriority = TextPriority.TERTIARY,
    fontWeight: FontWeight = FontWeight.Normal,
    maxLines: Int = Int.MAX_VALUE,
    modifier: Modifier = Modifier,
    textAlign: androidx.compose.ui.text.style.TextAlign? = null
) {
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val screenWidthDp = configuration.screenWidthDp
    
    val labelFontSize = when {
        screenWidthDp < 360 -> 11.sp
        screenWidthDp < 480 -> 12.sp
        screenWidthDp < 600 -> 13.sp
        else -> 14.sp
    }
    
    ResponsiveText(
        text = text,
        priority = priority,
        fontSize = labelFontSize,
        fontWeight = fontWeight,
        maxLines = maxLines,
        modifier = modifier,
        textAlign = textAlign
    )
}
