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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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
import com.trackmaster.app.ui.theme.*
import com.trackmaster.app.utils.AppLog

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

    val isToday by remember {
        derivedStateOf {
            val today = Calendar.getInstance()
            selectedTimeDimension == TimeDimension.DAY &&
                selectedYear == today.get(Calendar.YEAR) &&
                selectedMonth == today.get(Calendar.MONTH) &&
                selectedDay == today.get(Calendar.DAY_OF_MONTH)
        }
    }

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
    }

    LaunchedEffect(selectedTimeDimension, selectedYear, selectedMonth, selectedDay) {
        var lastUpdateTime = 0L
        while (currentCoroutineContext().isActive) {
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

            if (shouldUpdate) {
                val currentTimeRange = when (selectedTimeDimension) {
                    TimeDimension.DAY -> statisticsManager.getDateRange(
                        today.get(Calendar.YEAR),
                        today.get(Calendar.MONTH),
                        today.get(Calendar.DAY_OF_MONTH)
                    )
                    TimeDimension.MONTH -> statisticsManager.getMonthRange(selectedYear, selectedMonth)
                    TimeDimension.YEAR -> statisticsManager.getYearRange(selectedYear)
                }
                val newStatistics = statisticsManager.getStatistics(selectedTimeDimension, currentTimeRange)
                if (newStatistics.map { it.fenceId to it.totalDurationSeconds } !=
                    statistics.map { it.fenceId to it.totalDurationSeconds }) {
                    AppLog.d("StatisticsScreen", "实时更新统计数据: ${newStatistics.size} 项")
                    statistics = newStatistics
                    statisticsTree = statisticsManager.buildFenceStatisticTree(selectedTimeDimension, currentTimeRange)
                }
                lastUpdateTime = now
            }

            delay(1000)
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

    GlassDialog(
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
                    color = primaryColor()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        displayDate,
                        style = MaterialTheme.typography.titleMedium,
                        color = NeonCoral
                    )
                    Box(
                        modifier = Modifier
                            .height(32.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(
                                brush = Brush.linearGradient(
                                    colors = listOf(NeonCoral, NeonAmber),
                                    start = androidx.compose.ui.geometry.Offset(0f, 0f),
                                    end = androidx.compose.ui.geometry.Offset(100f, 100f)
                                )
                            )
                            .clickable {
                                when (selectedTimeDimension) {
                                    TimeDimension.DAY -> showDatePicker = true
                                    TimeDimension.MONTH -> showMonthPicker = true
                                    TimeDimension.YEAR -> showYearPicker = true
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(stringResource(R.string.select_date_button), fontSize = 11.sp, color = Color.White)
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
                        CircularProgressIndicator(color = NeonCoral)
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
                            color = Color(0xFFBDBDBD)
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
            GlassButton(onClick = onDismiss) {
                Text(stringResource(R.string.close_short), color = Color.White)
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

@Composable
fun DatePickerDialog(
    initialYear: Int,
    initialMonth: Int,
    initialDay: Int,
    onDateSelected: (Int, Int, Int) -> Unit,
    onDismiss: () -> Unit
) {
    var year by remember { mutableStateOf(initialYear) }
    var month by remember { mutableStateOf(initialMonth) }
    var day by remember { mutableStateOf(initialDay) }

    val currentYear = Calendar.getInstance().get(Calendar.YEAR)
    val yearRange = 2020..(currentYear + 10)

    val calendar = Calendar.getInstance()
    calendar.set(Calendar.YEAR, year)
    calendar.set(Calendar.MONTH, month)
    val maxDay = calendar.getActualMaximum(Calendar.DAY_OF_MONTH)

    day = remember(day, maxDay) { if (day > maxDay) maxDay else day }
    
    GlassDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.select_date), color = NeonCoral) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    NumberPicker(
                        label = stringResource(R.string.year_label),
                        value = year,
                        range = yearRange,
                        onValueChange = { year = it },
                        modifier = Modifier.weight(1f)
                    )
                    NumberPicker(
                        label = stringResource(R.string.month_label),
                        value = month + 1,
                        range = 1..12,
                        onValueChange = { month = it - 1 },
                        modifier = Modifier.weight(1f)
                    )
                    NumberPicker(
                        label = stringResource(R.string.day_label),
                        value = day,
                        range = 1..maxDay,
                        onValueChange = { day = it },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        },
        confirmButton = {
            GlassButton(onClick = { onDateSelected(year, month, day) }) {
                Text(stringResource(R.string.confirm_short), color = Color.White)
            }
        },
        dismissButton = {
            GlassButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel_short), color = Color.White)
            }
        }
    )
}

@Composable
fun MonthPickerDialog(
    initialYear: Int,
    initialMonth: Int,
    onMonthSelected: (Int, Int) -> Unit,
    onDismiss: () -> Unit
) {
    var year by remember { mutableStateOf(initialYear) }
    var month by remember { mutableStateOf(initialMonth) }

    val currentYear = Calendar.getInstance().get(Calendar.YEAR)
    val yearRange = 2020..(currentYear + 10)
    
    GlassDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.select_month), color = NeonCoral) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    NumberPicker(
                        label = stringResource(R.string.year_label),
                        value = year,
                        range = yearRange,
                        onValueChange = { year = it },
                        modifier = Modifier.weight(1f)
                    )
                    NumberPicker(
                        label = stringResource(R.string.month_label),
                        value = month + 1,
                        range = 1..12,
                        onValueChange = { month = it - 1 },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        },
        confirmButton = {
            GlassButton(onClick = { onMonthSelected(year, month) }) {
                Text(stringResource(R.string.confirm_short), color = Color.White)
            }
        },
        dismissButton = {
            GlassButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel_short), color = Color.White)
            }
        }
    )
}

