package com.trackmaster.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trackmaster.app.StatisticsManager
import com.trackmaster.app.TrackMasterApplication
import com.trackmaster.app.ui.theme.*
import com.trackmaster.app.ui.design.currentPalette

/**
 * 真空期信息提示组件
 *
 * 显示应用被清理后台期间的时间信息
 */
@Composable
fun VacuumPeriodInfo(
    modifier: Modifier = Modifier
) {
    val app = remember { TrackMasterApplication.instance }
    val statsManager = remember { app.statisticsManager }
    var vacuumDuration by remember { mutableStateOf<Long?>(null) }
    var lastExitFence by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        vacuumDuration = app.getVacuumDuration()
        lastExitFence = app.getLastExitFence()?.fenceName
    }

    val duration: Long? = vacuumDuration
    if (duration != null && duration > 0) {
        Card(
            modifier = modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = Color(0x4DFFA500) // 橙色半透明背景
            ),
            shape = RoundedCornerShape(12.dp),
            elevation = CardDefaults.cardElevation(0.dp)
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "⚠️",
                    fontSize = 20.sp
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = "应用曾关闭",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFE65100)
                    )
                    Text(
                        text = "真空期: ${statsManager.formatDuration(duration)} (未记录)",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFE65100)
                    )
                    if (lastExitFence != null) {
                        Text(
                            text = "上次离开: $lastExitFence",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFE65100)
                        )
                    }
                }
            }
        }
    }
}
