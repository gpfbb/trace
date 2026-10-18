package com.trackmaster.app.settings

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
import com.trackmaster.app.ui.theme.*
import com.trackmaster.app.utils.AppLog
import com.trackmaster.app.ui.design.currentPalette
import androidx.compose.foundation.layout.statusBarsPadding
import com.trackmaster.app.ui.design.LargeTitle
import com.trackmaster.app.ui.design.AppIcons
import com.trackmaster.app.ui.design.IconTile
import com.trackmaster.app.settings.FenceRelationshipRecalculator
import com.trackmaster.app.ui.design.AppType
import com.trackmaster.app.R
import com.trackmaster.app.PermissionManager
@Composable
fun PermissionSectionContent(
    permissionStatus: PermissionManager.PermissionStatus,
    permissionManager: PermissionManager,
    onPermissionChanged: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                onPermissionChanged()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringResource(R.string.permission_management),
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            color = primaryColor()
        )

        PermissionItem(
            title = stringResource(R.string.location_permission),
            description = stringResource(R.string.location_permission_desc),
            isGranted = permissionStatus.hasLocationPermission,
            onGrantClick = {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
                context.startActivity(intent)
            }
        )

        PermissionItem(
            title = stringResource(R.string.background_location_permission),
            description = stringResource(R.string.background_location_permission_desc),
            isGranted = permissionStatus.hasBackgroundLocationPermission,
            onGrantClick = {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
                context.startActivity(intent)
            }
        )

        PermissionItem(
            title = stringResource(R.string.battery_optimization),
            description = stringResource(R.string.battery_optimization_desc),
            isGranted = permissionStatus.isIgnoringBatteryOptimizations,
            onGrantClick = {
                permissionManager.getBatteryOptimizationIntent()?.let { intent ->
                    try {
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        val settingsIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        context.startActivity(settingsIntent)
                    }
                }
            }
        )

        if (!permissionStatus.hasAllPermissions) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    // 危险色淡底 + 危险色文字：两种模式下都能读到 4.5:1 以上
                    containerColor = currentPalette.danger.copy(alpha = 0.12f)
                ),
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                Box(modifier = Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.permission_warning),
                        fontSize = 13.sp,
                        color = currentPalette.danger
                    )
                }
            }
        }
    }
}

@Composable
fun PermissionItem(
    title: String,
    description: String,
    isGranted: Boolean,
    onGrantClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isGranted)
                Color(0x33FF7F50)
            else
                currentPalette.groupedCell
        ),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontWeight = FontWeight.Medium,
                    color = currentPalette.label
                )
                Text(
                    text = description,
                    fontSize = 12.sp,
                    color = currentPalette.labelSecondary
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            if (isGranted) {
                Icon(
                    imageVector = AppIcons.checkCircle,
                    contentDescription = stringResource(R.string.granted),
                    tint = Success,
                    modifier = Modifier.size(28.dp)
                )
            } else {
                GlassButton(onClick = onGrantClick) {
                    Text(stringResource(R.string.go_to_settings), color = Color.White)
                }
            }
        }
    }
}

@Composable
fun TrackingToggleCard(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = currentPalette.groupedCell),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.tracking_enabled),
                    fontWeight = FontWeight.Medium,
                    color = currentPalette.label
                )
                Text(
                    text = stringResource(R.string.tracking_enabled_desc),
                    fontSize = 12.sp,
                    color = currentPalette.labelSecondary,
                    lineHeight = 16.sp
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = primaryColor(),
                    checkedTrackColor = primaryColor().copy(alpha = 0.4f)
                )
            )
        }
    }
}

@Composable
fun LocationModeSection(
    selectedMode: PowerMode,
    onModeSelected: (PowerMode) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringResource(R.string.location_mode),
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            color = primaryColor()
        )

        LocationModeItem(
            title = stringResource(R.string.power_save_mode),
            description = stringResource(R.string.power_save_desc),
            isSelected = selectedMode == PowerMode.POWERSAVE,
            onClick = { onModeSelected(PowerMode.POWERSAVE) }
        )

        LocationModeItem(
            title = stringResource(R.string.balanced_mode),
            description = stringResource(R.string.balanced_desc),
            isSelected = selectedMode == PowerMode.BALANCED,
            onClick = { onModeSelected(PowerMode.BALANCED) }
        )
    }
}

@Composable
fun LocationModeItem(
    title: String,
    description: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                Color(0x4DFF7F50)
            else
                currentPalette.groupedCell
        ),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(0.dp),
        border = if (isSelected) androidx.compose.foundation.BorderStroke(
            2.dp,
            primaryColor()
        ) else null
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = isSelected,
                onClick = onClick,
                colors = RadioButtonDefaults.colors(selectedColor = primaryColor())
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontWeight = FontWeight.Medium,
                    color = currentPalette.label
                )
                Text(
                    text = description,
                    fontSize = 12.sp,
                    color = currentPalette.labelSecondary,
                    lineHeight = 16.sp
                )
            }
        }
    }
}

