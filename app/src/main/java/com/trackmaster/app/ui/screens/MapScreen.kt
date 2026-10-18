package com.trackmaster.app.ui.screens

import android.graphics.Color as AndroidColor
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.Opacity
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Satellite
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material3.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.amap.api.location.AMapLocation
import com.trackmaster.app.R
import com.trackmaster.app.data.GeoFence
import com.trackmaster.app.data.GeoFencePoint
import com.trackmaster.app.core.GeoFenceType
import com.trackmaster.app.LocationServiceState
import com.trackmaster.app.locationservice.Location
import com.trackmaster.app.locationservice.LocationType
import com.trackmaster.app.TrackMasterApplication
import com.trackmaster.app.MapProvider
import com.trackmaster.app.ui.components.AMapViewWrapper
import com.trackmaster.app.ui.components.GoogleMapWrapper
import com.trackmaster.app.viewmodel.MapViewModel
import java.util.Random
import java.util.UUID
import com.trackmaster.app.ui.design.currentPalette
import com.trackmaster.app.ui.design.AppIcons
import androidx.compose.foundation.border

private fun AMapLocation.toLocation(): Location = Location(
    latitude = latitude,
    longitude = longitude,
    accuracy = accuracy,
    speed = speed,
    bearing = bearing,
    timestamp = time,
    locationType = when (locationType) {
        1 -> LocationType.GPS
        2 -> LocationType.NETWORK
        else -> LocationType.UNKNOWN
    }
)

