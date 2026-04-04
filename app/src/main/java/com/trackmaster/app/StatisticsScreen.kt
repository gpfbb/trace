package com.trackmaster.app

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*

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
fun StatisticsScreen(
    onDismiss: () -> Unit,
    statisticsManager: StatisticsManager
) {
    var selectedTimeDimension by remember { mutableStateOf(TimeDimension.DAY) }
    var statistics by remember { mutableStateOf<List<FenceStatistics>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    
    var selectedYear by remember { mutableStateOf(Calendar.getInstance().get(Calendar.YEAR)) }
    var selectedMonth by remember { mutableStateOf(Calendar.getInstance().get(Calendar.MONTH)) }
    var selectedDay by remember { mutableStateOf(Calendar.getInstance().get(Calendar.DAY_OF_MONTH)) }
    
    var showDatePicker by remember { mutableStateOf(false) }
    var showMonthPicker by remember { mutableStateOf(false) }
    var showYearPicker by remember { mutableStateOf(false) }
    
    val isToday = remember(selectedTimeDimension, selectedYear, selectedMonth, selectedDay) {
        val today = Calendar.getInstance()
        selectedTimeDimension == TimeDimension.DAY &&
            selectedYear == today.get(Calendar.YEAR) &&
            selectedMonth == today.get(Calendar.MONTH) &&
            selectedDay == today.get(Calendar.DAY_OF_MONTH)
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
        isLoading = false
    }
    
    LaunchedEffect(isToday, selectedTimeDimension, timeRange) {
        if (isToday) {
            while (true) {
                delay(1000)
                val newStatistics = statisticsManager.getStatistics(selectedTimeDimension, timeRange)
                if (newStatistics != statistics) {
                    statistics = newStatistics
                }
            }
        }
    }
    
    val dateFormat = SimpleDateFormat("yyyy年MM月dd日", Locale.getDefault())
    val monthFormat = SimpleDateFormat("yyyy年MM月", Locale.getDefault())
    val yearFormat = SimpleDateFormat("yyyy年", Locale.getDefault())
    
    val calendar = Calendar.getInstance()
    calendar.set(Calendar.YEAR, selectedYear)
    calendar.set(Calendar.MONTH, selectedMonth)
    calendar.set(Calendar.DAY_OF_MONTH, selectedDay)
    
    val displayDate = when (selectedTimeDimension) {
        TimeDimension.DAY -> dateFormat.format(calendar.time)
        TimeDimension.MONTH -> monthFormat.format(calendar.time)
        TimeDimension.YEAR -> yearFormat.format(calendar.time)
    }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "统计数据可视化",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        displayDate,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Button(
                        onClick = {
                            when (selectedTimeDimension) {
                                TimeDimension.DAY -> showDatePicker = true
                                TimeDimension.MONTH -> showMonthPicker = true
                                TimeDimension.YEAR -> showYearPicker = true
                            }
                        }
                    ) {
                        Text("选择日期")
                    }
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 500.dp)
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
                        CircularProgressIndicator()
                    }
                } else if (statistics.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "暂无统计数据",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    PieChartSection(
                        statistics = statistics
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    BarChartSection(
                        statistics = statistics,
                        statisticsManager = statisticsManager
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    LeaderboardSection(
                        statistics = statistics,
                        statisticsManager = statisticsManager
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("关闭")
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
    
    val calendar = Calendar.getInstance()
    calendar.set(Calendar.YEAR, year)
    calendar.set(Calendar.MONTH, month)
    val maxDay = calendar.getActualMaximum(Calendar.DAY_OF_MONTH)
    
    if (day > maxDay) {
        day = maxDay
    }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择日期") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    NumberPicker(
                        label = "年",
                        value = year,
                        range = 2020..2030,
                        onValueChange = { year = it },
                        modifier = Modifier.weight(1f)
                    )
                    NumberPicker(
                        label = "月",
                        value = month + 1,
                        range = 1..12,
                        onValueChange = { month = it - 1 },
                        modifier = Modifier.weight(1f)
                    )
                    NumberPicker(
                        label = "日",
                        value = day,
                        range = 1..maxDay,
                        onValueChange = { day = it },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { onDateSelected(year, month, day) }) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
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
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择月份") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    NumberPicker(
                        label = "年",
                        value = year,
                        range = 2020..2030,
                        onValueChange = { year = it },
                        modifier = Modifier.weight(1f)
                    )
                    NumberPicker(
                        label = "月",
                        value = month + 1,
                        range = 1..12,
                        onValueChange = { month = it - 1 },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { onMonthSelected(year, month) }) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
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
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择年份") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                NumberPicker(
                    label = "年",
                    value = year,
                    range = 2020..2030,
                    onValueChange = { year = it }
                )
            }
        },
        confirmButton = {
            Button(onClick = { onYearSelected(year) }) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
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
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
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
                Text("▲", fontSize = 18.sp)
            }
            Text(
                text = value.toString(),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium
            )
            IconButton(
                onClick = {
                    if (value > range.first) {
                        onValueChange(value - 1)
                    }
                },
                enabled = value > range.first
            ) {
                Text("▼", fontSize = 18.sp)
            }
        }
    }
}

