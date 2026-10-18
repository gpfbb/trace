package com.trackmaster.app

import android.graphics.Color as AndroidColor
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.Icons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.trackmaster.app.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import java.text.SimpleDateFormat
import java.util.*
import com.trackmaster.app.utils.AppLog
import com.trackmaster.app.ui.design.currentPalette
import com.trackmaster.app.ui.design.DesignSecondaryButton
import com.trackmaster.app.ui.statistics.DatePickerDialog
import com.trackmaster.app.ui.statistics.MonthPickerDialog
import com.trackmaster.app.ui.statistics.YearPickerDialog
import com.trackmaster.app.ui.statistics.TimeDimensionSelector
import com.trackmaster.app.ui.statistics.FenceTreeSection

val chartColors = listOf(
    Color(0xFF6366F1),
    Color(0xFFEC4899),
    Color(0xFF10B981),
    Color(0xFFF59E0B),
    Color(0xFFEF4444),
    Color(0xFF8B5CF6),
    Color(0xFF06B6D4),
    Color(0xFF84CC16)
)

@Composable
private fun formatDuration(seconds: Long): String {
    val hours = seconds / 3600
    val mins = (seconds % 3600) / 60
    val secs = seconds % 60
    return when {
        hours > 0 -> stringResource(R.string.duration_hours_minutes_seconds, hours, mins, secs)
        mins > 0 -> stringResource(R.string.duration_minutes_seconds, mins, secs)
        else -> stringResource(R.string.duration_less_than_minute)
    }
}