@Composable
fun MapScreen(app: TrackMasterApplication, mapViewModel: MapViewModel) {
    var localFences by remember { mutableStateOf(app.geoFences.toList()) }
    var localMatchedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var localLocation by remember { mutableStateOf<Location?>(null) }

    // 绘制/编辑的状态机。原来这里是 8 个独立状态变量，
    // 现在收成三态（Idle / Drawing / Editing），互斥关系由类型保证。
    val editor = remember { com.trackmaster.app.ui.map.MapEditorState() }
    var mapType by remember { mutableStateOf(app.appPreferences.mapType) }

    var showFenceList by remember { mutableStateOf(false) }
    var selectedFence by remember { mutableStateOf<GeoFence?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf<GeoFence?>(null) }
    var showRecalculateHint by remember { mutableStateOf(false) }
    var showToolsPopup by remember { mutableStateOf(false) }

    val fenceColors = listOf(
        // 红色系
        "#FF0000", "#E53935", "#D32F2F", "#C62828",
        // 粉色系
        "#E91E63", "#F06292", "#EC407A", "#FF4081",
        // 紫色系
        "#9C27B0", "#AB47BC", "#7B1FA2", "#BA68C8",
        // 蓝色系
        "#3F51B5", "#5C6BC0", "#2196F3", "#03A9F4", "#00BCD4",
        // 青色系
        "#009688", "#26A69A", "#00897B", "#4DB6AC",
        // 绿色系
        "#4CAF50", "#66BB6A", "#43A047", "#8BC34A", "#00E676",
        // 黄色/金色系
        "#FFEB3B", "#FFC107", "#FF9800",
        // 橙/棕系
        "#FF5722", "#FF7043", "#795548", "#8D6E63",
        // 灰/中性
        "#607D8B", "#78909C", "#90A4AE", "#000000"
    )

    val fenceStateVersion by app.fenceStateVersion.collectAsState()
    val highlightedFenceId by app.highlightedFenceId.collectAsState()
    val displayedFenceId by app.displayedFenceId.collectAsState()

    LaunchedEffect(fenceStateVersion) {
        localFences = app.geoFences.toList()
        localMatchedIds = app.currentMatchedFences.map { it.id }.toSet()
    }

    DisposableEffect(Unit) {
        val service = app.getLocationService()
        val listener: (LocationServiceState) -> Unit = { state ->
            localLocation = state.lastLocation
            localMatchedIds = app.currentMatchedFences.map { it.id }.toSet()
        }
        service?.addStateListener(listener)
        onDispose {
            service?.removeStateListener(listener)
        }
    }

    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                localFences = app.geoFences.toList()
                localMatchedIds = app.currentMatchedFences.map { it.id }.toSet()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val onMapClick: (Double, Double) -> Unit = { lat, lng ->
        editor.onMapClick(lat, lng)
    }

    val currentMapProvider by app.appPreferences.mapProviderFlow.collectAsState()

    Box(modifier = Modifier.fillMaxSize()) {
        if (currentMapProvider == MapProvider.GOOGLE) {
            GoogleMapWrapper(
                modifier = Modifier.fillMaxSize(),
                currentLocation = localLocation,
                fences = localFences,
                currentMatchedFenceIds = localMatchedIds,
                highlightedFenceId = highlightedFenceId,
                displayedFenceId = displayedFenceId,
                isDrawingMode = editor.isDrawing,
                drawingPoints = editor.activePoints,
                selectedFenceType = editor.drawingType,
                isEditingFence = editor.isEditing,
                editingPoints = editor.activePoints,
                isPrecisionMode = editor.precisionActive,
                mapType = mapType,
                onMapClick = onMapClick
            )
        } else {
            AMapViewWrapper(
                modifier = Modifier.fillMaxSize(),
                currentLocation = localLocation,
                fences = localFences,
                currentMatchedFenceIds = localMatchedIds,
                highlightedFenceId = highlightedFenceId,
                displayedFenceId = displayedFenceId,
                isDrawingMode = editor.isDrawing,
                drawingPoints = editor.activePoints,
                selectedFenceType = editor.drawingType,
                isEditingFence = editor.isEditing,
                editingPoints = editor.activePoints,
                isPrecisionMode = editor.precisionActive,
                mapType = mapType,
                onMapClick = onMapClick
            )
        }

        TopStatusIsland(
            currentMatchedFenceIds = localMatchedIds,
            fences = localFences,
            displayedFenceId = displayedFenceId,
            mapType = mapType,
            showToolsPopup = showToolsPopup,
            onMapTypeChange = { newType ->
                mapType = newType
                app.appPreferences.mapType = newType
            },
            onFenceListClick = { showFenceList = true },
            onToolsClick = { showToolsPopup = !showToolsPopup },
            onFenceClick = { fenceId ->
                val targetId = if (displayedFenceId == fenceId) null else fenceId
                app.setDisplayedFenceId(targetId)
                app.setHighlightedFenceId(targetId)
            },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 8.dp)
        )

        if (showToolsPopup) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 60.dp)
            ) {
                com.trackmaster.app.ui.map.MapToolsPopup(
                    onApplyRandomColors = {
                        mapViewModel.applyRandomColors(fenceColors)
                        localFences = app.geoFences.toList()
                    },
                    onApplyGlobalAlpha = { alpha ->
                        mapViewModel.applyGlobalAlpha(alpha)
                        localFences = app.geoFences.toList()
                    },
                    onDismiss = { showToolsPopup = false }
                )
            }
        }

        AnimatedVisibility(
            visible = editor.isDrawing,
            enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 72.dp)
                .padding(horizontal = 16.dp)
        ) {
            DrawingToolbar(
                fenceType = editor.drawingType,
                drawingPoints = editor.activePoints,
                selectedColor = editor.drawingColor,
                selectedAlpha = editor.drawingAlpha,
                fenceColors = fenceColors,
                isPrecisionMode = editor.drawingPrecision,
                onPrecisionModeChange = { editor.drawingPrecision = it },
                onFenceTypeChange = { editor.setDrawingType(it) },
                onColorChange = { editor.drawingColor = it },
                onAlphaChange = { editor.drawingAlpha = it },
                onUndo = { editor.undoLastPoint() },
                onCancel = { editor.cancel() },
                onCreate = { showCreateDialog = true }
            )
        }

        // 编辑模式下 editingFence 一定非空——由 MapEditMode.Editing 保证，
        // 不需要再写 `isEditingFence && editingFence != null` 这种双重判断。
        (editor.mode as? com.trackmaster.app.ui.map.MapEditMode.Editing)?.let { editing ->
            AnimatedVisibility(
                visible = true,
                enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 72.dp)
                    .padding(horizontal = 16.dp)
            ) {
                EditingToolbar(
                    fence = editing.fence,
                    editingPoints = editing.points,
                    isPrecisionMode = editor.editingPrecision,
                    onPrecisionModeChange = { editor.editingPrecision = it },
                    onUndo = { editor.undoLastPoint() },
                    onCancel = { editor.cancel() },
                    onSave = { updatedFence ->
                        mapViewModel.saveFence(updatedFence)
                        editor.cancel()
                        selectedFence = null
                    }
                )
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 撤销按钮：绘制和编辑共用同一个动作，只在有点可撤时出现
            if (editor.consumesMapClicks && editor.activePoints.isNotEmpty()) {
                SmallFloatingActionButton(
                    onClick = { editor.undoLastPoint() },
                    containerColor = currentPalette.warning,
                    contentColor = currentPalette.onAccent
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Undo,
                        contentDescription = stringResource(R.string.undo)
                    )
                }
            }

            FloatingActionButton(
                onClick = {
                    if (editor.consumesMapClicks) editor.cancel() else editor.startDrawing()
                },
                containerColor = when {
                    editor.consumesMapClicks -> currentPalette.danger
                    else -> currentPalette.accent
                },
                contentColor = currentPalette.onAccent
            ) {
                Icon(
                    imageVector = when {
                        editor.consumesMapClicks -> AppIcons.close
                        else -> AppIcons.add
                    },
                    contentDescription = if (editor.consumesMapClicks) {
                        stringResource(R.string.cancel)
                    } else {
                        stringResource(R.string.draw)
                    }
                )
            }
        }
    }

    if (showCreateDialog) {
        val drawnPoints = editor.activePoints
        com.trackmaster.app.ui.map.CreateFenceDialog(
            fenceType = editor.drawingType,
            points = drawnPoints,
            color = editor.drawingColor,
            alpha = editor.drawingAlpha,
            onDismiss = {
                showCreateDialog = false
                editor.cancel()
            },
            onConfirm = { name, color, alpha, radius ->
                val fence = when (editor.drawingType) {
                    GeoFenceType.CIRCLE -> {
                        if (drawnPoints.isEmpty()) {
                            showCreateDialog = false
                            return@CreateFenceDialog
                        }
                        GeoFence(
                            id = UUID.randomUUID().toString(),
                            name = name,
                            type = GeoFenceType.CIRCLE,
                            center = drawnPoints.first(),
                            radius = radius,
                            color = color,
                            alpha = alpha
                        )
                    }

                    GeoFenceType.POLYGON -> {
                        if (drawnPoints.size < 3) {
                            showCreateDialog = false
                            return@CreateFenceDialog
                        }
                        GeoFence(
                            id = UUID.randomUUID().toString(),
                            name = name,
                            type = GeoFenceType.POLYGON,
                            points = drawnPoints,
                            color = color,
                            alpha = alpha
                        )
                    }
                }

                mapViewModel.addFence(fence)

                showCreateDialog = false
                showRecalculateHint = true
                editor.cancel()
            }
        )
    }

    // 创建成功后引导去设置重新计算父子关系
    if (showRecalculateHint) {
        AlertDialog(
            onDismissRequest = { showRecalculateHint = false },
            title = { Text(stringResource(R.string.fence_created)) },
            text = {
                Text(stringResource(R.string.recalculate_hint))
            },
            confirmButton = {
                Button(onClick = { showRecalculateHint = false }) {
                    Text(stringResource(R.string.ok))
                }
            }
        )
    }

    if (showFenceList) {
        com.trackmaster.app.ui.map.FenceListDialog(
            fences = localFences,
            fenceTreeManager = app.fenceTreeManager,
            currentMatchedIds = localMatchedIds,
            highlightedFenceId = highlightedFenceId,
            onDismiss = {
                showFenceList = false
                // 关闭对话框时保持高亮状态，直到用户再次点击同一围栏
            },
            onHighlightClick = { fence ->
                // 点击列表围栏时显示围栏的所有点，跳转并高亮显示
                val targetId = if (displayedFenceId == fence.id) null else fence.id
                app.setDisplayedFenceId(targetId)
                app.setHighlightedFenceId(targetId)
            },
            onEditClick = { fence ->
                selectedFence = fence
                showFenceList = false
                showEditDialog = true
            },
            onDeleteClick = { fence ->
                showFenceList = false
                showDeleteConfirm = fence
            }
        )
    }

    selectedFence?.let { fence ->
        if (showEditDialog) {
            com.trackmaster.app.ui.map.EditFenceDialog(
                fence = fence,
                fenceColors = fenceColors,
            onDismiss = {
                showEditDialog = false
                selectedFence = null
            },
            onConfirm = { updatedFence ->
                // 进入编辑模式：顶点从围栏带过来，之后地图点击会往上加
                editor.startEditing(updatedFence)
                showEditDialog = false
            },
                onDelete = {
                    showEditDialog = false
                    showDeleteConfirm = selectedFence
                    selectedFence = null
                }
            )
        }
    }

    showDeleteConfirm?.let { fenceToDelete ->
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = null },
            title = { Text(stringResource(R.string.confirm_delete)) },
            text = { Text(stringResource(R.string.fence_delete_confirm, fenceToDelete.name)) },
            confirmButton = {
                Button(
                    onClick = {
                        mapViewModel.deleteFence(fenceToDelete.id)
                        showDeleteConfirm = null
                        if (selectedFence?.id == fenceToDelete.id) selectedFence = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = currentPalette.danger)
                ) {
                    Text(stringResource(R.string.delete), color = currentPalette.onDanger)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun TopStatusIsland(
    currentMatchedFenceIds: Set<String>,
    fences: List<GeoFence>,
    displayedFenceId: String?,
    mapType: Int,
    showToolsPopup: Boolean,
    onMapTypeChange: (Int) -> Unit,
    onFenceListClick: () -> Unit,
    onToolsClick: () -> Unit,
    onFenceClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = currentPalette

    Box(
        modifier = modifier
            .height(48.dp)
            .clip(RoundedCornerShape(24.dp))
            // 地图上方是浮动控件，用不透明卡片底保证图标始终可读；
            // 半透明在浅色地图上会糊成一片
            .background(palette.groupedCell)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            // 找出所有匹配的围栏中最小的一个（面积最小）
            //
            // 面积用 GeoCalculator 里的实现（带 cos(纬度) 校正）。
            // 这里曾经有一份自己的鞋带公式，直接把经纬度平方乘 111000²，
            // 没有考虑经度随纬度收缩——在北纬 40 度会把多边形面积高估约 30%，
            // 导致和圆形围栏比较时选错"最小围栏"。
            val matchedFences = fences.filter { it.id in currentMatchedFenceIds }
            val smallestFence = matchedFences.minByOrNull {
                com.trackmaster.app.utils.geometry.calculateFenceArea(
                    com.trackmaster.app.core.buildEngineGeofence(it)
                )
            }

            if (smallestFence != null) {
                Icon(
                    AppIcons.insideFence,
                    contentDescription = null,
                    tint = palette.success,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = smallestFence.name,
                    fontSize = 14.sp,
                    color = if (displayedFenceId == smallestFence.id) palette.accent else palette.label,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable { onFenceClick(smallestFence.id) }
                )
            } else {
                Icon(
                    AppIcons.outsideFence,
                    contentDescription = null,
                    tint = palette.labelSecondary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.fence_count, fences.size),
                    fontSize = 14.sp,
                    color = palette.label,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(Modifier.width(16.dp))
            Box(
                Modifier
                    .width(1.dp)
                    .height(20.dp)
                    .background(palette.separator)
            )
            Spacer(Modifier.width(16.dp))
            // 地图图层切换按钮
            val nextMapType = if (mapType >= 3) 1 else mapType + 1
            val mapTypeIcon = when (mapType) {
                2 -> Icons.Default.Satellite
                3 -> Icons.Default.NightsStay
                else -> Icons.Default.Map
            }
            val mapTypeDesc = when (mapType) {
                1 -> stringResource(R.string.map_type_normal)
                2 -> stringResource(R.string.map_type_satellite)
                else -> stringResource(R.string.map_type_night)
            }
            Icon(
                mapTypeIcon,
                contentDescription = stringResource(R.string.switch_map_layer, mapTypeDesc),
                tint = palette.accent,
                modifier = Modifier
                    .size(20.dp)
                    .clickable { onMapTypeChange(nextMapType) }
            )
            Spacer(Modifier.width(16.dp))
            Icon(
                AppIcons.settings,
                contentDescription = stringResource(R.string.tools),
                tint = if (showToolsPopup) palette.accent else palette.labelSecondary,
                modifier = Modifier
                    .size(20.dp)
                    .clickable { onToolsClick() }
            )
            Spacer(Modifier.width(16.dp))
            Icon(
                AppIcons.navHistorySelected,
                contentDescription = stringResource(R.string.fence_list),
                tint = palette.labelSecondary,
                modifier = Modifier
                    .size(20.dp)
                    .clickable { onFenceListClick() }
            )
        }
    }
}

@Composable
private fun DrawingToolbar(
    fenceType: GeoFenceType,
    drawingPoints: List<GeoFencePoint>,
    selectedColor: String,
    selectedAlpha: Float,
    fenceColors: List<String>,
    isPrecisionMode: Boolean,
    onPrecisionModeChange: (Boolean) -> Unit,
    onFenceTypeChange: (GeoFenceType) -> Unit,
    onColorChange: (String) -> Unit,
    onAlphaChange: (Float) -> Unit,
    onUndo: () -> Unit,
    onCancel: () -> Unit,
    onCreate: () -> Unit
) {
    // 紧凑型工具栏，使用两行布局减少垂直高度
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = currentPalette.groupedCell.copy(alpha = 0.95f),
        tonalElevation = 4.dp
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // 第一行：类型选择和操作按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 围栏类型切换（有点位后禁止切换，避免圆/多边形围栏相交）
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(
                        selected = fenceType == GeoFenceType.CIRCLE,
                        onClick = { if (drawingPoints.isEmpty()) onFenceTypeChange(GeoFenceType.CIRCLE) },
                        label = { Text(stringResource(R.string.circle), fontSize = 12.sp) },
                        leadingIcon = { Icon(Icons.Filled.Circle, contentDescription = null, modifier = Modifier.size(14.dp)) },
                        modifier = Modifier.height(32.dp),
                        enabled = drawingPoints.isEmpty()
                    )
                    Spacer(Modifier.width(6.dp))
                    FilterChip(
                        selected = fenceType == GeoFenceType.POLYGON,
                        onClick = { if (drawingPoints.isEmpty()) onFenceTypeChange(GeoFenceType.POLYGON) },
                        label = { Text(stringResource(R.string.polygon), fontSize = 12.sp) },
                        leadingIcon = { Icon(Icons.Filled.Category, contentDescription = null, modifier = Modifier.size(14.dp)) },
                        modifier = Modifier.height(32.dp),
                        enabled = drawingPoints.isEmpty()
                    )
                    Spacer(Modifier.width(6.dp))
                    FilterChip(
                        selected = isPrecisionMode,
                        onClick = { onPrecisionModeChange(!isPrecisionMode) },
                        label = { Text(stringResource(R.string.precision), fontSize = 12.sp) },
                        leadingIcon = { Icon(AppIcons.myLocation, contentDescription = null, modifier = Modifier.size(14.dp)) },
                        modifier = Modifier.height(32.dp)
                    )
                }

                Spacer(Modifier.weight(1f))

                // 操作按钮
                IconButton(
                    onClick = onUndo,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Undo,
                        contentDescription = stringResource(R.string.undo),
                        tint = if (drawingPoints.isNotEmpty()) currentPalette.accent else currentPalette.labelTertiary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                IconButton(
                    onClick = onCancel,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        AppIcons.close,
                        contentDescription = stringResource(R.string.cancel),
                        tint = currentPalette.labelSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // 第二行：颜色和透明度
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 颜色选择器（可横向滚动）
                Text(stringResource(R.string.color) + ":", fontSize = 10.sp, color = currentPalette.labelSecondary)
                Spacer(Modifier.width(6.dp))
                Row(
                    modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    fenceColors.forEach { c ->
                        val sel = selectedColor == c
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(Color(AndroidColor.parseColor(c)))
                                .then(if (sel) Modifier.border(2.dp, currentPalette.label, CircleShape) else Modifier)
                                .clickable { onColorChange(c) }
                        )
                    }
                }

                Spacer(Modifier.width(8.dp))

                // 透明度
                Text(stringResource(R.string.transparency, (selectedAlpha * 100).toInt()), fontSize = 10.sp, color = currentPalette.labelSecondary)
                Slider(
                    value = selectedAlpha,
                    onValueChange = onAlphaChange,
                    modifier = Modifier.weight(1f).height(24.dp),
                    valueRange = 0.1f..0.8f,
                    steps = 6
                )
            }

            Spacer(Modifier.height(8.dp))

            // 第三行：状态提示和创建按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    when (fenceType) {
                        GeoFenceType.CIRCLE -> if (drawingPoints.isEmpty()) stringResource(R.string.tap_map_for_center) else stringResource(R.string.center_selected_radius)
                        GeoFenceType.POLYGON -> {
                            val remaining = 3 - drawingPoints.size
                            if (remaining > 0) stringResource(R.string.need_n_vertices, remaining) else stringResource(R.string.ready_to_create, drawingPoints.size)
                        }
                    },
                    fontSize = 11.sp,
                    color = currentPalette.accent,
                    modifier = Modifier.weight(1f)
                )

                Button(
                    onClick = onCreate,
                    enabled = when (fenceType) {
                        GeoFenceType.CIRCLE -> drawingPoints.isNotEmpty()
                        GeoFenceType.POLYGON -> drawingPoints.size >= 3
                    },
                    modifier = Modifier.height(36.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = currentPalette.accent),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp)
                ) {
                    Text(stringResource(R.string.create), fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun EditingToolbar(
    fence: GeoFence?,
    editingPoints: List<GeoFencePoint>,
    isPrecisionMode: Boolean,
    onPrecisionModeChange: (Boolean) -> Unit,
    onUndo: () -> Unit,
    onCancel: () -> Unit,
    onSave: (GeoFence) -> Unit
) {
    if (fence == null) return
    com.trackmaster.app.ui.design.SurfaceCard(Modifier.fillMaxWidth()) {
        Column {
            Row(Modifier.fillMaxWidth().padding(8.dp)) {
                Text(
                    // 圆形和多边形的编辑语义不同：圆形加的是新圆心，多边形才是顶点
                    stringResource(
                        when (fence.type) {
                            GeoFenceType.CIRCLE -> R.string.editing_circle_tap_to_move
                            GeoFenceType.POLYGON -> R.string.editing_fence_tap_to_add
                        },
                        fence.name
                    ),
                    fontSize = 12.sp,
                    color = currentPalette.accent,
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = isPrecisionMode,
                    onClick = { onPrecisionModeChange(!isPrecisionMode) },
                    label = { Text(stringResource(R.string.precision), fontSize = 11.sp) },
                    leadingIcon = { Icon(AppIcons.myLocation, contentDescription = null, modifier = Modifier.size(14.dp)) },
                    modifier = Modifier.height(28.dp)
                )
                IconButton(onClick = onUndo) {
                    Icon(
                        Icons.AutoMirrored.Filled.Undo,
                        contentDescription = stringResource(R.string.delete_last),
                        tint = if (editingPoints.isNotEmpty()) currentPalette.accent else currentPalette.labelTertiary
                    )
                }
                IconButton(onClick = onCancel) {
                    Icon(AppIcons.close, contentDescription = stringResource(R.string.cancel), tint = currentPalette.labelSecondary)
                }
            }

            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                Button(
                    onClick = {
                        when (fence.type) {
                            GeoFenceType.POLYGON -> {
                                if (editingPoints.size >= 3) onSave(fence.copy(points = editingPoints))
                            }
                            GeoFenceType.CIRCLE -> {
                                if (editingPoints.size >= 1) {
                                    onSave(fence.copy(center = editingPoints.firstOrNull()))
                                }
                            }
                        }
                    },
                    enabled = when (fence.type) {
                        GeoFenceType.POLYGON -> editingPoints.size >= 3
                        GeoFenceType.CIRCLE -> editingPoints.size >= 1
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = currentPalette.success)
                ) {
                    Text(stringResource(R.string.save), color = currentPalette.onDanger)
                }
            }
        }
    }
}

