package com.trackmaster.app

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import java.text.SimpleDateFormat
import java.util.*
import com.trackmaster.app.ui.theme.*

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
    // 防双击关闭
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
            while (currentCoroutineContext().isActive) {
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
                    "统计数据可视化",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = NeonCoral
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
                        Text("📅 选择日期", fontSize = 11.sp, color = Color.White)
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
                            "暂无统计数据",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFFBDBDBD)
                        )
                    }
                } else {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        DurationProgressSection(
                            statistics = statistics,
                            statisticsManager = statisticsManager
                        )

                        LeaderboardSection(
                            statistics = statistics,
                            statisticsManager = statisticsManager
                        )
                    }
                }
            }
        },
        confirmButton = {
            GlassButton(onClick = onDismiss) {
                Text("关闭", color = Color.White)
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
        title = { Text("选择日期", color = NeonCoral) },
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
                        range = yearRange,
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
            GlassButton(onClick = { onDateSelected(year, month, day) }) {
                Text("确定", color = Color.White)
            }
        },
        dismissButton = {
            GlassButton(onClick = onDismiss) {
                Text("取消", color = Color.White)
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
        title = { Text("选择月份", color = NeonCoral) },
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
                        range = yearRange,
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
            GlassButton(onClick = { onMonthSelected(year, month) }) {
                Text("确定", color = Color.White)
            }
        },
        dismissButton = {
            GlassButton(onClick = onDismiss) {
                Text("取消", color = Color.White)
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
        title = { Text("选择年份", color = NeonCoral) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                NumberPicker(
                    label = "年",
                    value = year,
                    range = yearRange,
                    onValueChange = { year = it }
                )
            }
        },
        confirmButton = {
            GlassButton(onClick = { onYearSelected(year) }) {
                Text("确定", color = Color.White)
            }
        },
        dismissButton = {
            GlassButton(onClick = onDismiss) {
                Text("取消", color = Color.White)
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
        // 滑动背景指示器 - 使用 alignment 实现平滑移动
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
                            TimeDimension.DAY -> "日"
                            TimeDimension.MONTH -> "月"
                            TimeDimension.YEAR -> "年"
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
                    "停留时长分布",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = NeonCoral
                )
                Text(
                    "总时长: ${statisticsManager.formatDuration(totalDuration)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFBDBDBD)
                )
            }

            // 使用网格卡片布局
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
                            // 颜色指示器
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
                                        text = statisticsManager.formatDuration(stat.totalDurationSeconds),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color(0xFFBDBDBD)
                                    )
                                }
                            }

                            // 小型进度条
                            Box(
                                modifier = Modifier
                                    .width(60.dp)
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(Color(0x332A221C))
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(percentage / 100f)
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
                "停留时长排行榜",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = NeonCoral
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
