package com.trackmaster.app

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import com.trackmaster.app.core.PowerMode
import com.trackmaster.app.utils.GeoMath
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trackmaster.app.ui.components.CompactThemeSelector
import com.trackmaster.app.ui.components.ThemePickerDialog
import com.trackmaster.app.ui.theme.*

// 独立的设置页面内容组件
@Composable
fun SettingsPage(
    @Suppress("UNUSED_PARAMETER") onNavigateBack: (() -> Unit)? = null,
    onOpenDataImportExport: (() -> Unit)? = null,
    hideNavBar: Boolean = false
) {
    val context = LocalContext.current
    val app = context.applicationContext as TrackMasterApplication
    val prefs = app.appPreferences
    val permissionManager = remember { PermissionManager(context) }
    var permissionStatus by remember { mutableStateOf(permissionManager.checkPermissions()) }
    var selectedMode by remember { mutableStateOf(prefs.powerMode) }
    var currentTheme by remember { mutableStateOf(ThemeManager.getCurrentTheme()) }
    var showThemePicker by remember { mutableStateOf(false) }

    // 高级设置状态
    var showAdvancedSettings by remember { mutableStateOf(false) }
    var stationaryThreshold by remember { mutableStateOf(prefs.stationaryThreshold) }
    var boundaryBuffer by remember { mutableStateOf(prefs.boundaryBuffer) }
    var driftThreshold by remember { mutableStateOf(prefs.driftThreshold) }


    LaunchedEffect(Unit) {
        permissionStatus = permissionManager.checkPermissions()
    }

    // 定期刷新权限状态
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissionStatus = permissionManager.checkPermissions()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(brush = Brush.verticalGradient(colors = listOf(surfaceColor(), backgroundColor())))
    ) {
        // 顶部导航栏 - 无返回按钮，作为底部Tab页面
        if (!hideNavBar) {
            GlassTopCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "设置",
                        tint = primaryColor(),
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        "应用设置",
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                        color = primaryColor()
                    )
                }
            }
        }

        // 内容区域
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            PermissionSectionContent(
                permissionStatus = permissionStatus,
                permissionManager = permissionManager,
                onPermissionChanged = {
                    permissionStatus = permissionManager.checkPermissions()
                }
            )

            HorizontalDivider(color = Color(0x30FFFFFF))

            LocationModeSection(
                selectedMode = selectedMode,
                onModeSelected = { mode ->
                    selectedMode = mode
                    prefs.powerMode = mode
                    app.getLocationService()?.setPowerMode(mode)
                }
            )

            HorizontalDivider(color = Color(0x30FFFFFF))

            // 主题选择
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showThemePicker = true },
                colors = CardDefaults.cardColors(
                    containerColor = Color(0x4D000000)
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Palette,
                            contentDescription = "主题",
                            tint = ThemeManager.getPrimaryColor(),
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "主题",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = Color(0xFFE0E0E0)
                            )
                            Text(
                                currentTheme.displayName,
                                fontSize = 13.sp,
                                color = Color(0xFFBDBDBD)
                            )
                        }
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = Color(0xFFBDBDBD),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            HorizontalDivider(color = Color(0x30FFFFFF))

            if (onOpenDataImportExport != null) {
                SettingsMenuCard(
                    icon = Icons.Default.Folder,
                    title = "数据导入导出",
                    description = "围栏与统计数据的导入导出",
                    tint = primaryColor(),
                    onClick = onOpenDataImportExport
                )
                HorizontalDivider(color = Color(0x30FFFFFF))
            }

            // 重新计算父子关系
            var showRecalculateDialog by remember { mutableStateOf(false) }
            var recalculateMessage by remember { mutableStateOf<String?>(null) }

            SettingsMenuCard(
                icon = Icons.Default.AccountTree,
                title = "重新计算父子关系",
                description = "根据几何位置自动判断围栏包含关系",
                tint = NeonBlue,
                onClick = { showRecalculateDialog = true }
            )

            if (showRecalculateDialog) {
                AlertDialog(
                    onDismissRequest = { showRecalculateDialog = false },
                    title = { Text("重新计算父子关系") },
                    text = {
                        Column {
                            Text("这将根据几何位置自动判断围栏的父子包含关系，确定继续？")
                            if (recalculateMessage != null) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    recalculateMessage!!,
                                    color = if (recalculateMessage!!.startsWith("重新计算完成")) Success else Danger,
                                    fontSize = 14.sp
                                )
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                try {
                                    app.fenceTreeManager.clearAll()
                                    val fences = app.geoFences
                                    val coreFences = fences.mapNotNull { fence ->
                                        val type = try {
                                            com.trackmaster.app.core.GeoFenceType.valueOf(fence.type.name)
                                        } catch (_: Exception) { null }
                                        if (type != null) {
                                            // 计算中心点：圆形用center，多边形用重心
                                            val centerPoint: com.trackmaster.app.core.GeoPoint? = when (type) {
                                                com.trackmaster.app.core.GeoFenceType.CIRCLE -> fence.center?.let {
                                                    com.trackmaster.app.core.GeoPoint(it.latitude, it.longitude)
                                                }
                                                com.trackmaster.app.core.GeoFenceType.POLYGON -> {
                                                    // 计算多边形重心
                                                    val pts = fence.points
                                                    if (pts != null && pts.size >= 3) {
                                                        var latSum = 0.0
                                                        var lonSum = 0.0
                                                        for (p in pts) {
                                                            latSum += p.latitude
                                                            lonSum += p.longitude
                                                        }
                                                        com.trackmaster.app.core.GeoPoint(
                                                            latSum / pts.size,
                                                            lonSum / pts.size
                                                        )
                                                    } else null
                                                }
                                            }
                                            com.trackmaster.app.core.Geofence(
                                                id = fence.id,
                                                name = fence.name,
                                                type = type,
                                                polygon = fence.points?.map {
                                                    com.trackmaster.app.core.GeoPoint(it.latitude, it.longitude)
                                                } ?: emptyList(),
                                                center = centerPoint,
                                                radius = fence.radius?.toDouble()
                                            )
                                        } else null
                                    }

                                    var relationshipCount = 0
                                    android.util.Log.d("SettingsPage", "开始计算父子关系，coreFences数量: ${coreFences.size}")
                                    for (fenceA in coreFences) {
                                        for (fenceB in coreFences) {
                                            if (fenceA.id == fenceB.id) continue
                                            val bInA = GeoMath.isFenceContainedByFence(fenceB, fenceA)
                                            val aInB = GeoMath.isFenceContainedByFence(fenceA, fenceB)
                                            android.util.Log.d("SettingsPage", "检查 ${fenceB.name} in ${fenceA.name}: $bInA, 反向: $aInB")
                                            if (bInA) {
                                                if (!aInB) {
                                                    app.fenceTreeManager.setParent(fenceB.id, fenceA.id)
                                                    relationshipCount++
                                                    android.util.Log.d("SettingsPage", "设置父子关系: ${fenceB.name} -> ${fenceA.name}")
                                                }
                                            }
                                        }
                                    }
                                    android.util.Log.d("SettingsPage", "重新计算完成，建立了 $relationshipCount 对父子关系")
                                    recalculateMessage = "重新计算完成，建立了 $relationshipCount 对父子关系"
                                } catch (e: Exception) {
                                    recalculateMessage = "重新计算失败: ${e.message}"
                                }
                            }
                        ) {
                            Text("确定", color = Success)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showRecalculateDialog = false }) {
                            Text("取消", color = Color.Gray)
                        }
                    }
                )
            }

            HorizontalDivider(color = Color(0x30FFFFFF))

            // 高级设置
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showAdvancedSettings = !showAdvancedSettings },
                colors = CardDefaults.cardColors(
                    containerColor = Color(0x4D000000)
                ),
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = "高级设置",
                            tint = primaryColor(),
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "高级设置",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = Color(0xFFE0E0E0)
                            )
                            Text(
                                "调整定位算法参数",
                                fontSize = 13.sp,
                                color = Color(0xFFBDBDBD)
                            )
                        }
                        Icon(
                            imageVector = if (showAdvancedSettings) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (showAdvancedSettings) "收起" else "展开",
                            tint = Color(0xFFBDBDBD),
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    if (showAdvancedSettings) {
                        Spacer(modifier = Modifier.height(16.dp))
                        AdvancedSettingsSection(
                            stationaryThreshold = stationaryThreshold,
                            onStationaryThresholdChange = { value ->
                                stationaryThreshold = value
                                prefs.stationaryThreshold = value
                                app.updateEngineConfig()
                            },
                            boundaryBuffer = boundaryBuffer,
                            onBoundaryBufferChange = { value ->
                                boundaryBuffer = value
                                prefs.boundaryBuffer = value
                                app.updateEngineConfig()
                            },
                            driftThreshold = driftThreshold,
                            onDriftThresholdChange = { value ->
                                driftThreshold = value
                                prefs.driftThreshold = value
                                app.updateEngineConfig()
                            }
                        )
                    }
                }
            }

            HorizontalDivider(color = Color(0x30FFFFFF))

            InfoSection()

            Spacer(modifier = Modifier.height(80.dp))
        }
    }

    // 主题选择对话框
    if (showThemePicker) {
        ThemePickerDialog(
            currentTheme = currentTheme,
            onThemeSelected = { theme ->
                currentTheme = theme
                ThemeManager.setTheme(context, theme)
            },
            onDismiss = { showThemePicker = false }
        )
    }
}

