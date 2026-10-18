package com.trackmaster.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.trackmaster.app.R
import com.trackmaster.app.TrackMasterApplication
import com.trackmaster.app.ui.design.AppType
import com.trackmaster.app.ui.design.Divider
import com.trackmaster.app.ui.design.GroupedSection
import com.trackmaster.app.ui.design.InfoRow
import com.trackmaster.app.ui.design.LargeTitle
import com.trackmaster.app.ui.design.Space
import com.trackmaster.app.ui.design.StatusBadge
import com.trackmaster.app.ui.design.SettingsRow
import com.trackmaster.app.ui.design.currentPalette
import com.trackmaster.app.viewmodel.HomeViewModel
import com.trackmaster.app.ui.design.AppIcons

/**
 * 首页：当前状态 + 今日统计 + 本月常去。
 *
 * 结构是三个分组卡片，每张卡片里放若干行，行之间用分割线。
 * 所有颜色来自 [currentPalette]，不再有 `Color.White` 这类写死值。
 */
@Composable
fun HomeScreen(
    onNavigateToMap: () -> Unit,
    onNavigateToStatistics: () -> Unit,
    homeViewModel: HomeViewModel
) {
    val palette = currentPalette

    val currentFenceName by homeViewModel.currentFenceName.collectAsState()
    val isInsideFence by homeViewModel.isInsideFence.collectAsState()
    val todayEnterCount by homeViewModel.todayEnterCount.collectAsState()
    val todayExitCount by homeViewModel.todayExitCount.collectAsState()
    val monthTopFences by homeViewModel.monthTopFences.collectAsState()

    // 回到前台时刷新一次：停留时长等信息在后台会变
    val context = LocalContext.current
    val app = remember(context) {
        context.applicationContext as TrackMasterApplication
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                app.notifyFenceStateChanged()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(
            start = Space.screenHorizontal,
            end = Space.screenHorizontal,
            top = Space.xl,
            // 给底部导航栏留出空间
            bottom = 96.dp
        ),
        verticalArrangement = Arrangement.spacedBy(Space.sectionGap)
    ) {
        item {
            LargeTitle(text = stringResource(R.string.trackmaster))
        }

        item {
            GroupedSection(title = stringResource(R.string.current_status)) {
                CurrentStatusRow(
                    isInsideFence = isInsideFence,
                    fenceName = currentFenceName
                )
            }
        }

        item {
            GroupedSection(title = stringResource(R.string.today_summary)) {
                InfoRow(
                    label = stringResource(R.string.today_enter),
                    value = stringResource(R.string.times_count, todayEnterCount)
                )
                Divider()
                InfoRow(
                    label = stringResource(R.string.today_exit),
                    value = stringResource(R.string.times_count, todayExitCount)
                )
            }
        }

        item {
            GroupedSection(
                title = stringResource(R.string.monthly_top_fences),
                footer = if (monthTopFences.isEmpty()) {
                    stringResource(R.string.no_data)
                } else {
                    null
                }
            ) {
                if (monthTopFences.isEmpty()) {
                    EmptyRow(stringResource(R.string.no_data))
                } else {
                    monthTopFences.forEachIndexed { index, stats ->
                        if (index > 0) Divider()
                        InfoRow(
                            label = stats.fenceName,
                            value = formatDuration(stats.totalDurationSeconds),
                            valueColor = palette.label
                        )
                    }
                }
            }
        }

        item {
            GroupedSection {
                SettingsRow(
                    title = stringResource(R.string.view_statistics),
                    showChevron = true,
                    onClick = onNavigateToStatistics
                )
                Divider()
                SettingsRow(
                    title = stringResource(R.string.nav_map),
                    showChevron = true,
                    onClick = onNavigateToMap
                )
            }
        }
    }
}

/**
 * 当前状态行：在围栏内显示围栏名 + 状态徽章，否则显示"不在任何围栏内"。
 */
@Composable
private fun CurrentStatusRow(
    isInsideFence: Boolean,
    fenceName: String?
) {
    val palette = currentPalette

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.lg, vertical = Space.lg),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (isInsideFence) AppIcons.insideFence else AppIcons.outsideFence,
            contentDescription = null,
            tint = if (isInsideFence) palette.success else palette.labelTertiary,
            modifier = Modifier.size(28.dp)
        )
        Spacer(Modifier.size(Space.md))

        Column(Modifier.weight(1f)) {
            Text(
                text = if (isInsideFence && fenceName != null) {
                    fenceName
                } else {
                    stringResource(R.string.not_inside_any_fence)
                },
                style = AppType.title2,
                color = palette.label,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(Space.xs))
            Text(
                text = if (isInsideFence) {
                    stringResource(R.string.monitoring_active)
                } else {
                    stringResource(R.string.current_status)
                },
                style = AppType.footnote,
                color = palette.labelSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (isInsideFence) {
            Spacer(Modifier.size(Space.sm))
            StatusBadge(
                text = stringResource(R.string.monitoring_active),
                color = palette.success
            )
        }
    }
}

/** 分组卡片里的空状态行。 */
@Composable
private fun EmptyRow(text: String) {
    val palette = currentPalette
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.lg, vertical = Space.xl),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = text, style = AppType.body, color = palette.labelSecondary)
    }
}

/**
 * 时长格式化。
 *
 * 注意：项目里另有两处同样的实现（StatisticsManager.formatDuration、
 * CategoryManagerScreen）。阶段 3 收尾时会统一，这里先保持行为一致。
 */
@Composable
private fun formatDuration(seconds: Long): String {
    val hours = seconds / 3600
    val mins = (seconds % 3600) / 60
    return when {
        hours > 0 -> stringResource(R.string.duration_hours_minutes, hours, mins)
        mins > 0 -> stringResource(R.string.duration_minutes, mins)
        else -> stringResource(R.string.duration_less_than_minute)
    }
}