@Composable
fun YearPickerDialog(
    initialYear: Int,
    onYearSelected: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var year by remember { mutableStateOf(initialYear) }

    val currentYear = Calendar.getInstance().get(Calendar.YEAR)
    val yearRange = 2020..(currentYear + 10)
    
    GlassDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.select_year), color = NeonCoral) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                NumberPicker(
                    label = stringResource(R.string.year_label),
                    value = year,
                    range = yearRange,
                    onValueChange = { year = it }
                )
            }
        },
        confirmButton = {
            GlassButton(onClick = { onYearSelected(year) }) {
                Text(stringResource(R.string.confirm_short), color = Color.White)
            }
        },
        dismissButton = {
            GlassButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel_short), color = Color.White)
            }
        }
    )
}

@Composable
fun NumberPicker(
    label: String,
    value: Int,
    range: IntRange,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = Color(0x4D000000)
        ),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IconButton(
                onClick = {
                    if (value < range.last) {
                        onValueChange(value + 1)
                    }
                },
                enabled = value < range.last
            ) {
                Text("▲", fontSize = 18.sp, color = NeonCoral)
            }
            Text(
                text = value.toString(),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = NeonCoral
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = Color(0xFFBDBDBD)
            )
            IconButton(
                onClick = {
                    if (value > range.first) {
                        onValueChange(value - 1)
                    }
                },
                enabled = value > range.first
            ) {
                Text("▼", fontSize = 18.sp, color = NeonCoral)
            }
        }
    }
}

