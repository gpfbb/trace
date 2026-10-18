package com.trackmaster.app.ui.statistics
import android.graphics.Color as AndroidColor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trackmaster.app.R
import com.trackmaster.app.FenceStatisticTree
import com.trackmaster.app.StatisticsManager
import com.trackmaster.app.ui.design.currentPalette

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
            containerColor = currentPalette.groupedCell
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
                    color = currentPalette.accent
                )
                Text(
                    stringResource(R.string.total_duration, formatDuration(totalDuration)),
                    style = MaterialTheme.typography.labelSmall,
                    color = currentPalette.labelSecondary
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
                containerColor = if (node.depth == 0) currentPalette.groupedCell else currentPalette.groupedCell
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
                        tint = currentPalette.accent,
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
                            color = currentPalette.label,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (node.children.isNotEmpty()) {
                            Text(
                                text = "(${node.children.size})",
                                style = MaterialTheme.typography.labelSmall,
                                color = currentPalette.accent
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
                            color = currentPalette.accent
                        )
                        Text(
                            text = formatDuration(node.totalDurationSeconds),
                            style = MaterialTheme.typography.labelSmall,
                            color = currentPalette.labelSecondary
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .width(50.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(currentPalette.groupedCell)
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
                    totalDuration = totalDuration
                )
            }
        }
    }
}