@Composable
private fun SettingsMenuCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
    tint: Color,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = Color(0x4D000000)
        ),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = tint,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = Color(0xFFE0E0E0)
                )
                Text(
                    description,
                    fontSize = 13.sp,
                    color = Color(0xFFBDBDBD)
                )
            }
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = Color(0xFFBDBDBD)
            )
        }
    }
}

// SettingsScreen 保持向后兼容 - 作为对话框
@Composable
fun SettingsScreen(
    onDismiss: () -> Unit,
    onOpenDataImportExport: (() -> Unit)? = null
) {
    GlassDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "设置",
                    tint = primaryColor(),
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text("应用设置", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = primaryColor())
            }
        },
        text = {
            SettingsPage(
                onNavigateBack = onDismiss,
                onOpenDataImportExport = onOpenDataImportExport,
                hideNavBar = true
            )
        },
        confirmButton = {
            GlassButton(onClick = onDismiss) {
                Text("关闭", color = Color.White)
            }
        }
    )
}

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
            "权限管理",
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            color = primaryColor()
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
            description = "防止后台服务被系统杀死",
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
                    containerColor = Color(0x4DFF5252)
                ),
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                Box(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "⚠️ 部分权限未授予，地理围栏功能可能无法正常工作。请点击上述按钮授予必要权限。",
                        fontSize = 13.sp,
                        color = Color(0xFFFFCDD2)
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
                Color(0x4D2A221C)
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
                    color = Color(0xFFE0E0E0)
                )
                Text(
                    text = description,
                    fontSize = 12.sp,
                    color = Color(0xFFBDBDBD)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            if (isGranted) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "已授予",
                    tint = Success,
                    modifier = Modifier.size(28.dp)
                )
            } else {
                GlassButton(onClick = onGrantClick) {
                    Text("去设置", color = Color.White)
                }
            }
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
            "定位模式",
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            color = primaryColor()
        )

        LocationModeItem(
            title = "省电模式",
            description = "超低功耗，适合睡觉/长时间静止。静止1分钟起2分钟/次，5分钟起8分钟/次，1小时以上仅30分钟/次",
            isSelected = selectedMode == PowerMode.POWERSAVE,
            onClick = { onModeSelected(PowerMode.POWERSAVE) }
        )

        LocationModeItem(
            title = "均衡模式",
            description = "智能平衡：静止1分钟/次起，2分钟降至3分钟/次，5分钟降至8分钟/次，10分钟以上10分钟/次",
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
                Color(0x4D2A221C)
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
                    color = Color(0xFFE0E0E0)
                )
                Text(
                    text = description,
                    fontSize = 12.sp,
                    color = Color(0xFFBDBDBD),
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
            fontSize = 16.sp,
            color = primaryColor()
        )
        Text(
            "1. 请授予所有必要权限以确保功能正常运行\n" +
                    "2. 添加至电池优化白名单可防止后台服务被系统杀死\n" +
                    "3. 可根据实际需求选择合适的定位模式\n" +
                    "4. 省电模式适合长时间使用，性能模式适合需要高精确度的场景",
            fontSize = 13.sp,
            color = Color(0xFFBDBDBD),
            lineHeight = 18.sp
        )
    }
}

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
                containerColor = Color(0x4D1B5E20)
            ),
            shape = RoundedCornerShape(12.dp),
            elevation = CardDefaults.cardElevation(0.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = "推荐",
                        tint = Color(0xFF81C784),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "推荐默认配置",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = Color(0xFF81C784)
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "• 静止判定: 20m (小围栏推荐10-30m)\n" +
                            "• 边界缓冲: 10m (小围栏推荐3-20m)\n" +
                            "• 漂移检测: 30m (过滤低精度GPS点)",
                    fontSize = 12.sp,
                    color = Color(0xFFC8E6C9),
                    lineHeight = 18.sp
                )
            }
        }

        // 重置按钮
        GlassButton(
            onClick = {
                onStationaryThresholdChange(30.0)
                onBoundaryBufferChange(10.0)
                onDriftThresholdChange(30.0)
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("恢复默认设置", color = Color.White)
        }

        // 静止判定阈值
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "静止判定阈值",
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                    color = Color(0xFFE0E0E0)
                )
                Text(
                    "${stationaryThreshold.toInt()}m",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = primaryColor()
                )
            }
            Slider(
                value = stationaryThreshold.toFloat(),
                onValueChange = { onStationaryThresholdChange(it.toDouble()) },
                valueRange = 10f..80f,
                steps = 11,
                colors = SliderDefaults.colors(
                    thumbColor = primaryColor(),
                    activeTrackColor = primaryColor(),
                    inactiveTrackColor = Color(0x4DFFFFFF)
                )
            )
            Text(
                "判定为静止的距离阈值。值越大越容易判定为静止，越省电。小围栏建议20-50米避免室内误判。",
                fontSize = 11.sp,
                color = Color(0xFF9E9E9E)
            )
        }

        // 边界缓冲距离
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "边界缓冲距离",
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                    color = Color(0xFFE0E0E0)
                )
                Text(
                    "${boundaryBuffer.toInt()}m",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = primaryColor()
                )
            }
            Slider(
                value = boundaryBuffer.toFloat(),
                onValueChange = { onBoundaryBufferChange(it.toDouble()) },
                valueRange = 3f..100f,
                steps = 9,
                colors = SliderDefaults.colors(
                    thumbColor = primaryColor(),
                    activeTrackColor = primaryColor(),
                    inactiveTrackColor = Color(0x4DFFFFFF)
                )
            )
            Text(
                "围栏边界扩展区域。小围栏建议3-20米，过大(如100m+)会导致室内外判断失误。",
                fontSize = 11.sp,
                color = Color(0xFF9E9E9E)
            )
        }

        // 漂移检测阈值
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "漂移检测阈值",
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                    color = Color(0xFFE0E0E0)
                )
                Text(
                    "${driftThreshold.toInt()}m",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = primaryColor()
                )
            }
            Slider(
                value = driftThreshold.toFloat(),
                onValueChange = { onDriftThresholdChange(it.toDouble()) },
                valueRange = 10f..50f,
                steps = 7,
                colors = SliderDefaults.colors(
                    thumbColor = primaryColor(),
                    activeTrackColor = primaryColor(),
                    inactiveTrackColor = Color(0x4DFFFFFF)
                )
            )
            Text(
                "GPS精度过滤阈值。定位点精度超过此值会被丢弃，防止误判。小围栏建议15-30米。",
                fontSize = 11.sp,
                color = Color(0xFF9E9E9E)
            )
        }
    }
}
