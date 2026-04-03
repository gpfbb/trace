package com.trackmaster.app

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SettingsScreen(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val permissionManager = remember { PermissionManager(context) }
    var permissionStatus by remember { mutableStateOf(permissionManager.checkPermissions()) }
    var selectedMode by remember { mutableStateOf(LocationMode.BALANCED) }

    LaunchedEffect(Unit) {
        permissionStatus = permissionManager.checkPermissions()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "设置",
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text("应用设置", fontWeight = FontWeight.Bold, fontSize = 20.sp)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                PermissionSection(
                    permissionStatus = permissionStatus,
                    permissionManager = permissionManager,
                    onPermissionChanged = {
                        permissionStatus = permissionManager.checkPermissions()
                    }
                )

                HorizontalDivider()

                LocationModeSection(
                    selectedMode = selectedMode,
                    onModeSelected = { mode ->
                        selectedMode = mode
                        (context.applicationContext as? TrackMasterApplication)?.let { app ->
                            app.getLocationService()?.setLocationMode(mode)
                        }
                    }
                )

                HorizontalDivider()

                InfoSection()
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
}

@Composable
fun PermissionSection(
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
            "权限管理",
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp
        )

        PermissionItem(
            title = "位置权限",
            description = "获取用户位置信息以监测地理围栏",
            isGranted = permissionStatus.hasLocationPermission,
            onGrantClick = {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
                context.startActivity(intent)
            }
        )

        PermissionItem(
            title = "后台定位权限",
            description = "在应用后台时仍能监测位置",
            isGranted = permissionStatus.hasBackgroundLocationPermission,
            onGrantClick = {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
                context.startActivity(intent)
            }
        )

        PermissionItem(
            title = "电池优化白名单",
            description = "防止系统杀死后台服务",
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
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Box(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "⚠️ 部分权限未授予，地理围栏功能可能无法正常工作。请点击上述按钮授予必要权限。",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onErrorContainer
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
                MaterialTheme.colorScheme.primaryContainer 
            else 
                MaterialTheme.colorScheme.surfaceVariant
        )
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
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = description,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            if (isGranted) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "已授予",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
            } else {
                Button(onClick = onGrantClick) {
                    Text("去设置")
                }
            }
        }
    }
}

@Composable
fun LocationModeSection(
    selectedMode: LocationMode,
    onModeSelected: (LocationMode) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "定位模式",
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp
        )

        LocationModeItem(
            title = "省电模式",
            description = "定位频率较低(60秒/次)，在围栏边界区域提升至10秒/次，最省电",
            isSelected = selectedMode == LocationMode.POWER_SAVING,
            onClick = { onModeSelected(LocationMode.POWER_SAVING) }
        )

        LocationModeItem(
            title = "均衡模式",
            description = "根据运动状态智能调整：静止30秒/次，步行15秒/次，跑步/高速移动5秒/次",
            isSelected = selectedMode == LocationMode.BALANCED,
            onClick = { onModeSelected(LocationMode.BALANCED) }
        )

        LocationModeItem(
            title = "性能模式",
            description = "最高定位频率(2秒/次)，实时性和精度最好，但耗电最高",
            isSelected = selectedMode == LocationMode.PERFORMANCE,
            onClick = { onModeSelected(LocationMode.PERFORMANCE) }
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
                MaterialTheme.colorScheme.primaryContainer 
            else 
                MaterialTheme.colorScheme.surface
        ),
        border = if (isSelected) androidx.compose.foundation.BorderStroke(
            2.dp,
            MaterialTheme.colorScheme.primary
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
                onClick = onClick
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = description,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 16.sp
                )
            }
        }
    }
}

@Composable
fun InfoSection() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "使用说明",
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp
        )
        Text(
            "1. 请授予所有必要权限以确保功能正常运行\n" +
                    "2. 添加至电池优化白名单可防止后台服务被系统杀死\n" +
                    "3. 可根据实际需求选择合适的定位模式\n" +
                    "4. 省电模式适合长时间使用，性能模式适合需要高精确度的场景",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 18.sp
        )
    }
}
