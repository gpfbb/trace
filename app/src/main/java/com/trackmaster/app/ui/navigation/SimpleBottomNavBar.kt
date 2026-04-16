package com.trackmaster.app.ui.navigation

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import com.trackmaster.app.ui.navigation.Screen
import com.trackmaster.app.R
import com.trackmaster.app.ui.theme.glassPanel
import com.trackmaster.app.ui.theme.primaryColor

/**
 * 简洁版底部导航栏 - 避免动画卡顿
 */
@Composable
fun SimpleBottomNavBar(
    currentRoute: String?,
    onNavigate: (Screen) -> Unit,
    modifier: Modifier = Modifier
) {
    val primaryColor = primaryColor()
    val panelColor = glassPanel()

    Row(
        modifier = modifier
            .height(60.dp)
            .clip(RoundedCornerShape(30.dp))
            .background(panelColor),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        SimpleNavItem.entries.forEach { item ->
            val isSelected = currentRoute == item.screen.route
            val iconColor by animateColorAsState(
                targetValue = if (isSelected) primaryColor else Color.Gray,
                animationSpec = tween(150),
                label = "iconColor"
            )
            val textColor by animateColorAsState(
                targetValue = if (isSelected) primaryColor else Color.Gray,
                animationSpec = tween(150),
                label = "textColor"
            )
            val resolvedTitle = stringResource(item.titleRes)

            Column(
                modifier = Modifier
                    .width(64.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onNavigate(item.screen) }
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = item.icon,
                    contentDescription = resolvedTitle,
                    tint = iconColor,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = resolvedTitle,
                    fontSize = 10.sp,
                    color = textColor,
                    fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal
                )
            }
        }
    }
}

private enum class SimpleNavItem(
    val screen: Screen,
    @androidx.annotation.StringRes val titleRes: Int,
    val icon: ImageVector
) {
    HOME(Screen.Home, R.string.nav_home, Icons.Filled.Home),
    MAP(Screen.Map, R.string.nav_map, Icons.Filled.Map),
    HISTORY(Screen.History, R.string.nav_history, Icons.AutoMirrored.Filled.List),
    SETTINGS(Screen.Settings, R.string.nav_settings, Icons.Filled.Settings)
}
