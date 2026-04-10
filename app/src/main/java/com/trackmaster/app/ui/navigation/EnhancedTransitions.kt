package com.trackmaster.app.ui.navigation

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/**
 * 增强型页面过渡动画系统
 * 提供多种过渡效果：滑动、淡入淡出、缩放、组合等
 */

/** 从右侧滑入 */
fun slideInRight(distance: Int = 300): AnimatedContentTransitionScope<*>.() -> EnterTransition = {
    slideInHorizontally(
        initialOffsetX = { distance },
        animationSpec = spring(
            dampingRatio = 0.8f,
            stiffness = 300f
        )
    ) + fadeIn(
        animationSpec = tween(300)
    )
}

/** 向右滑出 */
fun slideOutRight(distance: Int = 300): AnimatedContentTransitionScope<*>.() -> ExitTransition = {
    slideOutHorizontally(
        targetOffsetX = { distance },
        animationSpec = spring(
            dampingRatio = 0.8f,
            stiffness = 300f
        )
    ) + fadeOut(
        animationSpec = tween(200)
    )
}

/** 从左侧滑入 */
fun slideInLeft(distance: Int = 300): AnimatedContentTransitionScope<*>.() -> EnterTransition = {
    slideInHorizontally(
        initialOffsetX = { -distance },
        animationSpec = spring(
            dampingRatio = 0.8f,
            stiffness = 300f
        )
    ) + fadeIn(
        animationSpec = tween(300)
    )
}

/** 向左滑出 */
fun slideOutLeft(distance: Int = 300): AnimatedContentTransitionScope<*>.() -> ExitTransition = {
    slideOutHorizontally(
        targetOffsetX = { -distance },
        animationSpec = spring(
            dampingRatio = 0.8f,
            stiffness = 300f
        )
    ) + fadeOut(
        animationSpec = tween(200)
    )
}

/** 从底部滑入（适合对话框） */
fun slideInFromBottom(): AnimatedContentTransitionScope<*>.() -> EnterTransition = {
    slideInVertically(
        initialOffsetY = { it / 2 },
        animationSpec = spring(
            dampingRatio = 0.7f,
            stiffness = 400f
        )
    ) + fadeIn(
        animationSpec = tween(250)
    )
}

/** 向底部滑出 */
fun slideOutToBottom(): AnimatedContentTransitionScope<*>.() -> ExitTransition = {
    slideOutVertically(
        targetOffsetY = { it / 2 },
        animationSpec = spring(
            dampingRatio = 0.7f,
            stiffness = 400f
        )
    ) + fadeOut(
        animationSpec = tween(200)
    )
}

/** 缩放进入 */
fun scaleIn(): AnimatedContentTransitionScope<*>.() -> EnterTransition = {
    scaleIn(
        initialScale = 0.85f,
        animationSpec = spring(
            dampingRatio = 0.7f,
            stiffness = 400f
        )
    ) + fadeIn(
        animationSpec = tween(300)
    )
}

/** 缩放退出 */
fun scaleOut(): AnimatedContentTransitionScope<*>.() -> ExitTransition = {
    scaleOut(
        targetScale = 0.85f,
        animationSpec = spring(
            dampingRatio = 0.7f,
            stiffness = 400f
        )
    ) + fadeOut(
        animationSpec = tween(200)
    )
}

/** 弹性缩放进入（适合重要内容） */
fun bounceIn(): AnimatedContentTransitionScope<*>.() -> EnterTransition = {
    scaleIn(
        initialScale = 0.5f,
        animationSpec = spring(
            dampingRatio = 0.5f,
            stiffness = 300f
        )
    ) + fadeIn(
        animationSpec = tween(400)
    )
}

/** 通用内容页面过渡 */
val contentEnterTransition: AnimatedContentTransitionScope<*>.() -> EnterTransition = {
    fadeIn(animationSpec = tween(300)) + scaleIn(
        initialScale = 0.95f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 300f)
    )
}

val contentExitTransition: AnimatedContentTransitionScope<*>.() -> ExitTransition = {
    fadeOut(animationSpec = tween(200)) + scaleOut(
        targetScale = 0.95f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 300f)
    )
}

/**
 * 交错动画效果
 * 让列表元素依次进入
 */
@Composable
fun <T> StaggeredFadeSlideIn(
    items: List<T>,
    modifier: Modifier = Modifier,
    delayBetweenItems: Int = 50,
    initialOffsetY: Int = 30,
    content: @Composable (item: T, index: Int, animatedIndex: Int) -> Unit
) {
    items.forEachIndexed { index, item ->
        var visible by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            visible = true
        }

        val alpha by animateFloatAsState(
            targetValue = if (visible) 1f else 0f,
            animationSpec = tween(
                durationMillis = 400,
                delayMillis = index * delayBetweenItems
            ),
            label = "staggerAlpha"
        )

        val offsetY by animateFloatAsState(
            targetValue = if (visible) 0f else initialOffsetY.toFloat(),
            animationSpec = tween(
                durationMillis = 400,
                delayMillis = index * delayBetweenItems
            ),
            label = "staggerOffsetY"
        )

        Box(
            modifier = modifier.graphicsLayer {
                this.alpha = alpha
                this.translationY = offsetY
            }
        ) {
            content(item, index, index)
        }
    }
}

/**
 * 脉冲动画效果（用于强调）
 */
@Composable
fun pulsingAnimation(
    content: @Composable (scale: Float, alpha: Float) -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")

    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    content(scale, alpha)
}

/**
 * 呼吸动画效果（用于状态指示）
 */
@Composable
fun breathingAnimation(
    content: @Composable (scale: Float) -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "breathing")

    val scale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.02f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breathingScale"
    )

    content(scale)
}
