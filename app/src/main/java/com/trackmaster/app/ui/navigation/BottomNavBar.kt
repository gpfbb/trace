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
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trackmaster.app.ui.theme.*

@Composable
fun BottomNavBar(
    currentRoute: String?,
    onNavigate: (Screen) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .height(64.dp)
            .clip(RoundedCornerShape(32.dp))
            .background(GlassPanelRich.copy(alpha = 0.95f))
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        BottomNavItem.entries.forEach { item ->
            val isSelected = currentRoute == item.screen.route
            NavItem(
                icon = item.toIcon(),
                label = item.title,
                isSelected = isSelected,
                onClick = { onNavigate(item.screen) }
            )
        }
    }
}

@Composable
private fun NavItem(
    icon: ImageVector,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val scale by animateFloatAsState(
        targetValue = if (isSelected) 1.1f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 400f),
        label = "iconScale"
    )

    val bgAlpha by animateFloatAsState(
        targetValue = if (isSelected) 0.2f else 0f,
        animationSpec = tween(150),
        label = "bgAlpha"
    )

    val iconColor by animateColorAsState(
        targetValue = if (isSelected) NeonCoral else Color.Gray,
        animationSpec = tween(150),
        label = "iconColor"
    )

    val textColor by animateColorAsState(
        targetValue = if (isSelected) NeonCoral else Color.Gray,
        animationSpec = tween(150),
        label = "textColor"
    )

    val fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal

    Column(
        modifier = Modifier
            .width(72.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (bgAlpha > 0.01f) NeonCoral.copy(alpha = bgAlpha)
                else Color.Transparent
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(vertical = 8.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = iconColor,
            modifier = Modifier
                .size(26.dp)
                .scale(scale)
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            fontSize = 11.sp,
            color = textColor,
            fontWeight = fontWeight
        )
    }
}

private fun BottomNavItem.toIcon(): ImageVector {
    return when (this) {
        BottomNavItem.HOME -> Icons.Filled.Home
        BottomNavItem.MAP -> Icons.Filled.Map
        BottomNavItem.HISTORY -> Icons.AutoMirrored.Filled.List
        BottomNavItem.SETTINGS -> Icons.Filled.Settings
    }
}
