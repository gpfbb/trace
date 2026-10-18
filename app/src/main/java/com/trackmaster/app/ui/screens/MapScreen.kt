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
import com.trackmaster.app.ui.theme.*
import com.trackmaster.app.viewmodel.MapViewModel
import java.util.Random
import java.util.UUID
import com.trackmaster.app.ui.design.currentPalette
import com.trackmaster.app.ui.design.AppIcons

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

    var isDrawingMode by remember { mutableStateOf(false) }
    var selectedFenceType by remember { mutableStateOf(GeoFenceType.CIRCLE) }
    var drawingPoints by remember { mutableStateOf<List<GeoFencePoint>>(emptyList()) }
    var selectedColor by remember { mutableStateOf("#FF0000") }
    var selectedAlpha by remember { mutableStateOf(0.3f) }
    var drawingPrecisionMode by remember { mutableStateOf(false) }
    var editingPrecisionMode by remember { mutableStateOf(false) }
    var mapType by remember { mutableStateOf(app.appPreferences.mapType) }

    var showFenceList by remember { mutableStateOf(false) }
    var selectedFence by remember { mutableStateOf<GeoFence?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf<GeoFence?>(null) }
    var showRecalculateHint by remember { mutableStateOf(false) }

    var isEditingFence by remember { mutableStateOf(false) }
    var editingPoints by remember { mutableStateOf<List<GeoFencePoint>>(emptyList()) }
    var editingFence by remember { mutableStateOf<GeoFence?>(null) }
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
        when {
            isDrawingMode -> {
                drawingPoints = drawingPoints + GeoFencePoint(lat, lng)
            }
            isEditingFence && editingFence != null -> {
                editingPoints = editingPoints + GeoFencePoint(lat, lng)
            }
        }
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
                isDrawingMode = isDrawingMode,
                drawingPoints = drawingPoints,
                selectedFenceType = selectedFenceType,
                isEditingFence = isEditingFence,
                editingPoints = editingPoints,
                isPrecisionMode = drawingPrecisionMode || editingPrecisionMode,
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
                isDrawingMode = isDrawingMode,
                drawingPoints = drawingPoints,
                selectedFenceType = selectedFenceType,
                isEditingFence = isEditingFence,
                editingPoints = editingPoints,
                isPrecisionMode = drawingPrecisionMode || editingPrecisionMode,
                mapType = mapType,
                onMapClick = onMapClick
            )
        }

        TopStatusIsland(
            currentMatchedFenceIds = localMatchedIds,
            fences = localFences,
            highlightedFenceId = highlightedFenceId,
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
                ToolsPopup(
                    fences = localFences,
                    fenceColors = fenceColors,
                    app = app,
                    onDismiss = { showToolsPopup = false },
                    onFencesUpdated = {
                        localFences = app.geoFences.toList()
                    }
                )
            }
        }

        AnimatedVisibility(
            visible = isDrawingMode,
            enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 72.dp)
                .padding(horizontal = 16.dp)
        ) {
            DrawingToolbar(
                fenceType = selectedFenceType,
                drawingPoints = drawingPoints,
                selectedColor = selectedColor,
                selectedAlpha = selectedAlpha,
                fenceColors = fenceColors,
                isPrecisionMode = drawingPrecisionMode,
                onPrecisionModeChange = { drawingPrecisionMode = it },
                onFenceTypeChange = { selectedFenceType = it },
                onColorChange = { selectedColor = it },
                onAlphaChange = { selectedAlpha = it },
                onUndo = { drawingPoints = drawingPoints.dropLast(1) },
                onCancel = {
                    isDrawingMode = false
                    drawingPoints = emptyList()
                },
                onCreate = { showCreateDialog = true }
            )
        }

        AnimatedVisibility(
            visible = isEditingFence && editingFence != null,
            enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 72.dp)
                .padding(horizontal = 16.dp)
        ) {
            EditingToolbar(
                fence = editingFence,
                editingPoints = editingPoints,
                isPrecisionMode = editingPrecisionMode,
                onPrecisionModeChange = { editingPrecisionMode = it },
                onUndo = { editingPoints = editingPoints.dropLast(1) },
                onCancel = {
                    isEditingFence = false
                    editingFence = null
                    editingPoints = emptyList()
                },
                onSave = { updatedFence ->
                    mapViewModel.saveFence(updatedFence)
                    isEditingFence = false
                    editingFence = null
                    editingPoints = emptyList()
                    selectedFence = null
                }
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (isEditingFence && editingFence != null) {
                SmallFloatingActionButton(
                    onClick = { editingPoints = editingPoints.dropLast(1) },
                    containerColor = currentPalette.warning,
                    contentColor = currentPalette.onAccent
                ) {
                    Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = stringResource(R.string.undo))
                }
            }

            if (isDrawingMode && drawingPoints.isNotEmpty()) {
                SmallFloatingActionButton(
                    onClick = { drawingPoints = drawingPoints.dropLast(1) },
                    containerColor = currentPalette.warning,
                    contentColor = currentPalette.onAccent
                ) {
                    Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = stringResource(R.string.undo))
                }
            }

            FloatingActionButton(
                onClick = {
                    when {
                        isDrawingMode -> {
                            isDrawingMode = false
                            drawingPoints = emptyList()
                        }
                        isEditingFence -> {
                            isEditingFence = false
                            editingFence = null
                            editingPoints = emptyList()
                        }
                        else -> {
                            isDrawingMode = true
                            selectedFenceType = GeoFenceType.CIRCLE
                        }
                    }
                },
                containerColor = when {
                    isDrawingMode || isEditingFence -> Danger
                    else -> currentPalette.accent
                },
                contentColor = currentPalette.onAccent
            ) {
                Icon(
                    imageVector = when {
                        isDrawingMode || isEditingFence -> AppIcons.close
                        else -> AppIcons.add
                    },
                    contentDescription = if (isDrawingMode || isEditingFence) stringResource(R.string.cancel) else stringResource(R.string.draw)
                )
            }
        }
    }

    if (showCreateDialog) {
        CreateFenceDialog(
            fenceType = selectedFenceType,
            points = drawingPoints,
            color = selectedColor,
            alpha = selectedAlpha,
            onDismiss = {
                showCreateDialog = false
                drawingPoints = emptyList()
                isDrawingMode = false
            },
            onConfirm = { name, color, alpha, radius ->
                val fence = when (selectedFenceType) {
                    GeoFenceType.CIRCLE -> {
                        if (drawingPoints.isEmpty()) {
                            showCreateDialog = false
                            return@CreateFenceDialog
                        }
                        GeoFence(
                            id = UUID.randomUUID().toString(),
                            name = name,
                            type = GeoFenceType.CIRCLE,
                            center = drawingPoints.first(),
                            radius = radius,
                            color = color,
                            alpha = alpha
                        )
                    }
                    GeoFenceType.POLYGON -> {
                        if (drawingPoints.size < 3) {
                            showCreateDialog = false
                            return@CreateFenceDialog
                        }
                        GeoFence(
                            id = UUID.randomUUID().toString(),
                            name = name,
                            type = GeoFenceType.POLYGON,
                            points = drawingPoints,
                            color = color,
                            alpha = alpha
                        )
                    }
                }

                mapViewModel.addFence(fence)

                showCreateDialog = false
                showRecalculateHint = true
                drawingPoints = emptyList()
                isDrawingMode = false
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
        FenceListDialog(
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
            EditFenceDialog(
                fence = fence,
                fenceColors = fenceColors,
            onDismiss = {
                showEditDialog = false
                selectedFence = null
            },
            onConfirm = { updatedFence ->
                editingFence = updatedFence
                editingPoints = updatedFence.points?.toList() ?: emptyList()
                isEditingFence = true
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
                    Text(stringResource(R.string.delete), color = Color.White)
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
    highlightedFenceId: String?,
    displayedFenceId: String?,
    mapType: Int,
    showToolsPopup: Boolean,
    onMapTypeChange: (Int) -> Unit,
    onFenceListClick: () -> Unit,
    onToolsClick: () -> Unit,
    onFenceClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    // 计算围栏面积（用于找出最小的围栏）
    fun calculateFenceArea(fence: GeoFence): Double {
        return when (fence.type) {
            GeoFenceType.CIRCLE -> {
                val r = (fence.radius ?: 0f).toDouble()
                Math.PI * r * r
            }
            GeoFenceType.POLYGON -> {
                // 使用鞋带公式计算多边形面积
                val pts = fence.points ?: return 0.0
                if (pts.size < 3) return 0.0
                var area = 0.0
                val n = pts.size
                for (i in 0 until n) {
                    val j = (i + 1) % n
                    area += pts[i].longitude * pts[j].latitude
                    area -= pts[j].longitude * pts[i].latitude
                }
                kotlin.math.abs(area) / 2.0 * 111000.0 * 111000.0 // approximate m² conversion
            }
        }
    }

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
            val matchedFences = fences.filter { it.id in currentMatchedFenceIds }
            val smallestFence = matchedFences.minByOrNull { calculateFenceArea(it) }

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
private fun ToolsPopup(
    fences: List<GeoFence>,
    fenceColors: List<String>,
    app: TrackMasterApplication,
    onDismiss: () -> Unit,
    onFencesUpdated: () -> Unit
) {
    var globalAlpha by remember { mutableStateOf(0.3f) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 40.dp),
        shape = RoundedCornerShape(16.dp),
        color = glassPanel().copy(alpha = 0.95f),
        tonalElevation = 8.dp
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
                    stringResource(R.string.batch_tools),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = NeonCoral
                )
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.close), fontSize = 12.sp, color = Color.Gray)
                }
            }

            // Random Color Button
            GlassButton(
                onClick = {
                    val rng = Random()
                    fences.forEach { fence ->
                        val newColor = fenceColors[rng.nextInt(fenceColors.size)]
                        app.dbHelper.updateFence(fence.copy(color = newColor))
                    }
                    app.geoFences.clear()
                    app.geoFences.addAll(app.dbHelper.getAllFences())
                    app.refreshEngineFences()
                    onFencesUpdated()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.Palette, contentDescription = null, tint = currentPalette.accent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.random_colors), color = NeonAmber, fontSize = 13.sp)
            }

            // Global Transparency
            Text(stringResource(R.string.global_transparency, (globalAlpha * 100).toInt()), fontSize = 12.sp, color = Color.Gray)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("10%", fontSize = 10.sp, color = Color.Gray)
                Slider(
                    value = globalAlpha,
                    onValueChange = { globalAlpha = it },
                    modifier = Modifier.weight(1f).height(24.dp),
                    valueRange = 0.1f..0.8f,
                    steps = 6,
                    colors = SliderDefaults.colors(thumbColor = NeonCoral, activeTrackColor = NeonCoral)
                )
                Text("80%", fontSize = 10.sp, color = Color.Gray)
            }
            GlassButton(
                onClick = {
                    app.dbHelper.updateAllFencesAlpha(globalAlpha)
                    app.geoFences.clear()
                    app.geoFences.addAll(app.dbHelper.getAllFences())
                    app.refreshEngineFences()
                    onFencesUpdated()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.Opacity, contentDescription = null, tint = NeonBlue, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.apply_transparency), color = NeonBlue, fontSize = 13.sp)
            }
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
        color = glassPanel().copy(alpha = 0.95f),
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
                        tint = if (drawingPoints.isNotEmpty()) NeonAmber else Color.Gray,
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
                        tint = Color.Gray,
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
                Text(stringResource(R.string.color) + ":", fontSize = 10.sp, color = Color.Gray)
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
                                .then(if (sel) Modifier.background(Color.White.copy(alpha = 0.5f), CircleShape) else Modifier)
                                .clickable { onColorChange(c) }
                        )
                    }
                }

                Spacer(Modifier.width(8.dp))

                // 透明度
                Text(stringResource(R.string.transparency, (selectedAlpha * 100).toInt()), fontSize = 10.sp, color = Color.Gray)
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
                    color = primaryColor(),
                    modifier = Modifier.weight(1f)
                )

                Button(
                    onClick = onCreate,
                    enabled = when (fenceType) {
                        GeoFenceType.CIRCLE -> drawingPoints.isNotEmpty()
                        GeoFenceType.POLYGON -> drawingPoints.size >= 3
                    },
                    modifier = Modifier.height(36.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = primaryColor()),
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
    GlassCard(Modifier.fillMaxWidth()) {
        Column {
            Row(Modifier.fillMaxWidth().padding(8.dp)) {
                Text(
                    stringResource(R.string.editing_fence_tap_to_add, fence.name),
                    fontSize = 12.sp,
                    color = NeonBlue,
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
                        tint = if (editingPoints.isNotEmpty()) NeonAmber else Color.Gray
                    )
                }
                IconButton(onClick = onCancel) {
                    Icon(AppIcons.close, contentDescription = stringResource(R.string.cancel), tint = Color.Gray)
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
                    colors = ButtonDefaults.buttonColors(containerColor = Success)
                ) {
                    Text(stringResource(R.string.save), color = Color.White)
                }
            }
        }
    }
}

