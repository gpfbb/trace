package com.trackmaster.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trackmaster.app.Category
import com.trackmaster.app.GeoFence
import com.trackmaster.app.GeoFenceDatabaseHelper
import com.trackmaster.app.GeoFenceEvent
import java.text.SimpleDateFormat
import java.util.*

/**
 * 停留时间线屏幕 — 水平时间线，按分类着色
 *
 * 查询当日事件 → 按时间排序 → 显示进入/离开标记
 */
@Composable
fun TimelineScreen(
    dbHelper: GeoFenceDatabaseHelper,
    fences: List<GeoFence>,
    categories: List<Category>,
    modifier: Modifier = Modifier,
) {
    var selectedDate by remember { mutableStateOf(todayKey()) }
    var events by remember { mutableStateOf<List<GeoFenceEvent>>(emptyList()) }

    LaunchedEffect(selectedDate) {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val start = sdf.parse(selectedDate) ?: Date()
        val cal = Calendar.getInstance()
        cal.time = start
        cal.add(Calendar.DAY_OF_MONTH, 1)
        val end = cal.time
        events = dbHelper.getEventsByTimeRange(start, end).sortedBy { it.time.time }
    }

    Column(modifier = modifier.padding(16.dp)) {
        // 日期选择
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TextButton(onClick = { selectedDate = shiftDate(selectedDate, -1) }) {
                Text("< 前一天")
            }
            Text(
                text = selectedDate,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            TextButton(onClick = { selectedDate = shiftDate(selectedDate, 1) }) {
                Text("后一天 >")
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (events.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "当日无事件记录",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            // 分类颜色映射
            val categoryColorMap = remember(categories) {
                val colors = listOf(
                    Color(0xFF42A5F5), Color(0xFF66BB6A), Color(0xFFFFA726),
                    Color(0xFFEF5350), Color(0xFFAB47BC), Color(0xFF26C6DA),
                    Color(0xFFFFCA28), Color(0xFF8D6E63)
                )
                categories.mapIndexed { i, cat -> cat.id to colors[i % colors.size] }.toMap()
            }

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(events) { event ->
                    EventTimelineItem(
                        event = event,
                        fence = fences.find { it.id == event.fenceId },
                        categoryColor = event.fenceId.let { fid ->
                            val fence = fences.find { it.id == fid }
                            val catId = fence?.categoryId
                            if (catId != null && catId != Category.UNASSIGNED_ID)
                                categoryColorMap[catId] ?: Color.Gray
                            else Color.Gray
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun EventTimelineItem(
    event: GeoFenceEvent,
    fence: GeoFence?,
    categoryColor: Color,
) {
    val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(event.time)
    val actionText = when (event.action.name) {
        "ENTER" -> "进入"
        "EXIT" -> "离开"
        "STAY" -> "停留"
        else -> event.action.name
    }
    val actionColor = when (event.action.name) {
        "ENTER" -> Color(0xFF4CAF50)
        "EXIT" -> Color(0xFFF44336)
        else -> Color(0xFFFF9800)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top
    ) {
        // 时间列
        Text(
            text = timeStr,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.width(70.dp),
            fontSize = 11.sp
        )

        // 时间线圆点 + 线
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(30.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .background(actionColor, CircleShape)
            )
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .height(40.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant)
            )
        }

        // 事件信息
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 分类色标
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(categoryColor, RoundedCornerShape(2.dp))
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = actionText,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = actionColor
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = fence?.name ?: event.fenceName,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (event.accuracy > 0) {
                Text(
                    text = "精度: ${String.format("%.1f", event.accuracy)}m",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun todayKey(): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

private fun shiftDate(dateKey: String, days: Int): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    val cal = Calendar.getInstance()
    cal.time = sdf.parse(dateKey) ?: Date()
    cal.add(Calendar.DAY_OF_MONTH, days)
    return sdf.format(cal.time)
}
