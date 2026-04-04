package com.trackmaster.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trackmaster.app.core.DailySummary
import com.trackmaster.app.core.VizNode
import com.trackmaster.app.services.Aggregator
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

/**
 * 可视化树屏幕 — 展示每日停留的 Sunburst / 嵌套柱状图
 *
 * 流程：选择日期 → Aggregator.aggregate() → toVizTree() → 嵌套柱状图
 */
@Composable
fun VizTreeScreen(
    aggregator: Aggregator,
    modifier: Modifier = Modifier,
) {
    var selectedDate by remember { mutableStateOf(todayKey()) }
    var summaries by remember { mutableStateOf<List<DailySummary>>(emptyList()) }
    var vizTree by remember { mutableStateOf<VizNode?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // 加载数据
    LaunchedEffect(selectedDate) {
        isLoading = true
        scope.launch {
            try {
                val result = aggregator.aggregate(selectedDate)
                summaries = result
                vizTree = if (result.isNotEmpty()) aggregator.toVizTree(result) else null
            } catch (_: Exception) {
                summaries = emptyList()
                vizTree = null
            }
            isLoading = false
        }
    }

    Column(modifier = modifier.padding(16.dp)) {
        // 日期选择器
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

        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (vizTree == null || summaries.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "当日无停留数据",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            // 嵌套柱状图
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                // 总览
                item {
                    val totalMin = summaries.sumOf { it.spatialDurationMin }
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                "总停留: ${formatDuration(totalMin)}",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "${summaries.size} 个围栏",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }

                // 树形节点
                val children = vizTree?.children ?: emptyList()
                items(children) { node ->
                    VizNodeBar(node = node, maxDuration = children.maxOfOrNull { sumValues(it) } ?: 1)
                }
            }
        }
    }
}

@Composable
private fun VizNodeBar(node: VizNode, maxDuration: Int, depth: Int = 0) {
    val totalValue = sumValues(node)
    val ratio = if (maxDuration > 0) totalValue.toFloat() / maxDuration else 0f
    val barColor = nodeColor(depth)

    var expanded by remember { mutableStateOf(false) }
    val hasChildren = !node.children.isNullOrEmpty()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (depth * 16).dp, bottom = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = hasChildren) { expanded = !expanded }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 节点名
            Text(
                text = node.name,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (depth == 0) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.widthIn(max = 100.dp),
                maxLines = 1,
                fontSize = ((13 - depth).coerceAtLeast(10)).sp
            )

            Spacer(modifier = Modifier.width(8.dp))

            // 条形图
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(20.dp)
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(4.dp)
                    )
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(fraction = ratio.coerceIn(0f, 1f))
                        .background(barColor, RoundedCornerShape(4.dp))
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // 时长
            Text(
                text = formatDuration(totalValue),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.width(55.dp)
            )

            if (hasChildren) {
                Text(
                    text = if (expanded) "▾" else "▸",
                    fontSize = 12.sp,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }
        }

        // 子节点
        if (expanded && hasChildren) {
            val childMax = node.children!!.maxOfOrNull { sumValues(it) } ?: 1
            for (child in node.children) {
                VizNodeBar(node = child, maxDuration = childMax, depth = depth + 1)
            }
        }
    }
}

private fun sumValues(node: VizNode): Int {
    return node.value ?: node.children?.sumOf { sumValues(it) } ?: 0
}

private fun nodeColor(depth: Int): Color {
    val colors = listOf(
        Color(0xFF42A5F5), // 蓝
        Color(0xFF66BB6A), // 绿
        Color(0xFFFFA726), // 橙
        Color(0xFFEF5350), // 红
        Color(0xFFAB47BC), // 紫
        Color(0xFF26C6DA), // 青
    )
    return colors[depth % colors.size]
}

private fun formatDuration(minutes: Int): String {
    if (minutes < 60) return "${minutes}分钟"
    val h = minutes / 60
    val m = minutes % 60
    return if (m > 0) "${h}时${m}分" else "${h}小时"
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