@Composable
fun StatisticsScreen(
    onDismiss: () -> Unit,
    statisticsManager: StatisticsManager
) {
    var dismissHandled by remember { mutableStateOf(false) }
    fun debouncedDismiss() {
        if (dismissHandled) return
        dismissHandled = true
        onDismiss()
    }
    fun safeDismiss() {
        if (dismissHandled) return
        dismissHandled = true
        onDismiss()
    }

    var selectedTimeDimension by remember { mutableStateOf(TimeDimension.DAY) }
    var statistics by remember { mutableStateOf<List<FenceStatistics>>(emptyList()) }
    var statisticsTree by remember { mutableStateOf<List<FenceStatisticTree>>(emptyList()) }
    var expandedFenceIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var isLoading by remember { mutableStateOf(true) }

    var selectedYear by remember { mutableStateOf(Calendar.getInstance().get(Calendar.YEAR)) }
    var selectedMonth by remember { mutableStateOf(Calendar.getInstance().get(Calendar.MONTH)) }
    var selectedDay by remember { mutableStateOf(Calendar.getInstance().get(Calendar.DAY_OF_MONTH)) }

    var showDatePicker by remember { mutableStateOf(false) }
    var showMonthPicker by remember { mutableStateOf(false) }
    var showYearPicker by remember { mutableStateOf(false) }

    fun flattenTreeIds(node: FenceStatisticTree): List<String> {
        return listOf(node.fenceId) + node.children.flatMap { flattenTreeIds(it) }
    }

    LaunchedEffect(statisticsTree) {
        if (statisticsTree.isNotEmpty() && expandedFenceIds.isEmpty()) {
            expandedFenceIds = statisticsTree.flatMap { flattenTreeIds(it) }.toSet()
        }
    }

    val timeRange = remember(selectedTimeDimension, selectedYear, selectedMonth, selectedDay) {
        when (selectedTimeDimension) {
            TimeDimension.DAY -> statisticsManager.getDateRange(selectedYear, selectedMonth, selectedDay)
            TimeDimension.MONTH -> statisticsManager.getMonthRange(selectedYear, selectedMonth)
            TimeDimension.YEAR -> statisticsManager.getYearRange(selectedYear)
        }
    }

    LaunchedEffect(selectedTimeDimension, selectedYear, selectedMonth, selectedDay) {
        isLoading = true
        statistics = statisticsManager.getStatistics(selectedTimeDimension, timeRange)
        // 用刚算出来的统计构建树，避免再查一遍库
        statisticsTree = statisticsManager.buildFenceStatisticTree(statistics)
        isLoading = false

        while (currentCoroutineContext().isActive) {
            // 刷新节奏取决于"有没有正在计时的停留"：
            //
            // - 有活跃会话时，秒数每秒都在涨，30 秒才刷一次会让用户以为
            //   界面卡死了（只能靠切维度触发重新加载）。这种时候按秒刷。
            // - 没有活跃会话时数据不会自己变，按秒刷只是白耗电。
            //
            // 查询本身很轻：一次 daily_stays 范围查询 + 一次 COUNT 聚合，
            // 而且只在统计弹窗打开期间跑。
            val hasLiveSession = statisticsManager.hasActiveSessions()
            delay(if (hasLiveSession) LIVE_REFRESH_INTERVAL_MS else IDLE_REFRESH_INTERVAL_MS)

            if (!currentCoroutineContext().isActive) break

            val now = Calendar.getInstance()
            // 只有"看今天"时数据才会随秒变化；翻到历史日期就没必要刷
            val viewingNow = when (selectedTimeDimension) {
                TimeDimension.DAY ->
                    selectedYear == now.get(Calendar.YEAR) &&
                        selectedMonth == now.get(Calendar.MONTH) &&
                        selectedDay == now.get(Calendar.DAY_OF_MONTH)

                TimeDimension.MONTH ->
                    selectedYear == now.get(Calendar.YEAR) &&
                        selectedMonth == now.get(Calendar.MONTH)

                TimeDimension.YEAR -> selectedYear == now.get(Calendar.YEAR)
            }
            // 看的不是当前这段时间，数据不会变，不用刷
            if (!viewingNow) continue

            val currentTimeRange = when (selectedTimeDimension) {
                TimeDimension.DAY ->
                    statisticsManager.getDateRange(selectedYear, selectedMonth, selectedDay)

                TimeDimension.MONTH ->
                    statisticsManager.getMonthRange(selectedYear, selectedMonth)

                TimeDimension.YEAR -> statisticsManager.getYearRange(selectedYear)
            }
            val newStats = statisticsManager.getStatistics(selectedTimeDimension, currentTimeRange)
            if (newStats.map { it.fenceId to it.totalDurationSeconds } !=
                statistics.map { it.fenceId to it.totalDurationSeconds }
            ) {
                statistics = newStats
                statisticsTree = statisticsManager.buildFenceStatisticTree(newStats)
            }
        }
    }
    
    val dateFormat = SimpleDateFormat(stringResource(R.string.date_format_day), Locale.getDefault())
    val monthFormat = SimpleDateFormat(stringResource(R.string.date_format_month), Locale.getDefault())
    val yearFormat = SimpleDateFormat(stringResource(R.string.date_format_year), Locale.getDefault())
    
    val calendar = Calendar.getInstance()
    calendar.set(Calendar.YEAR, selectedYear)
    calendar.set(Calendar.MONTH, selectedMonth)
    calendar.set(Calendar.DAY_OF_MONTH, selectedDay)
    
    val displayDate = when (selectedTimeDimension) {
        TimeDimension.DAY -> dateFormat.format(calendar.time)
        TimeDimension.MONTH -> monthFormat.format(calendar.time)
        TimeDimension.YEAR -> yearFormat.format(calendar.time)
    }

    com.trackmaster.app.ui.design.AppDialog(
        onDismissRequest = {
            if (!dismissHandled) {
                dismissHandled = true
                onDismiss()
            }
        },
        title = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    stringResource(R.string.statistics_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = currentPalette.accent
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        displayDate,
                        style = MaterialTheme.typography.titleMedium,
                        color = currentPalette.accent
                    )
                    Box(
                        modifier = Modifier
                            .height(32.dp)
                            .clip(RoundedCornerShape(16.dp))
                            // 实心强调色而不是渐变：渐变在浅色模式下对白字对比度不可控
                            .background(currentPalette.accent)
                            .clickable {
                                when (selectedTimeDimension) {
                                    TimeDimension.DAY -> showDatePicker = true
                                    TimeDimension.MONTH -> showMonthPicker = true
                                    TimeDimension.YEAR -> showYearPicker = true
                                }
                            }
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.select_date_button),
                            fontSize = 12.sp,
                            color = currentPalette.onAccent,
                            maxLines = 1
                        )
                    }
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(500.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                TimeDimensionSelector(
                    selectedDimension = selectedTimeDimension,
                    onDimensionSelected = { selectedTimeDimension = it }
                )

                if (isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = currentPalette.accent)
                    }
                } else if (statistics.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            stringResource(R.string.no_statistics_data),
                            style = MaterialTheme.typography.bodyMedium,
                            color = currentPalette.labelSecondary
                        )
                    }
                } else {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        FenceTreeSection(
                            tree = statisticsTree,
                            expandedIds = expandedFenceIds,
                            onToggleExpand = { fenceId ->
                                expandedFenceIds = if (fenceId in expandedFenceIds) {
                                    expandedFenceIds - fenceId
                                } else {
                                    expandedFenceIds + fenceId
                                }
                            },
                            statisticsManager = statisticsManager
                        )
                    }
                }
            }
        },
        confirmButton = {
            com.trackmaster.app.ui.design.DesignSecondaryButton(onClick = onDismiss) {
                Text(stringResource(R.string.close_short), color = currentPalette.accent)
            }
        }
    )
    
    if (showDatePicker) {
        DatePickerDialog(
            initialYear = selectedYear,
            initialMonth = selectedMonth,
            initialDay = selectedDay,
            onDateSelected = { year, month, day ->
                selectedYear = year
                selectedMonth = month
                selectedDay = day
                showDatePicker = false
            },
            onDismiss = { showDatePicker = false }
        )
    }
    
    if (showMonthPicker) {
        MonthPickerDialog(
            initialYear = selectedYear,
            initialMonth = selectedMonth,
            onMonthSelected = { year, month ->
                selectedYear = year
                selectedMonth = month
                showMonthPicker = false
            },
            onDismiss = { showMonthPicker = false }
        )
    }
    
    if (showYearPicker) {
        YearPickerDialog(
            initialYear = selectedYear,
            onYearSelected = { year ->
                selectedYear = year
                showYearPicker = false
            },
            onDismiss = { showYearPicker = false }
        )
    }
}

/** 有正在计时的停留时，按秒刷新，让时长看起来是实时的。 */
private const val LIVE_REFRESH_INTERVAL_MS = 1_000L

/** 没有活跃会话时，数据不会自己变，降低频率避免白耗电。 */
private const val IDLE_REFRESH_INTERVAL_MS = 15_000L
