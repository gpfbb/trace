package com.trackmaster.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trackmaster.app.data.GeoFenceAction
import com.trackmaster.app.LocationServiceState
import com.trackmaster.app.TrackMasterApplication
import com.trackmaster.app.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun HomeScreen(
    onNavigateToMap: () -> Unit,
    onNavigateToStatistics: () -> Unit
) {
    val context = LocalContext.current
    val app = remember { context.applicationContext as TrackMasterApplication }

    var currentFenceName by remember { mutableStateOf<String?>(null) }
    var isInsideFence by remember { mutableStateOf(false) }
    var todayEnterCount by remember { mutableStateOf(0) }
    var todayExitCount by remember { mutableStateOf(0) }
    var monthTopFences by remember { mutableStateOf<List<Pair<String, Long>>>(emptyList()) }

    DisposableEffect(Unit) {
        val service = app.getLocationService()
        var listener: ((LocationServiceState) -> Unit)? = null
        if (service != null) {
            listener = { state ->
                isInsideFence = state.isInsideFence
                currentFenceName = state.currentFenceName
            }
            service.addStateListener(listener)
        }
        onDispose {
            if (service != null && listener != null) {
                service.removeStateListener(listener)
            }
        }
    }

    val fenceStateVersion by app.fenceStateVersion.collectAsState()
    LaunchedEffect(fenceStateVersion) {
        val matched = app.currentMatchedFences
        isInsideFence = matched.isNotEmpty()
        currentFenceName = matched.firstOrNull()?.name
    }

    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                val today = Calendar.getInstance()
                val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                val todayStr = dateFormat.format(today.time)
                val todayEvents = app.geoFenceEvents.filter { dateFormat.format(it.time).startsWith(todayStr) }
                todayEnterCount = todayEvents.count { it.action == GeoFenceAction.ENTER }
                todayExitCount = todayEvents.count { it.action == GeoFenceAction.EXIT }

                val cal = Calendar.getInstance()
                cal.set(Calendar.DAY_OF_MONTH, 1)
                val monthStart = cal.time
                val allRecords = app.dbHelper.getAllStayRecords()
                val monthRecords = allRecords.filter { it.enterTime >= monthStart }
                val top = monthRecords
                    .groupBy { it.fenceName }
                    .mapValues { (_, recs) -> recs.sumOf { it.durationSeconds ?: 0L } }
                    .entries
                    .sortedByDescending { it.value }
                    .take(3)
                    .map { it.key to it.value }
                monthTopFences = top

                val matched = app.currentMatchedFences
                isInsideFence = matched.isNotEmpty()
                currentFenceName = matched.firstOrNull()?.name
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LazyColumn(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(surfaceColor(), backgroundColor())))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth()) {
                Text("TrackMaster", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = primaryColor())
            }
        }

        item {
            GlassCard(Modifier.fillMaxWidth()) {
                Column {
                    Text("当前状态", fontSize = 14.sp, color = primaryColor())
                    Spacer(Modifier.height(8.dp))
                    if (isInsideFence && currentFenceName != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.LocationOn, contentDescription = null, tint = Success, modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text("正在 $currentFenceName", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                Text("实时监测中...", fontSize = 16.sp, color = Color.White.copy(alpha = 0.7f))
                            }
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.LocationOff, contentDescription = null, tint = Warning, modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("未在任何围栏内", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }
                }
            }
        }

        item {
            Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(12.dp)) {
                StatsCard(title = "今日进入", value = "$todayEnterCount 次", icon = Icons.AutoMirrored.Filled.Login, color = Success, modifier = Modifier.weight(1f))
                StatsCard(title = "今日离开", value = "$todayExitCount 次", icon = Icons.AutoMirrored.Filled.Logout, color = Danger, modifier = Modifier.weight(1f))
            }
        }

        item {
            GlassCard(Modifier.fillMaxWidth()) {
                Column {
                    Text("本月停留时长 TOP 3", fontSize = 14.sp, color = primaryColor())
                    Spacer(Modifier.height(12.dp))
                    if (monthTopFences.isEmpty()) {
                        Text("暂无数据", fontSize = 14.sp, color = Color.White.copy(alpha = 0.5f))
                    } else {
                        monthTopFences.forEachIndexed { idx, pair ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                Arrangement.SpaceBetween, Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("${idx + 1}.", fontSize = 14.sp, color = if (idx == 0) NeonAmber else Color.Gray, fontWeight = FontWeight.Bold)
                                    Spacer(Modifier.width(8.dp))
                                    Text(pair.first, fontSize = 14.sp, color = Color.White)
                                }
                                Text(formatDuration(pair.second), fontSize = 14.sp, color = primaryColor())
                            }
                        }
                    }
                }
            }
        }

        item {
            Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(12.dp)) {
                GlassButton(onClick = onNavigateToMap, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Map, contentDescription = null, tint = Color.White)
                    Spacer(Modifier.width(8.dp))
                    Text("查看地图", color = Color.White)
                }
                GlassButton(onClick = onNavigateToStatistics, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.BarChart, contentDescription = null, tint = Color.White)
                    Spacer(Modifier.width(8.dp))
                    Text("查看统计", color = Color.White)
                }
            }
        }

        item { Spacer(Modifier.height(80.dp)) }
    }
}

private fun formatDuration(seconds: Long): String {
    val hours = seconds / 3600
    val mins = (seconds % 3600) / 60
    return when {
        hours > 0 -> "${hours}小时${mins}分"
        mins > 0 -> "${mins}分钟"
        else -> "< 1分钟"
    }
}

@Composable
private fun StatsCard(
    title: String,
    value: String,
    icon: ImageVector,
    color: Color,
    modifier: Modifier = Modifier
) {
    GlassCard(modifier) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(8.dp))
            Text(title, fontSize = 12.sp, color = Color.White.copy(alpha = 0.7f))
            Spacer(Modifier.height(4.dp))
            Text(value, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = color)
        }
    }
}