@Composable
fun TimeDimensionSelector(
    selectedDimension: TimeDimension,
    onDimensionSelected: (TimeDimension) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "时间维度",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TimeDimension.values().forEach { dimension ->
                    FilterChip(
                        selected = selectedDimension == dimension,
                        onClick = { onDimensionSelected(dimension) },
                        label = {
                            Text(
                                text = when (dimension) {
                                    TimeDimension.DAY -> "日"
                                    TimeDimension.MONTH -> "月"
                                    TimeDimension.YEAR -> "年"
                                }
                            )
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
fun PieChartSection(
    statistics: List<FenceStatistics>
) {
    val totalDuration = statistics.sumOf { it.totalDurationSeconds }
    val chartData = statistics.take(8).mapIndexed { index, stat ->
        ChartData(
            label = stat.fenceName,
            value = stat.totalDurationSeconds,
            color = chartColors[index % chartColors.size]
        )
    }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "停留时长占比",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                PieChart(
                    data = chartData,
                    modifier = Modifier.size(150.dp)
                )
                
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    chartData.forEach { data ->
                        LegendItem(
                            color = data.color,
                            label = data.label,
                            percentage = if (totalDuration > 0) {
                                "%.1f%%".format(data.value * 100f / totalDuration)
                            } else "0%"
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PieChart(
    data: List<ChartData>,
    modifier: Modifier = Modifier
) {
    val total = data.sumOf { it.value }
    val surfaceColor = MaterialTheme.colorScheme.surface
    var startAngle = -90f
    
    Canvas(modifier = modifier) {
        if (total > 0) {
            data.forEach { item ->
                val sweepAngle = (item.value * 360f / total).toFloat()
                drawArc(
                    color = item.color,
                    startAngle = startAngle,
                    sweepAngle = sweepAngle,
                    useCenter = true,
                    size = Size(size.width, size.height)
                )
                startAngle += sweepAngle
            }
            
            drawCircle(
                color = surfaceColor,
                radius = size.minDimension * 0.3f,
                center = Offset(size.width / 2, size.height / 2)
            )
        }
    }
}

@Composable
fun LegendItem(
    color: Color,
    label: String,
    percentage: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(color)
        )
        Text(
            text = label.take(8) + if (label.length > 8) "…" else "",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = percentage,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun BarChartSection(
    statistics: List<FenceStatistics>,
    statisticsManager: StatisticsManager
) {
    val maxDuration = statistics.maxOfOrNull { it.totalDurationSeconds } ?: 1L
    val chartData = statistics.take(6)
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "停留时长对比",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                chartData.forEachIndexed { index, stat ->
                    val heightRatio = (stat.totalDurationSeconds.toFloat() / maxDuration).coerceAtLeast(0.05f)
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = statisticsManager.formatDuration(stat.totalDurationSeconds),
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 9.sp
                        )
                        Box(
                            modifier = Modifier
                                .width(30.dp)
                                .fillMaxHeight(heightRatio)
                                .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                .background(chartColors[index % chartColors.size])
                        )
                        Text(
                            text = stat.fenceName.take(4),
                            style = MaterialTheme.typography.labelSmall
                        )
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
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "停留时长排行榜",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            
            statistics.take(10).forEachIndexed { index, stat ->
                LeaderboardItem(
                    rank = index + 1,
                    name = stat.fenceName,
                    duration = statisticsManager.formatDuration(stat.totalDurationSeconds),
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
                    1 -> MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                    2 -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.2f)
                    3 -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.2f)
                    else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                }
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            }
        ),
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
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = rank.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (rank <= 3) Color.Black else MaterialTheme.colorScheme.onSurface
                )
            }
            
            Text(
                text = name,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = duration,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = percentage,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
