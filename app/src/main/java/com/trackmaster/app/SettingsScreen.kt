package com.trackmaster.app

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
import com.trackmaster.app.ui.theme.*
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

// 独立的设置页面内容组件
@Composable
fun SettingsPage(
    @Suppress("UNUSED_PARAMETER") onNavigateBack: (() -> Unit)? = null,
    onOpenDataImportExport: (() -> Unit)? = null,
    hideNavBar: Boolean = false,
    /**
     * 语言切换后回调，通知宿主刷新 LocalConfiguration。
     * 不传则切换语言后已有界面文字不会立即更新。
     */
    onLanguageChangeRequested: () -> Unit = {}
) {
    val context = LocalContext.current
    val app = context.applicationContext as TrackMasterApplication
    val prefs = app.appPreferences
    val permissionManager = remember { PermissionManager(context) }
    var permissionStatus by remember { mutableStateOf(permissionManager.checkPermissions()) }
    var selectedMode by remember { mutableStateOf(prefs.powerMode) }
    var showThemePicker by remember { mutableStateOf(false) }
    var showLanguagePicker by remember { mutableStateOf(false) }
    var currentLanguage by remember { mutableStateOf(prefs.appLanguage) }

    // 主题设置：读全局唯一的控制器（由 TrackMasterDesignTheme 提供）。
    // 不要在这里 remember 一个新的——那样改的是另一份状态，界面不会更新。
    val themeController = com.trackmaster.app.ui.design.rememberThemeController()

    // 高级设置状态
    var showAdvancedSettings by remember { mutableStateOf(false) }
    var stationaryThreshold by remember { mutableStateOf(prefs.stationaryThreshold) }
    var boundaryBuffer by remember { mutableStateOf(prefs.boundaryBuffer) }
    var driftThreshold by remember { mutableStateOf(prefs.driftThreshold) }
    var recalculateIsSuccess by remember { mutableStateOf(false) }


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
            .background(currentPalette.background)
            .statusBarsPadding()
    ) {
        // 页面大标题（分组列表风格）
        if (!hideNavBar) {
            LargeTitle(text = stringResource(R.string.app_settings))
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

            HorizontalDivider(color = currentPalette.separator)

            // 追踪总开关：关闭后停止后台定位并取消定时唤醒
            TrackingToggleCard(
                enabled = prefs.isTrackingEnabled,
                onToggle = { enabled ->
                    app.setTrackingEnabled(enabled)
                }
            )

            HorizontalDivider(color = currentPalette.separator)

            LocationModeSection(
                selectedMode = selectedMode,
                onModeSelected = { mode ->
                    selectedMode = mode
                    prefs.powerMode = mode
                    app.getLocationService()?.setPowerMode(mode)
                }
            )

            HorizontalDivider(color = currentPalette.separator)

            // 地图服务切换
            var showMapProviderPicker by remember { mutableStateOf(false) }
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showMapProviderPicker = true },
                colors = CardDefaults.cardColors(containerColor = currentPalette.groupedCell),
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconTile(
                        icon = AppIcons.mapService,
                        containerColor = primaryColor(),
                        tint = Color.White,
                        size = 29.dp
                        )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.map_service), fontWeight = FontWeight.Bold, fontSize = 16.sp, color = currentPalette.label)
                        Text(
                            if (prefs.mapProvider == MapProvider.GOOGLE) stringResource(R.string.google_card_label) else stringResource(R.string.amap_card_label),
                            fontSize = 13.sp, color = currentPalette.labelSecondary
                        )
                    }
                    Icon(
                        imageVector = AppIcons.chevronRight,
                        contentDescription = null,
                        tint = currentPalette.labelSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            if (showMapProviderPicker) {
                AlertDialog(
                    onDismissRequest = { showMapProviderPicker = false },
                    title = { Text(stringResource(R.string.map_provider_selector)) },
                    text = {
                        Column {
                            Text(
                                stringResource(R.string.amap_recommendation_desc),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                            HorizontalDivider()
                            Text(
                                stringResource(R.string.google_recommendation_desc),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                stringResource(R.string.map_switch_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = NeonAmber
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            prefs.mapProvider = MapProvider.GOOGLE
                            showMapProviderPicker = false
                        }) {
                            Text(stringResource(R.string.google_card_label))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            prefs.mapProvider = MapProvider.AMAP
                            showMapProviderPicker = false
                        }) {
                            Text(stringResource(R.string.amap_card_label))
                        }
                    }
                )
            }

            // 语言切换
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showLanguagePicker = true },
                colors = CardDefaults.cardColors(containerColor = currentPalette.groupedCell),
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconTile(
                        icon = AppIcons.language,
                        containerColor = primaryColor(),
                        tint = Color.White,
                        size = 29.dp
                        )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.language), fontWeight = FontWeight.Bold, fontSize = 16.sp, color = currentPalette.label)
                        Text(
                            when (currentLanguage) {
                                AppLanguage.CHINESE -> stringResource(R.string.chinese)
                                AppLanguage.ENGLISH -> stringResource(R.string.english)
                                AppLanguage.MALAY -> stringResource(R.string.malay)
                            },
                            fontSize = 13.sp, color = currentPalette.labelSecondary
                        )
                    }
                    Icon(
                        imageVector = AppIcons.chevronRight,
                        contentDescription = null,
                        tint = currentPalette.labelSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            HorizontalDivider(color = currentPalette.separator)

            // 主题选择
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showThemePicker = true },
                colors = CardDefaults.cardColors(
                    containerColor = currentPalette.groupedCell
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconTile(
                            icon = AppIcons.palette,
                            containerColor = primaryColor(),
                            tint = Color.White,
                            size = 29.dp
                            )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.theme),
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = currentPalette.label
                            )
                            Text(
                                stringResource(
                                    when (themeController.settings.mode) {
                                        com.trackmaster.app.ui.design.ThemeMode.SYSTEM ->
                                            R.string.theme_mode_system
                                        com.trackmaster.app.ui.design.ThemeMode.LIGHT ->
                                            R.string.theme_mode_light
                                        com.trackmaster.app.ui.design.ThemeMode.DARK ->
                                            R.string.theme_mode_dark
                                    }
                                ),
                                fontSize = 13.sp,
                                color = currentPalette.labelSecondary
                            )
                        }
                        Icon(
                            imageVector = AppIcons.chevronRight,
                            contentDescription = null,
                            tint = currentPalette.labelSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            HorizontalDivider(color = currentPalette.separator)

            if (onOpenDataImportExport != null) {
                SettingsMenuCard(
                    icon = AppIcons.folder,
                    title = stringResource(R.string.data_import_export),
                    description = stringResource(R.string.data_import_export_desc),
                    tint = primaryColor(),
                    onClick = onOpenDataImportExport
                )
                HorizontalDivider(color = currentPalette.separator)
            }

            // 重新计算父子关系
            var showRecalculateDialog by remember { mutableStateOf(false) }
            var recalculateResult by remember {
                mutableStateOf<FenceRelationshipRecalculator.Result?>(null)
            }

            SettingsMenuCard(
                icon = AppIcons.hierarchy,
                title = stringResource(R.string.recalculate_relationships),
                description = stringResource(R.string.recalculate_desc),
                tint = NeonBlue,
                onClick = {
                    // 每次打开对话框都清掉上一次的结果，避免显示陈旧的成功/失败提示
                    recalculateResult = null
                    showRecalculateDialog = true
                }
            )

            if (showRecalculateDialog) {
                RecalculateRelationshipsDialog(
                    result = recalculateResult,
                    onConfirm = {
                        if (recalculateResult is FenceRelationshipRecalculator.Result.Success ||
                            recalculateResult is FenceRelationshipRecalculator.Result.HasIntersections
                        ) {
                            // 已经有结果了，这一下是"知道了"
                            showRecalculateDialog = false
                        } else {
                            recalculateResult = FenceRelationshipRecalculator(app).recalculate()
                        }
                    },
                    onDismiss = { showRecalculateDialog = false }
                )
            }


            HorizontalDivider(color = currentPalette.separator)

            // 高级设置
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showAdvancedSettings = !showAdvancedSettings },
                colors = CardDefaults.cardColors(
                    containerColor = currentPalette.groupedCell
                ),
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = AppIcons.tune,
                            contentDescription = stringResource(R.string.advanced_settings),
                            tint = primaryColor(),
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.advanced_settings),
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = currentPalette.label
                            )
                            Text(
                                stringResource(R.string.advanced_settings_desc),
                                fontSize = 13.sp,
                                color = currentPalette.labelSecondary
                            )
                        }
                        Icon(
                            imageVector = AppIcons.expandChevron(showAdvancedSettings),
                            contentDescription = if (showAdvancedSettings) stringResource(R.string.collapse) else stringResource(R.string.expand),
                            tint = currentPalette.labelSecondary,
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

            HorizontalDivider(color = currentPalette.separator)

            InfoSection()

            Spacer(modifier = Modifier.height(80.dp))
        }
    }

    // 语言选择对话框
    if (showLanguagePicker) {
        AlertDialog(
            onDismissRequest = { showLanguagePicker = false },
            title = { Text(stringResource(R.string.select_language)) },
            text = {
                Column {
                    listOf(
                        AppLanguage.CHINESE to R.string.chinese,
                        AppLanguage.ENGLISH to R.string.english,
                        AppLanguage.MALAY to R.string.malay
                    ).forEach { (lang, labelRes) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    LanguageManager.applyLocale(context, lang, currentLanguage) {
                                        currentLanguage = lang
                                    }
                                    prefs.appLanguage = lang
                                    onLanguageChangeRequested()
                                    showLanguagePicker = false
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (currentLanguage == lang) AppIcons.checkCircle else AppIcons.checkCircleOutline,
                                contentDescription = null,
                                tint = if (currentLanguage == lang) primaryColor() else Color.Gray,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                stringResource(labelRes),
                                fontSize = 16.sp,
                                color = Color.White
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showLanguagePicker = false }) {
                    Text(stringResource(R.string.cancel), color = Color.Gray)
                }
            }
        )
    }

    // 主题设置对话框：强调色 + 深浅模式
    if (showThemePicker) {
        com.trackmaster.app.ui.design.ThemeSettingsDialog(
            settings = themeController.settings,
            onSettingsChange = { themeController.update(it) },
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 彩色圆角方块里放白色图标：分组列表视觉，比裸图标更容易扫读
        com.trackmaster.app.ui.design.IconTile(
            icon = icon,
            containerColor = tint,
            tint = Color.White,
            size = 29.dp
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                fontWeight = FontWeight.Medium,
                fontSize = 17.sp,
                color = currentPalette.label
            )
            Text(
                description,
                fontSize = 13.sp,
                color = currentPalette.labelSecondary
            )
        }
        Icon(
            imageVector = AppIcons.chevronRight,
            contentDescription = null,
            tint = currentPalette.labelTertiary,
            modifier = Modifier.size(20.dp)
        )
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
                    imageVector = AppIcons.settings,
                    contentDescription = stringResource(R.string.settings),
                    tint = primaryColor(),
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(stringResource(R.string.app_settings), fontWeight = FontWeight.Bold, fontSize = 20.sp, color = primaryColor())
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
                Text(stringResource(R.string.close), color = Color.White)
            }
        }
    )
}