@Composable
fun TimeDimensionSelector(
    selectedDimension: TimeDimension,
    onDimensionSelected: (TimeDimension) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(
                color = Color(0x4D2A221C),
                shape = RoundedCornerShape(24.dp)
            )
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(1f / 3f)
                .align(
                    when (selectedDimension) {
                        TimeDimension.DAY -> Alignment.CenterStart
                        TimeDimension.MONTH -> Alignment.Center
                        TimeDimension.YEAR -> Alignment.CenterEnd
                    }
                )
                .padding(2.dp)
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(NeonCoral, NeonAmber),
                        start = androidx.compose.ui.geometry.Offset(0f, 0f),
                        end = androidx.compose.ui.geometry.Offset(100f, 100f)
                    ),
                    shape = RoundedCornerShape(22.dp)
                )
        )
        
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TimeDimension.values().forEach { dimension ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable {
                            onDimensionSelected(dimension)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = when (dimension) {
                            TimeDimension.DAY -> stringResource(R.string.day_dimension)
                            TimeDimension.MONTH -> stringResource(R.string.month_dimension)
                            TimeDimension.YEAR -> stringResource(R.string.year_dimension)
                        },
                        color = if (selectedDimension == dimension) 
                            Color.White 
                            else 
                            Color(0xFFBDBDBD),
                        fontWeight = if (selectedDimension == dimension) 
                            FontWeight.Bold 
                            else 
                            FontWeight.Normal
                    )
                }
            }
        }
    }
}

@Composable
fun FenceTreeSection(
    tree: List<FenceStatisticTree>,
    expandedIds: Set<String>,
    onToggleExpand: (String) -> Unit,
    statisticsManager: StatisticsManager
) {
    val totalDuration = tree.sumOf { it.totalDurationSeconds }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = Color(0x4D000000)
        ),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.fence_duration),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = NeonCoral
                )
                Text(
                    stringResource(R.string.total_duration, formatDuration(totalDuration)),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFBDBDBD)
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                tree.forEachIndexed { index, node ->
                    FenceTreeItem(
                        node = node,
                        index = index,
                        expandedIds = expandedIds,
                        onToggleExpand = onToggleExpand,
                        statisticsManager = statisticsManager,
                        totalDuration = totalDuration
                    )
                }
            }
        }
    }
}

@Composable
private fun FenceTreeItem(
    node: FenceStatisticTree,
    index: Int,
    expandedIds: Set<String>,
    onToggleExpand: (String) -> Unit,
    statisticsManager: StatisticsManager,
    totalDuration: Long
) {
    val isExpanded = node.fenceId in expandedIds
    val indentPadding = (node.depth * 16).dp
    val percentage = if (totalDuration > 0) {
        node.totalDurationSeconds * 100f / totalDuration
    } else 0f

    Column {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = indentPadding),
            colors = CardDefaults.cardColors(
                containerColor = if (node.depth == 0) Color(0x332A221C) else Color(0x1A2A221C)
            ),
            shape = RoundedCornerShape(12.dp),
            elevation = CardDefaults.cardElevation(0.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        if (node.children.isNotEmpty()) {
                            onToggleExpand(node.fenceId)
                        }
                    }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (node.children.isNotEmpty()) {
                    Icon(
                        imageVector = if (isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (isExpanded) stringResource(R.string.collapse_short) else stringResource(R.string.expand_short),
                        tint = NeonBlue,
                        modifier = Modifier.size(20.dp)
                    )
                } else {
                    Spacer(Modifier.width(20.dp))
                }

                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(
                            try {
                                Color(AndroidColor.parseColor(node.color))
                            } catch (e: Exception) {
                                // 使用 fenceId 生成稳定的颜色索引，解决递归子节点 index=0 颜色相同问题
                                chartColors[ kotlin.math.abs(node.fenceId.hashCode()) % chartColors.size ]
                            }
                        )
                )

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = node.fenceName,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (node.depth == 0) FontWeight.Bold else FontWeight.Medium,
                            color = Color(0xFFE0E0E0),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (node.children.isNotEmpty()) {
                            Text(
                                text = "(${node.children.size})",
                                style = MaterialTheme.typography.labelSmall,
                                color = NeonBlue
                            )
                        }
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${"%.1f".format(percentage)}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = NeonCoral
                        )
                        Text(
                            text = formatDuration(node.totalDurationSeconds),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFBDBDBD)
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .width(50.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color(0x332A221C))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth((percentage / 100f).coerceIn(0f, 1f))
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(2.dp))
                            .background(
                                try {
                                    Color(AndroidColor.parseColor(node.color))
                                } catch (e: Exception) {
                                    chartColors[ kotlin.math.abs(node.fenceId.hashCode()) % chartColors.size ]
                                }
                            )
                    )
                }
            }
        }

        if (isExpanded && node.children.isNotEmpty()) {
            node.children.forEach { child ->
                FenceTreeItem(
                    node = child,
                    index = 0,
                    expandedIds = expandedIds,
                    onToggleExpand = onToggleExpand,
                    statisticsManager = statisticsManager,
                    totalDuration = node.totalDurationSeconds
                )
            }
        }
    }
}

