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
        statisticsTree = statisticsManager.buildFenceStatisticTree(selectedTimeDimension, timeRange)
        isLoading = false

        var lastUpdateTime = 0L
        while (currentCoroutineContext().isActive) {
            delay(30_000)
            val now = System.currentTimeMillis()
            val today = Calendar.getInstance()
            val isTodayNow = selectedTimeDimension == TimeDimension.DAY &&
                    selectedYear == today.get(Calendar.YEAR) &&
                    selectedMonth == today.get(Calendar.MONTH) &&
                    selectedDay == today.get(Calendar.DAY_OF_MONTH)

            val shouldUpdate = when (selectedTimeDimension) {
                TimeDimension.DAY -> isTodayNow
                TimeDimension.MONTH, TimeDimension.YEAR -> now - lastUpdateTime >= 10_000
            }
            if (!shouldUpdate || !currentCoroutineContext().isActive) continue
            val currentTimeRange = when (selectedTimeDimension) {
                TimeDimension.DAY -> statisticsManager.getDateRange(
                    today.get(Calendar.YEAR), today.get(Calendar.MONTH), today.get(Calendar.DAY_OF_MONTH))
                TimeDimension.MONTH -> statisticsManager.getMonthRange(selectedYear, selectedMonth)
                TimeDimension.YEAR -> statisticsManager.getYearRange(selectedYear)
            }
            val newStats = statisticsManager.getStatistics(selectedTimeDimension, currentTimeRange)
            if (newStats.map { it.fenceId to it.totalDurationSeconds } !=
                statistics.map { it.fenceId to it.totalDurationSeconds }) {
                statistics = newStats
                statisticsTree = statisticsManager.buildFenceStatisticTree(selectedTimeDimension, currentTimeRange)
            }
            lastUpdateTime = now
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