@Composable
fun InfoSection() {
    val palette = currentPalette
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // 这是说明性文字，不是可点的操作项。用次级灰而不是强调色——
        // 强调色留给真正能点、需要吸引注意的东西。
        Text(
            stringResource(R.string.usage_instructions),
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            color = palette.labelSecondary
        )
        Text(
            "1. ${stringResource(R.string.usage_tip_1)}\n2. ${stringResource(R.string.usage_tip_2)}\n3. ${stringResource(R.string.usage_tip_3)}\n4. ${stringResource(R.string.usage_tip_4)}",
            fontSize = 13.sp,
            color = palette.labelSecondary,
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
                        imageVector = AppIcons.star,
                        contentDescription = stringResource(R.string.recommended),
                        tint = Color(0xFF81C784),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.recommended_defaults),
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = Color(0xFF81C784)
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "• ${stringResource(R.string.default_config_stationary, 20)}\n• ${stringResource(R.string.default_config_buffer, 10)}\n• ${stringResource(R.string.default_config_drift, 30)}",
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
            Text(stringResource(R.string.reset_defaults), color = Color.White)
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
@Composable
private fun RecalculateRelationshipsDialog(
    result: FenceRelationshipRecalculator.Result?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val palette = currentPalette
    val hasResult = result != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(
                    if (hasResult) R.string.recalculate_title else R.string.recalculate_relationships
                ),
                style = com.trackmaster.app.ui.design.AppType.title2
            )
        },
        text = {
            Column {
                when (result) {
                    null -> Text(
                        text = stringResource(R.string.recalculate_confirm),
                        style = com.trackmaster.app.ui.design.AppType.body
                    )

                    is FenceRelationshipRecalculator.Result.HasIntersections -> Text(
                        text = stringResource(
                            R.string.intersection_warning,
                            result.pairCount
                        ),
                        style = com.trackmaster.app.ui.design.AppType.body,
                        color = palette.danger
                    )

                    is FenceRelationshipRecalculator.Result.Success -> Text(
                        text = stringResource(
                            R.string.recalculate_success,
                            result.relationshipCount
                        ),
                        style = com.trackmaster.app.ui.design.AppType.body,
                        color = palette.success
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(
                        if (hasResult) R.string.ok else R.string.confirm
                    ),
                    color = palette.accent
                )
            }
        },
        dismissButton = if (hasResult) {
            null
        } else {
            {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel), color = palette.labelSecondary)
                }
            }
        }
    )
}