@Composable
private fun CreateFenceDialog(
    fenceType: GeoFenceType,
    points: List<GeoFencePoint>,
    color: String,
    alpha: Float,
    onDismiss: () -> Unit,
    onConfirm: (name: String, color: String, alpha: Float, radius: Float) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var radius by remember { mutableStateOf(100f) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.create_fence)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.fence_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                when (fenceType) {
                    GeoFenceType.CIRCLE -> {
                        Text(stringResource(R.string.radius_meters, radius.toInt()), fontSize = 14.sp)
                        Slider(
                            value = radius,
                            onValueChange = { radius = it },
                            valueRange = 50f..1000f,
                            steps = 18
                        )
                    }
                    GeoFenceType.POLYGON -> Text(stringResource(R.string.vertices_count, points.size), fontSize = 14.sp, color = Color.Gray)
                }
                Text(
                    stringResource(R.string.fence_hint),
                    fontSize = 11.sp,
                    color = NeonBlue
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(name, color, alpha, radius) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.create)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

@Composable
private fun FenceListDialog(
    fences: List<GeoFence>,
    fenceTreeManager: com.trackmaster.app.core.FenceTreeManager,
    currentMatchedIds: Set<String>,
    highlightedFenceId: String?,
    onDismiss: () -> Unit,
    onHighlightClick: (GeoFence) -> Unit,
    onEditClick: (GeoFence) -> Unit,
    onDeleteClick: (GeoFence) -> Unit
) {
    var expandedFenceIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    // 获取父级映射 - 使用 version 作为 key 确保重新计算后能刷新
    val parentMap by remember(fenceTreeManager.version) {
        mutableStateOf(fenceTreeManager.getParentMap())
    }

    // 计算围栏深度
    fun calculateDepth(fence: GeoFence): Int {
        var depth = 0
        var currentId: String? = parentMap[fence.id]
        while (currentId != null) {
            depth++
            currentId = parentMap[currentId]
        }
        return depth
    }

    // 检查是否有子围栏
    fun hasChildren(fence: GeoFence): Boolean {
        return parentMap.values.contains(fence.id)
    }

    // 构建可见围栏的扁平列表（用于 LazyColumn）
    fun buildVisibleFenceList(
        allFences: List<GeoFence>,
        pMap: Map<String, String>,
        expanded: Set<String>
    ): List<Pair<GeoFence, Int>> {
        if (allFences.isEmpty()) return emptyList()

        val fenceById = allFences.associateBy { it.id }

        // 构建 parentId -> children 映射
        val childrenMap = mutableMapOf<String?, MutableList<String>>()
        for (fence in allFences) {
            val parentId = pMap[fence.id]
            childrenMap.getOrPut(parentId) { mutableListOf() }.add(fence.id)
        }

        val result = mutableListOf<Pair<GeoFence, Int>>()

        // 递归添加可见围栏
        fun addVisible(parentId: String?, depth: Int) {
            val children = childrenMap[parentId] ?: return
            for (childId in children) {
                val child = fenceById[childId]
                if (child != null) {
                    result.add(child to depth)
                    // 如果这个围栏被展开了，递归添加子围栏
                    if (childId in expanded) {
                        addVisible(childId, depth + 1)
                    }
                }
            }
        }

        // 从顶级围栏开始（parentId 为 null）
        addVisible(null, 0)
        return result
    }

    // 根据 expandedFenceIds 过滤可见围栏，构建扁平化列表
    val visibleFences by remember(expandedFenceIds, fences, parentMap) {
        mutableStateOf(buildVisibleFenceList(fences, parentMap, expandedFenceIds))
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .fillMaxHeight(0.7f),
            shape = RoundedCornerShape(28.dp),
            color = glassPanel()
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // 标题栏
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.Map,
                            contentDescription = null,
                            tint = NeonBlue,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = stringResource(R.string.fence_list_title, visibleFences.size),
                            style = MaterialTheme.typography.titleLarge,
                            color = Color.White
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = AppIcons.close,
                            contentDescription = stringResource(R.string.close),
                            tint = Color.Gray
                        )
                    }
                }

                HorizontalDivider(color = Color.Gray.copy(alpha = 0.3f))

                if (visibleFences.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = AppIcons.outsideFence,
                                contentDescription = null,
                                tint = Color.Gray,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(stringResource(R.string.no_fences), color = Color.Gray, fontSize = 16.sp)
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        items(visibleFences.size) { i ->
                            val (fence, depth) = visibleFences[i]
                            val matched = fence.id in currentMatchedIds
                            val isHighlighted = fence.id == highlightedFenceId
                            val indentPadding = (depth * 16).dp
                            val fenceHasChildren = hasChildren(fence)
                            val isExpanded = fence.id in expandedFenceIds

                            Column {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(
                                            when {
                                                isHighlighted -> NeonAmber.copy(alpha = 0.2f)
                                                matched -> Success.copy(alpha = 0.1f)
                                                else -> Color.Transparent
                                            }
                                        )
                                        .clickable { onHighlightClick(fence) }
                                        .padding(start = indentPadding + 12.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (fenceHasChildren) {
                                        IconButton(
                                            onClick = {
                                                expandedFenceIds = if (isExpanded) {
                                                    expandedFenceIds - fence.id
                                                } else {
                                                    expandedFenceIds + fence.id
                                                }
                                            },
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Icon(
                                                imageVector = if (isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                                contentDescription = if (isExpanded) stringResource(R.string.collapse_short) else stringResource(R.string.expand_short),
                                                tint = NeonBlue,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    } else {
                                        Spacer(Modifier.width(24.dp))
                                    }

                                    Box(
                                        modifier = Modifier
                                            .size(24.dp)
                                            .clip(CircleShape)
                                            .background(Color(AndroidColor.parseColor(fence.color)))
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            fence.name,
                                            fontWeight = FontWeight.Medium,
                                            color = if (isHighlighted) NeonAmber else Color.White
                                        )
                                        Text(
                                            when (fence.type) {
                                                GeoFenceType.CIRCLE -> stringResource(R.string.circle_with_radius, fence.radius?.toInt() ?: 0)
                                                GeoFenceType.POLYGON -> stringResource(R.string.polygon_with_vertices, fence.points?.size ?: 0)
                                            },
                                            fontSize = 12.sp,
                                            color = Color.Gray
                                        )
                                    }
                                    if (matched) {
                                        Icon(
                                            imageVector = Icons.Filled.CheckCircle,
                                            contentDescription = stringResource(R.string.hit),
                                            tint = Success,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                    if (isHighlighted) {
                                        Icon(
                                            imageVector = AppIcons.insideFence,
                                            contentDescription = stringResource(R.string.highlighted),
                                            tint = currentPalette.accent,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                    IconButton(onClick = { onEditClick(fence) }, modifier = Modifier.size(32.dp)) {
                                        Icon(AppIcons.edit, contentDescription = stringResource(R.string.edit), tint = primaryColor(), modifier = Modifier.size(18.dp))
                                    }
                                    IconButton(onClick = { onDeleteClick(fence) }, modifier = Modifier.size(32.dp)) {
                                        Icon(AppIcons.delete, contentDescription = stringResource(R.string.delete), tint = Danger, modifier = Modifier.size(18.dp))
                                    }
                                }

                                HorizontalDivider(
                                    color = Color.Gray.copy(alpha = 0.15f),
                                    modifier = Modifier.padding(start = indentPadding + 48.dp, end = 12.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EditFenceDialog(
    fence: GeoFence,
    fenceColors: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (GeoFence) -> Unit,
    onDelete: () -> Unit
) {
    var name by remember { mutableStateOf(fence.name) }
    var color by remember { mutableStateOf(fence.color) }
    var alpha by remember { mutableStateOf(fence.alpha) }
    var radius by remember { mutableStateOf(fence.radius ?: 100f) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_fence)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.fence_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text(stringResource(R.string.color), fontSize = 12.sp, color = Color.Gray)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    fenceColors.forEach { c ->
                        val sel = color == c
                        Box(
                            Modifier.size(28.dp).clip(CircleShape)
                                .background(Color(AndroidColor.parseColor(c)))
                                .then(if (sel) Modifier.background(Color.White.copy(alpha = 0.4f)) else Modifier)
                                .clickable { color = c }
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                }

                Text(stringResource(R.string.transparency, (alpha * 100).toInt()), fontSize = 12.sp, color = Color.Gray)
                Slider(
                    value = alpha,
                    onValueChange = { alpha = it },
                    modifier = Modifier.fillMaxWidth(),
                    valueRange = 0.1f..0.8f,
                    steps = 6
                )

                if (fence.type == GeoFenceType.CIRCLE) {
                    Text(stringResource(R.string.radius_meters, radius.toInt()), fontSize = 12.sp, color = Color.Gray)
                    Slider(
                        value = radius,
                        onValueChange = { radius = it },
                        modifier = Modifier.fillMaxWidth(),
                        valueRange = 50f..1000f,
                        steps = 18
                    )
                }

                Text(stringResource(R.string.edit_on_map_hint), fontSize = 11.sp, color = NeonBlue)
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm(fence.copy(name = name, color = color, alpha = alpha, radius = if (fence.type == GeoFenceType.CIRCLE) radius else fence.radius))
                }
            ) { Text(stringResource(R.string.edit_on_map)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDelete) { Text(stringResource(R.string.delete), color = Danger) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        }
    )
}
