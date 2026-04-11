package com.trackmaster.app.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.dp
import com.trackmaster.app.ui.theme.*

/**
 * 骨架屏加载动画组件
 * 提供内容加载时的占位动画效果
 */
@Composable
fun ShimmerCard(
    modifier: Modifier = Modifier,
    height: Int = 120,
    cornerRadius: Int = 24
) {
    val shimmerColors = listOf(
        GlassPanelRich.copy(alpha = 0.6f),
        GlassPanelLightRich.copy(alpha = 0.4f),
        GlassPanelRich.copy(alpha = 0.6f)
    )

    val transition = rememberInfiniteTransition(label = "shimmer")
    val translateAnim by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = 1200,
                easing = FastOutSlowInEasing
            ),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerTranslate"
    )

    val brush = Brush.linearGradient(
        colors = shimmerColors,
        start = Offset(translateAnim - 500f, 0f),
        end = Offset(translateAnim, 0f)
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height.dp)
            .clip(RoundedCornerShape(cornerRadius.dp))
            .background(brush)
    )
}

@Composable
fun ShimmerListItem(
    modifier: Modifier = Modifier,
    iconSize: Int = 40,
    textLines: Int = 2
) {
    val shimmerColors = listOf(
        GlassPanelRich.copy(alpha = 0.6f),
        GlassPanelLightRich.copy(alpha = 0.4f),
        GlassPanelRich.copy(alpha = 0.6f)
    )

    val transition = rememberInfiniteTransition(label = "shimmerItem")
    val translateAnim by transition.animateFloat(
        initialValue = 0f,
        targetValue = 800f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = 1000,
                easing = FastOutSlowInEasing
            ),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerTranslateItem"
    )

    val brush = Brush.linearGradient(
        colors = shimmerColors,
        start = Offset(translateAnim - 400f, 0f),
        end = Offset(translateAnim, 0f)
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 图标占位
        Box(
            modifier = Modifier
                .size(iconSize.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(brush)
        )

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            repeat(textLines) { line ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth(if (line == textLines - 1) 0.6f else 1f)
                        .height(12.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(brush)
                )
            }
        }
    }
}

/**
 * 脉冲动画组件（用于状态指示器）
 */
@Composable
fun PulseIndicator(
    modifier: Modifier = Modifier,
    color: Color = NeonCoral,
    size: Int = 8
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")

    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    val scale by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    Box(
        modifier = modifier
            .size(size.dp)
            .scale(scale)
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = alpha))
    )
}

/**
 * 旋转加载指示器
 */
@Composable
fun RotatingLoader(
    modifier: Modifier = Modifier,
    color: Color = NeonCoral,
    size: Int = 40
) {
    val infiniteTransition = rememberInfiniteTransition(label = "rotate")

    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotateAngle"
    )

    Box(
        modifier = modifier
            .size(size.dp)
            .rotate(rotation)
    ) {
        // 创建三段弧形（仅实现一段）
        val sweepAngle1 = 90f

        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
            drawArc(
                color = color.copy(alpha = 0.3f),
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                style = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = 3.dp.toPx(),
                    cap = StrokeCap.Round
                )
            )

            drawArc(
                color = color,
                startAngle = rotation,
                sweepAngle = sweepAngle1,
                useCenter = false,
                style = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = 3.dp.toPx(),
                    cap = StrokeCap.Round
                )
            )
        }
    }
}

/**
 * 弹性加载动画
 */
@Composable
fun BouncingDots(
    modifier: Modifier = Modifier,
    color: Color = NeonCoral,
    dotCount: Int = 3,
    dotSize: Int = 10
) {
    val infiniteTransition = rememberInfiniteTransition(label = "bounce")

    val animations = (0 until dotCount).map { index ->
        infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = 600,
                    delayMillis = index * 150,
                    easing = EaseInOut
                ),
                repeatMode = RepeatMode.Reverse
            ),
            label = "bounce_$index"
        )
    }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(dotCount) { index ->
            Box(
                modifier = Modifier
                    .size(dotSize.dp)
                    .offset(y = (-12 * animations[index].value).dp)
                    .clip(RoundedCornerShape(50))
                    .background(color.copy(alpha = 0.5f + 0.5f * animations[index].value))
            )
        }
    }
}

/**
 * 渐变进度条动画
 */
@Composable
fun AnimatedProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    height: Int = 6,
    backgroundColor: Color = GlassPanelRich,
    foregroundColor: Color = NeonCoral
) {
    val animatedProgress by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = spring(
            dampingRatio = 0.8f,
            stiffness = 100f
        ),
        label = "progress"
    )

    val shimmerColors = listOf(
        foregroundColor.copy(alpha = 0.7f),
        foregroundColor.copy(alpha = 1f),
        foregroundColor.copy(alpha = 0.7f)
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height.dp)
            .clip(RoundedCornerShape(height / 2))
            .background(backgroundColor)
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(animatedProgress)
                .clip(RoundedCornerShape(height / 2))
                .background(
                    Brush.horizontalGradient(shimmerColors)
                )
        )
    }
}

/**
 * 交错列表进入动画
 */
@Composable
fun <T> AnimatedListItem(
    item: T,
    index: Int,
    modifier: Modifier = Modifier,
    content: @Composable (item: T, animatedAlpha: Float, animatedOffsetY: Float) -> Unit
) {
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        visible = true
    }

    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(
            durationMillis = 400,
            delayMillis = (index * 60).coerceAtMost(300)
        ),
        label = "itemAlpha"
    )

    val offsetY by animateFloatAsState(
        targetValue = if (visible) 0f else 30f,
        animationSpec = tween(
            durationMillis = 400,
            delayMillis = (index * 60).coerceAtMost(300)
        ),
        label = "itemOffsetY"
    )

    Box(
        modifier = modifier.graphicsLayer {
            this.alpha = alpha
            this.translationY = offsetY
        }
    ) {
        content(item, alpha, offsetY)
    }
}
