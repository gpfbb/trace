package com.trackmaster.app.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import com.trackmaster.app.core.PowerMode
import com.trackmaster.app.utils.LanguageManager
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trackmaster.app.R
import com.trackmaster.app.utils.AppLog
import com.trackmaster.app.ui.design.currentPalette
import androidx.compose.foundation.layout.statusBarsPadding
import com.trackmaster.app.ui.design.LargeTitle
import com.trackmaster.app.ui.design.AppIcons
import com.trackmaster.app.ui.design.IconTile
import com.trackmaster.app.settings.FenceRelationshipRecalculator
import com.trackmaster.app.settings.PermissionSectionContent
import com.trackmaster.app.settings.TrackingToggleCard
import com.trackmaster.app.settings.LocationModeSection
import com.trackmaster.app.ui.design.DesignSecondaryButton

@Composable
fun AdvancedSettingsSection(
    stationaryThreshold: Double,
    onStationaryThresholdChange: (Double) -> Unit,
    boundaryBuffer: Double,
    onBoundaryBufferChange: (Double) -> Unit,
    driftThreshold: Double,
    onDriftThresholdChange: (Double) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // 默认参数推荐卡片
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = currentPalette.success.copy(alpha = 0.12f)
            ),
            shape = RoundedCornerShape(12.dp),
            elevation = CardDefaults.cardElevation(0.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = AppIcons.star,
                        contentDescription = stringResource(R.string.recommended),
                        tint = currentPalette.success,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.recommended_defaults),
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = currentPalette.success
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "• ${stringResource(R.string.default_config_stationary, 20)}\n• ${stringResource(R.string.default_config_buffer, 10)}\n• ${stringResource(R.string.default_config_drift, 30)}",
                    fontSize = 12.sp,
                    color = currentPalette.labelSecondary,
                    lineHeight = 18.sp
                )
            }
        }

        // 重置按钮
        com.trackmaster.app.ui.design.DesignSecondaryButton(
            onClick = {
                onStationaryThresholdChange(30.0)
                onBoundaryBufferChange(10.0)
                onDriftThresholdChange(30.0)
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.reset_defaults), color = currentPalette.accent)
        }

        // 静止判定阈值
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    stringResource(R.string.stationary_threshold),
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                    color = currentPalette.label
                )
                Text(
                    "${stationaryThreshold.toInt()}m",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = currentPalette.accent
                )
            }
            Slider(
                value = stationaryThreshold.toFloat(),
                onValueChange = { onStationaryThresholdChange(it.toDouble()) },
                valueRange = 10f..80f,
                steps = 11,
                colors = SliderDefaults.colors(
                    thumbColor = currentPalette.accent,
                    activeTrackColor = currentPalette.accent,
                    inactiveTrackColor = currentPalette.separator
                )
            )
            Text(
                stringResource(R.string.stationary_threshold_desc),
                fontSize = 11.sp,
                color = currentPalette.labelTertiary
            )
        }

        // 边界缓冲距离
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    stringResource(R.string.boundary_buffer),
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                    color = currentPalette.label
                )
                Text(
                    "${boundaryBuffer.toInt()}m",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = currentPalette.accent
                )
            }
            Slider(
                value = boundaryBuffer.toFloat(),
                onValueChange = { onBoundaryBufferChange(it.toDouble()) },
                valueRange = 3f..100f,
                steps = 9,
                colors = SliderDefaults.colors(
                    thumbColor = currentPalette.accent,
                    activeTrackColor = currentPalette.accent,
                    inactiveTrackColor = currentPalette.separator
                )
            )
            Text(
                stringResource(R.string.boundary_buffer_desc),
                fontSize = 11.sp,
                color = currentPalette.labelTertiary
            )
        }

        // 漂移检测阈值
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    stringResource(R.string.drift_threshold),
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                    color = currentPalette.label
                )
                Text(
                    "${driftThreshold.toInt()}m",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = currentPalette.accent
                )
            }
            Slider(
                value = driftThreshold.toFloat(),
                onValueChange = { onDriftThresholdChange(it.toDouble()) },
                valueRange = 10f..50f,
                steps = 7,
                colors = SliderDefaults.colors(
                    thumbColor = currentPalette.accent,
                    activeTrackColor = currentPalette.accent,
                    inactiveTrackColor = currentPalette.separator
                )
            )
            Text(
                stringResource(R.string.drift_threshold_desc),
                fontSize = 11.sp,
                color = currentPalette.labelTertiary
            )
        }
    }
}

/**
 * 重新计算父子关系的确认/结果对话框。
 *
 * 首次打开是"确认执行"，执行后变成"结果展示"：
 * 有相交围栏时说明为什么没执行，成功时报告建立了多少对关系。
 */
