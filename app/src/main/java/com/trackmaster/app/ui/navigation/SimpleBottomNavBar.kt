package com.trackmaster.app.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.trackmaster.app.R
import com.trackmaster.app.ui.design.AppType
import com.trackmaster.app.ui.design.Space
import com.trackmaster.app.ui.design.currentPalette
import com.trackmaster.app.ui.design.AppIcons

/**
 * 底部标签栏。
 *
 * 设计语言变化：
 * - 原来的浮空胶囊（圆角 30dp、左右 32dp 边距、悬在内容上）改成贴底通栏，
 *   内容区不会被遮挡，也不用在每个屏幕底部手动留 80dp 空白。
 * - 选中态用强调色图标 + 文字，未选中用次级灰。
 * - 选中态用实心图标、未选中用线框图标，比只换颜色更容易一眼分辨。
 */
@Composable
fun SimpleBottomNavBar(
    currentRoute: String?,
    onNavigate: (Screen) -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = currentPalette

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(palette.groupedCell)
    ) {
        // 顶部分割线：在深色背景上分隔内容与标签栏
        Box(
            Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(palette.separator)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(Space.xxxl)
                .padding(horizontal = Space.sm),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            SimpleNavItem.entries.forEach { item ->
                val isSelected = currentRoute == item.screen.route
                val tint = if (isSelected) palette.accent else palette.labelSecondary
                val title = stringResource(item.titleRes)

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(Space.sm))
                        .clickable { onNavigate(item.screen) }
                        .padding(vertical = Space.xs),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = if (isSelected) item.iconSelected else item.iconUnselected,
                        contentDescription = title,
                        tint = tint,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.height(Space.xxs))
                    Text(
                        text = title,
                        style = AppType.caption2,
                        color = tint
                    )
                }
            }
        }
    }
}

private enum class SimpleNavItem(
    val screen: Screen,
    @androidx.annotation.StringRes val titleRes: Int,
    val iconSelected: ImageVector,
    val iconUnselected: ImageVector
) {
    HOME(
        Screen.Home,
        R.string.nav_home,
        AppIcons.navHomeSelected,
        AppIcons.navHomeUnselected
    ),
    MAP(
        Screen.Map,
        R.string.nav_map,
        AppIcons.navMapSelected,
        AppIcons.navMapUnselected
    ),
    HISTORY(
        Screen.History,
        R.string.nav_history,
        AppIcons.navHistorySelected,
        AppIcons.navHistoryUnselected
    ),
    SETTINGS(
        Screen.Settings,
        R.string.nav_settings,
        AppIcons.navSettingsSelected,
        AppIcons.navSettingsUnselected
    )
}