@Composable
fun DurationProgressSection(
    statistics: List<FenceStatistics>,
    statisticsManager: StatisticsManager
) {
    val totalDuration = statistics.sumOf { it.totalDurationSeconds }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = Color(0x4D000000)
        ),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.duration_distribution),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = NeonCoral
                )
                Text(
                    stringResource(R.string.total_duration, formatDuration(totalDuration)),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFBDBDBD)
                )
            }

            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                statistics.take(6).forEachIndexed { index, stat ->
                    val percentage = if (totalDuration > 0) {
                        stat.totalDurationSeconds * 100f / totalDuration
                    } else 0f

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0x332A221C)
                        ),
                        shape = RoundedCornerShape(12.dp),
                        elevation = CardDefaults.cardElevation(0.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(chartColors[index % chartColors.size])
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(4.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(Color(0x33FFFFFF))
                                ) {
                                    Text(
                                        text = "${index + 1}",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White,
                                        modifier = Modifier.align(Alignment.Center)
                                    )
                                }
                            }

                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = stat.fenceName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = Color(0xFFE0E0E0),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "${"%.1f".format(percentage)}%",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = NeonCoral
                                    )
                                    Text(
                                        text = formatDuration(stat.totalDurationSeconds),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color(0xFFBDBDBD)
                                    )
                                }
                            }

                            Box(
                                modifier = Modifier
                                    .width(60.dp)
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(Color(0x332A221C))
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth((percentage / 100f).coerceIn(0f, 1f))
                                        .fillMaxHeight()
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(chartColors[index % chartColors.size])
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LeaderboardSection(
    statistics: List<FenceStatistics>,
    statisticsManager: StatisticsManager
) {
    val totalDuration = statistics.sumOf { it.totalDurationSeconds }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = Color(0x4D000000)
        ),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                stringResource(R.string.duration_leaderboard),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = NeonCoral
            )
            
            statistics.take(10).forEachIndexed { index, stat ->
                LeaderboardItem(
                    rank = index + 1,
                    name = stat.fenceName,
                    duration = formatDuration(stat.totalDurationSeconds),
                    percentage = if (totalDuration > 0) {
                        "%.1f%%".format(stat.totalDurationSeconds * 100f / totalDuration)
                    } else "0%",
                    isTop3 = index < 3
                )
            }
        }
    }
}

@Composable
fun LeaderboardItem(
    rank: Int,
    name: String,
    duration: String,
    percentage: String,
    isTop3: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isTop3) {
                when (rank) {
                    1 -> NeonCoral.copy(alpha = 0.2f)
                    2 -> NeonAmber.copy(alpha = 0.2f)
                    3 -> Color(0xFF8B5CF6).copy(alpha = 0.2f)
                    else -> Color(0x332A221C)
                }
            } else {
                Color(0x332A221C)
            }
        ),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(if (isTop3) 2.dp else 0.dp)
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(
                        when (rank) {
                            1 -> Color(0xFFFFD700)
                            2 -> Color(0xFFC0C0C0)
                            3 -> Color(0xFFCD7F32)
                            else -> Color(0x4D2A221C)
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = rank.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (rank <= 3) Color.Black else Color(0xFFBDBDBD)
                )
            }
            
            Text(
                text = name,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = Color(0xFFE0E0E0),
                modifier = Modifier.weight(1f)
            )
            
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = duration,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = NeonCoral
                )
                Text(
                    text = percentage,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFBDBDBD)
                )
            }
        }
    }
}
