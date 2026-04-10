package com.trackmaster.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.EventNote
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trackmaster.app.data.GeoFenceAction
import com.trackmaster.app.data.GeoFenceEvent
import com.trackmaster.app.TrackMasterApplication
import com.trackmaster.app.ui.theme.GlassTopCard
import com.trackmaster.app.ui.theme.GlassCard
import com.trackmaster.app.ui.theme.Success
import com.trackmaster.app.ui.theme.Danger
import com.trackmaster.app.ui.theme.primaryColor
import com.trackmaster.app.ui.theme.surfaceColor
import com.trackmaster.app.ui.theme.backgroundColor
import com.trackmaster.app.ui.theme.glassPanel
import org.koin.compose.koinInject
import java.text.SimpleDateFormat
import java.util.*

/**
 * 历史记录页面
 *
 * 显示围栏事件的 chronological 时间线视图。
 */
@Composable
fun HistoryScreen(
    @Suppress("UNUSED_PARAMETER") onNavigateBack: () -> Unit
) {
    val application = koinInject<TrackMasterApplication>()
    val events = remember { mutableStateOf<List<GeoFenceEvent>>(emptyList()) }
    var selectedFilter by remember { mutableStateOf<EventFilter>(EventFilter.ALL) }

    LaunchedEffect(Unit) {
        events.value = application.geoFenceEvents
            .sortedByDescending { it.time }
    }

    val filteredEvents = when (selectedFilter) {
        EventFilter.ALL -> events.value
        EventFilter.ENTER -> events.value.filter { it.action == GeoFenceAction.ENTER }
        EventFilter.EXIT -> events.value.filter { it.action == GeoFenceAction.EXIT }
    }

    // 按日期分组
    val groupedEvents = filteredEvents.groupBy { event ->
        SimpleDateFormat("yyyy年MM月dd日", Locale.getDefault()).format(event.time)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(surfaceColor(), backgroundColor())
                )
            )
    ) {
        // ============================================================
        // 顶部栏
        // ============================================================
        GlassTopCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.History,
                        contentDescription = null,
                        tint = primaryColor()
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "历史记录",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = primaryColor()
                    )
                }
                Text(
                    text = "${filteredEvents.size} 条记录",
                    fontSize = 14.sp,
                    color = Color.Gray
                )
            }
        }

        // ============================================================
        // 筛选器
        // ============================================================
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                label = "全部",
                isSelected = selectedFilter == EventFilter.ALL,
                onClick = { selectedFilter = EventFilter.ALL }
            )
            FilterChip(
                label = "进入",
                isSelected = selectedFilter == EventFilter.ENTER,
                onClick = { selectedFilter = EventFilter.ENTER }
            )
            FilterChip(
                label = "离开",
                isSelected = selectedFilter == EventFilter.EXIT,
                onClick = { selectedFilter = EventFilter.EXIT }
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // ============================================================
        // 事件列表
        // ============================================================
        if (filteredEvents.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.EventNote,
                        contentDescription = null,
                        tint = Color.Gray,
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "暂无历史记录",
                        fontSize = 16.sp,
                        color = Color.Gray
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                groupedEvents.forEach { (date, dateEvents) ->
                    item {
                        Text(
                            text = date,
                            fontSize = 14.sp,
                            color = primaryColor(),
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }

                    items(dateEvents) { event ->
                        EventItem(event = event)
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(80.dp))
                }
            }
        }
    }
}

@Composable
private fun EventItem(event: GeoFenceEvent) {
    val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    val isEnter = event.action == GeoFenceAction.ENTER

    GlassCard(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 状态图标
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(20))
                    .background(if (isEnter) Success.copy(alpha = 0.2f) else Danger.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isEnter) Icons.AutoMirrored.Filled.Login else Icons.AutoMirrored.Filled.Logout,
                    contentDescription = null,
                    tint = if (isEnter) Success else Danger,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // 事件信息
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isEnter) "进入" else "离开",
                    fontSize = 12.sp,
                    color = if (isEnter) Success else Danger
                )
                Text(
                    text = event.fenceName,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White
                )
                Text(
                    text = "${event.latitude}, ${event.longitude}",
                    fontSize = 11.sp,
                    color = Color.Gray
                )
            }

            // 时间
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = timeFormat.format(event.time),
                    fontSize = 14.sp,
                    color = Color.White
                )
                Text(
                    text = "精度: ${event.accuracy.toInt()}m",
                    fontSize = 11.sp,
                    color = Color.Gray
                )
            }
        }
    }
}

@Composable
private fun FilterChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
        color = if (isSelected) primaryColor().copy(alpha = 0.2f) else glassPanel()
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            fontSize = 14.sp,
            color = if (isSelected) primaryColor() else Color.Gray
        )
    }
}

private enum class EventFilter {
    ALL, ENTER, EXIT
}
