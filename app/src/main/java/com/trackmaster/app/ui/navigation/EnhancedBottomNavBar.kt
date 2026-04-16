package com.trackmaster.app.ui.navigation

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trackmaster.app.R
import com.trackmaster.app.ui.theme.*

@Composable
fun EnhancedBottomNavBar(
    currentRoute: String?,
    onNavigate: (Screen) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .height(72.dp)
            .clip(RoundedCornerShape(36.dp))
            .background(GlassPanelRich.copy(alpha = 0.98f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        EnhancedBottomNavItem.entries.forEach { item ->
            val isSelected = currentRoute == item.screen.route
            val resolvedLabel = stringResource(item.titleRes)
            EnhancedNavItem(
                icon = item.toIcon(),
                label = resolvedLabel,
                isSelected = isSelected,
                onClick = { onNavigate(item.screen) }
            )
        }
    }
}

@Composable
private fun EnhancedNavItem(
    icon: ImageVector,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val scale by animateFloatAsState(
        targetValue = if (isSelected) 1.15f else 1f,
        animationSpec = spring(
            dampingRatio = 0.6f,
            stiffness = 400f
        ),
        label = "navScale"
    )

    val bgAlpha by animateFloatAsState(
        targetValue = if (isSelected) 0.25f else 0f,
        animationSpec = tween(200),
        label = "navBgAlpha"
    )

    val glowAlpha by animateFloatAsState(
        targetValue = if (isSelected) 0.6f else 0f,
        animationSpec = tween(300),
        label = "navGlowAlpha"
    )

    val iconColor by animateColorAsState(
        targetValue = if (isSelected) NeonCoral else Color.Gray,
        animationSpec = tween(200),
        label = "navIconColor"
    )

    val textColor by animateColorAsState(
        targetValue = if (isSelected) NeonCoral else Color.Gray,
        animationSpec = tween(200),
        label = "navTextColor"
    )

    val fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal

    val offsetY by animateFloatAsState(
        targetValue = if (isSelected) -2f else 0f,
        animationSpec = spring(
            dampingRatio = 0.7f,
            stiffness = 500f
        ),
        label = "navOffsetY"
    )

    Column(
        modifier = Modifier
            .width(72.dp)
            .offset(y = offsetY.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier.size(40.dp),
            contentAlignment = Alignment.Center
        ) {
            if (glowAlpha > 0.01f) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .blur(8.dp)
                        .background(
                            color = NeonCoral.copy(alpha = glowAlpha * 0.5f),
                            shape = RoundedCornerShape(12.dp)
                        )
                )
            }

            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(
                        color = NeonCoral.copy(alpha = bgAlpha),
                        shape = RoundedCornerShape(12.dp)
                    )
            )

            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = iconColor,
                modifier = Modifier
                    .size(24.dp)
                    .scale(scale)
            )
        }

        Spacer(modifier = Modifier.height(2.dp))

        Text(
            text = label,
            fontSize = 10.sp,
            color = textColor,
            fontWeight = fontWeight
        )
    }
}

private enum class EnhancedBottomNavItem(
    val screen: Screen,
    @androidx.annotation.StringRes val titleRes: Int
) {
    HOME(Screen.Home, R.string.nav_home),
    MAP(Screen.Map, R.string.nav_map),
    HISTORY(Screen.History, R.string.nav_history),
    SETTINGS(Screen.Settings, R.string.nav_settings)
}

private fun EnhancedBottomNavItem.toIcon(): ImageVector = when (this) {
    EnhancedBottomNavItem.HOME -> Icons.Filled.Home
    EnhancedBottomNavItem.MAP -> Icons.Filled.Map
    EnhancedBottomNavItem.HISTORY -> Icons.AutoMirrored.Filled.List
    EnhancedBottomNavItem.SETTINGS -> Icons.Filled.Settings
}
