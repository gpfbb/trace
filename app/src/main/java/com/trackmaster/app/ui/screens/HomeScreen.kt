package com.trackmaster.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trackmaster.app.R
import com.trackmaster.app.data.GeoFenceAction
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

    val fenceStateVersion by app.fenceStateVersion.collectAsState()

    // 【关键修复】fenceStateVersion 变化时立即更新当前围栏状态和热门围栏
    // 确保灵动岛/首页的"当前所在围栏"和"本月热门围栏"能实时反映围栏状态变化
    LaunchedEffect(fenceStateVersion) {
        val matched = app.currentMatchedFences
        isInsideFence = matched.isNotEmpty()
        currentFenceName = matched.firstOrNull()?.name

        // 更新热门围栏数据（包含 activeSessions 的实时时长）
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val cal = Calendar.getInstance()
        cal.set(Calendar.DAY_OF_MONTH, 1)
        val allDailyRecords = mutableListOf<com.trackmaster.app.DailyStayRecord>()
        val nowCal = Calendar.getInstance()
        while (!cal.after(nowCal)) {
            val dateKey = dateFormat.format(cal.time)
            allDailyRecords.addAll(app.dbHelper.getDailyStayRecordsByDate(dateKey))
            cal.add(Calendar.DAY_OF_MONTH, 1)
        }

        val dailyDurations = allDailyRecords
            .groupBy { it.fenceName }
            .mapValues { (_, recs) -> recs.sumOf { it.totalDurationSeconds } }
            .toMutableMap()

        val activeSessions = app.stayDurationManager.activeSessions
        for ((fenceId, session) in activeSessions) {
            // getCurrentDuration 已经包含 accumulatedDuration，不需要再相加
            val currentDuration = app.stayDurationManager.getCurrentDuration(fenceId) ?: 0L
            if (currentDuration > 0) {
                val currentName = dailyDurations.keys.find { it == session.fenceName } ?: session.fenceName
                dailyDurations[currentName] = (dailyDurations[currentName] ?: 0L) + currentDuration
            }
        }

        val top = dailyDurations
            .entries
            .sortedByDescending { it.value }
            .take(3)
            .map { it.key to it.value }
        monthTopFences = top
    }

    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    val lifecycle = lifecycleOwner.lifecycle
    DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                val today = Calendar.getInstance()
                val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                val todayStr = dateFormat.format(today.time)
                val todayEvents = app.geoFenceEvents.filter { dateFormat.format(it.time).startsWith(todayStr) }
                todayEnterCount = todayEvents.count { it.action == GeoFenceAction.ENTER }
                todayExitCount = todayEvents.count { it.action == GeoFenceAction.EXIT }
                app.notifyFenceStateChanged()

                // notifyFenceStateChanged() 会触发 LaunchedEffect(fenceStateVersion) 更新状态
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
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
                Text(stringResource(R.string.trackmaster), fontSize = 28.sp, fontWeight = FontWeight.Bold, color = primaryColor())
            }
        }

        item {
            GlassCard(Modifier.fillMaxWidth()) {
                Column {
                    Text(stringResource(R.string.current_status), fontSize = 14.sp, color = primaryColor())
                    Spacer(Modifier.height(8.dp))
                    if (isInsideFence && currentFenceName != null) {
                        currentFenceName?.let { fenceName ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.LocationOn, contentDescription = null, tint = Success, modifier = Modifier.size(24.dp))
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text(stringResource(R.string.currently_inside, fenceName), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                    Text(stringResource(R.string.monitoring_active), fontSize = 16.sp, color = Color.White.copy(alpha = 0.7f))
                                }
                            }
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.LocationOff, contentDescription = null, tint = Warning, modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.not_inside_any_fence), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }
                }
            }
        }

        item {
            Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(12.dp)) {
                StatsCard(title = stringResource(R.string.today_enter), value = "$todayEnterCount ${stringResource(R.string.times)}", icon = Icons.AutoMirrored.Filled.Login, color = Success, modifier = Modifier.weight(1f))
                StatsCard(title = stringResource(R.string.today_exit), value = "$todayExitCount ${stringResource(R.string.times)}", icon = Icons.AutoMirrored.Filled.Logout, color = Danger, modifier = Modifier.weight(1f))
            }
        }

        item {
            GlassCard(Modifier.fillMaxWidth()) {
                Column {
                    Text(stringResource(R.string.monthly_top_fences), fontSize = 14.sp, color = primaryColor())
                    Spacer(Modifier.height(12.dp))
                    if (monthTopFences.isEmpty()) {
                        Text(stringResource(R.string.no_data), fontSize = 14.sp, color = Color.White.copy(alpha = 0.5f))
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
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onNavigateToStatistics() }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.BarChart,
                        contentDescription = null,
                        tint = primaryColor(),
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        stringResource(R.string.view_statistics),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                }
            }
        }

        item { Spacer(Modifier.height(80.dp)) }
    }
}

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
