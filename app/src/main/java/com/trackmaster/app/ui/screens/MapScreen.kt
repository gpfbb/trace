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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.amap.api.location.AMapLocation
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
import java.util.UUID

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
fun MapScreen(app: TrackMasterApplication) {
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
            onMapTypeChange = { newType ->
                mapType = newType
                app.appPreferences.mapType = newType
            },
            onFenceListClick = { showFenceList = true },
            onFenceClick = { fenceId ->
                // 切换显示状态：点击同一围栏则取消显示，否则显示该围栏的点（只读）
                app.setDisplayedFenceId(if (displayedFenceId == fenceId) null else fenceId)
            },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 8.dp)
        )

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
                    val index = app.geoFences.indexOfFirst { it.id == updatedFence.id }
                    if (index >= 0) {
                        app.geoFences[index] = updatedFence
                        app.dbHelper.updateFence(updatedFence)
                        app.refreshEngineFences()
                        app.checkAndUpdateFenceState()
                    }
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
                    containerColor = NeonAmber,
                    contentColor = Color.White
                ) {
                    Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "撤回")
                }
            }

            if (isDrawingMode && drawingPoints.isNotEmpty()) {
                SmallFloatingActionButton(
                    onClick = { drawingPoints = drawingPoints.dropLast(1) },
                    containerColor = NeonAmber,
                    contentColor = Color.White
                ) {
                    Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "撤回")
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
                    else -> primaryColor()
                },
                contentColor = Color.White
            ) {
                Icon(
                    imageVector = when {
                        isDrawingMode || isEditingFence -> Icons.Filled.Close
                        else -> Icons.Filled.Add
                    },
                    contentDescription = if (isDrawingMode || isEditingFence) "取消" else "绘制"
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

                app.geoFences.add(fence)
                app.dbHelper.insertFence(fence)
                app.refreshEngineFences()
                app.checkAndUpdateFenceState()

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
            title = { Text("围栏创建成功") },
            text = {
                Text("建议前往「设置 → 重新计算父子关系」建立围栏的嵌套层级，统计时会自动累加子围栏的停留时长。")
            },
            confirmButton = {
                Button(onClick = { showRecalculateHint = false }) {
                    Text("知道了")
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
                // 【修复】点击列表围栏时显示围栏的所有点，不要高亮
                app.setDisplayedFenceId(if (displayedFenceId == fence.id) null else fence.id)
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

    if (showEditDialog && selectedFence != null) {
        EditFenceDialog(
            fence = selectedFence!!,
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

    if (showDeleteConfirm != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = null },
            title = { Text("确认删除") },
            text = { Text("确定要删除围栏「${showDeleteConfirm!!.name}」吗？此操作不可恢复。") },
            confirmButton = {
                Button(
                    onClick = {
                        val fence = showDeleteConfirm!!
                        app.geoFences.removeIf { it.id == fence.id }
                        app.dbHelper.deleteFence(fence.id)
                        app.refreshEngineFences()
                        app.checkAndUpdateFenceState()
                        showDeleteConfirm = null
                        if (selectedFence?.id == fence.id) selectedFence = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Danger)
                ) {
                    Text("删除", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = null }) {
                    Text("取消")
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
    onMapTypeChange: (Int) -> Unit,
    onFenceListClick: () -> Unit,
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
                kotlin.math.abs(area) / 2.0 * 111000.0 * 111000.0 // 粗略转换
            }
        }
    }

    Box(
        modifier = modifier
            .height(48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(glassPanel().copy(alpha = 0.9f))
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
                Icon(Icons.Filled.LocationOn, contentDescription = null, tint = Success, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = smallestFence.name,
                    fontSize = 14.sp,
                    color = if (displayedFenceId == smallestFence.id) NeonAmber else Color.White,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable { onFenceClick(smallestFence.id) }
                )
            } else {
                Icon(Icons.Filled.LocationOff, contentDescription = null, tint = Warning, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("${fences.size} 个围栏", fontSize = 14.sp, color = Color.White, fontWeight = FontWeight.Medium)
            }

            Spacer(Modifier.width(16.dp))
            Box(Modifier.width(1.dp).height(20.dp).background(Color.White.copy(alpha = 0.3f)))
            Spacer(Modifier.width(16.dp))
            // 地图图层切换按钮
            val nextMapType = if (mapType >= 3) 1 else mapType + 1
            val mapTypeIcon = when (mapType) {
                2 -> Icons.Default.Satellite
                3 -> Icons.Default.NightsStay
                else -> Icons.Default.Map
            }
            val mapTypeDesc = when (mapType) {
                1 -> "标准地图"
                2 -> "卫星地图"
                else -> "夜景地图"
            }
            Icon(
                mapTypeIcon,
                contentDescription = "切换地图图层($mapTypeDesc)",
                tint = NeonAmber,
                modifier = Modifier.size(20.dp).clickable { onMapTypeChange(nextMapType) }
            )
            Spacer(Modifier.width(16.dp))
            Icon(
                Icons.AutoMirrored.Filled.List,
                contentDescription = "围栏列表",
                tint = Color.White,
                modifier = Modifier.size(20.dp).clickable { onFenceListClick() }
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
                        label = { Text("圆形", fontSize = 12.sp) },
                        leadingIcon = { Icon(Icons.Filled.Circle, contentDescription = null, modifier = Modifier.size(14.dp)) },
                        modifier = Modifier.height(32.dp),
                        enabled = drawingPoints.isEmpty()
                    )
                    Spacer(Modifier.width(6.dp))
                    FilterChip(
                        selected = fenceType == GeoFenceType.POLYGON,
                        onClick = { if (drawingPoints.isEmpty()) onFenceTypeChange(GeoFenceType.POLYGON) },
                        label = { Text("多边形", fontSize = 12.sp) },
                        leadingIcon = { Icon(Icons.Filled.Category, contentDescription = null, modifier = Modifier.size(14.dp)) },
                        modifier = Modifier.height(32.dp),
                        enabled = drawingPoints.isEmpty()
                    )
                    Spacer(Modifier.width(6.dp))
                    FilterChip(
                        selected = isPrecisionMode,
                        onClick = { onPrecisionModeChange(!isPrecisionMode) },
                        label = { Text("精确", fontSize = 12.sp) },
                        leadingIcon = { Icon(Icons.Filled.MyLocation, contentDescription = null, modifier = Modifier.size(14.dp)) },
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
                        contentDescription = "撤回",
                        tint = if (drawingPoints.isNotEmpty()) NeonAmber else Color.Gray,
                        modifier = Modifier.size(20.dp)
                    )
                }
                IconButton(
                    onClick = onCancel,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "取消",
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
                Text("颜色:", fontSize = 10.sp, color = Color.Gray)
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
                Text("${(selectedAlpha * 100).toInt()}%", fontSize = 10.sp, color = Color.Gray)
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
                        GeoFenceType.CIRCLE -> if (drawingPoints.isEmpty()) "点击地图选圆心" else "已选圆心，半径100m"
                        GeoFenceType.POLYGON -> {
                            val remaining = 3 - drawingPoints.size
                            if (remaining > 0) "还需${remaining}个顶点" else "${drawingPoints.size}个顶点，已可创建"
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
                    Text("创建", fontSize = 13.sp)
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
                    "编辑「${fence.name}」- 点击地图添加顶点",
                    fontSize = 12.sp,
                    color = NeonBlue,
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = isPrecisionMode,
                    onClick = { onPrecisionModeChange(!isPrecisionMode) },
                    label = { Text("精确", fontSize = 11.sp) },
                    leadingIcon = { Icon(Icons.Filled.MyLocation, contentDescription = null, modifier = Modifier.size(14.dp)) },
                    modifier = Modifier.height(28.dp)
                )
                IconButton(onClick = onUndo) {
                    Icon(
                        Icons.AutoMirrored.Filled.Undo,
                        contentDescription = "删除最后",
                        tint = if (editingPoints.isNotEmpty()) NeonAmber else Color.Gray
                    )
                }
                IconButton(onClick = onCancel) {
                    Icon(Icons.Filled.Close, contentDescription = "取消", tint = Color.Gray)
                }
            }

            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                Button(
                    onClick = {
                        if (fence.type == GeoFenceType.POLYGON && editingPoints.size >= 3) {
                            onSave(fence.copy(points = editingPoints))
                        }
                    },
                    enabled = editingPoints.size >= 3,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Success)
                ) {
                    Text("保存", color = Color.White)
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
        title = { Text("创建围栏") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("围栏名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                when (fenceType) {
                    GeoFenceType.CIRCLE -> {
                        Text("半径: ${radius.toInt()} 米", fontSize = 14.sp)
                        Slider(
                            value = radius,
                            onValueChange = { radius = it },
                            valueRange = 50f..1000f,
                            steps = 18
                        )
                    }
                    GeoFenceType.POLYGON -> Text("顶点: ${points.size} 个", fontSize = 14.sp, color = Color.Gray)
                }
                Text(
                    "💡 提示：围栏支持嵌套（父子关系），不支持相交。相交的两个围栏无法计算父子关系。",
                    fontSize = 11.sp,
                    color = NeonBlue
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(name, color, alpha, radius) }, enabled = name.isNotBlank()) { Text("创建") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
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
                val child = allFences.find { it.id == childId }
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
                            text = "围栏列表 (${visibleFences.size})",
                            style = MaterialTheme.typography.titleLarge,
                            color = Color.White
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "关闭",
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
                                imageVector = Icons.Filled.LocationOff,
                                contentDescription = null,
                                tint = Color.Gray,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(Modifier.height(8.dp))
                            Text("暂无围栏", color = Color.Gray, fontSize = 16.sp)
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
                                                contentDescription = if (isExpanded) "收起" else "展开",
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
                                                GeoFenceType.CIRCLE -> "圆形 ${fence.radius?.toInt() ?: 0}m"
                                                GeoFenceType.POLYGON -> "多边形 ${fence.points?.size ?: 0}顶点"
                                            },
                                            fontSize = 12.sp,
                                            color = Color.Gray
                                        )
                                    }
                                    if (matched) {
                                        Icon(
                                            imageVector = Icons.Filled.CheckCircle,
                                            contentDescription = "已命中",
                                            tint = Success,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                    if (isHighlighted) {
                                        Icon(
                                            imageVector = Icons.Filled.LocationOn,
                                            contentDescription = "已高亮",
                                            tint = NeonAmber,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                    IconButton(onClick = { onEditClick(fence) }, modifier = Modifier.size(32.dp)) {
                                        Icon(Icons.Filled.Edit, contentDescription = "编辑", tint = primaryColor(), modifier = Modifier.size(18.dp))
                                    }
                                    IconButton(onClick = { onDeleteClick(fence) }, modifier = Modifier.size(32.dp)) {
                                        Icon(Icons.Filled.Delete, contentDescription = "删除", tint = Danger, modifier = Modifier.size(18.dp))
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
        title = { Text("编辑围栏") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("围栏名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text("颜色", fontSize = 12.sp, color = Color.Gray)
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

                Text("透明度: ${(alpha * 100).toInt()}%", fontSize = 12.sp, color = Color.Gray)
                Slider(
                    value = alpha,
                    onValueChange = { alpha = it },
                    modifier = Modifier.fillMaxWidth(),
                    valueRange = 0.1f..0.8f,
                    steps = 6
                )

                if (fence.type == GeoFenceType.CIRCLE) {
                    Text("半径: ${radius.toInt()} 米", fontSize = 12.sp, color = Color.Gray)
                    Slider(
                        value = radius,
                        onValueChange = { radius = it },
                        modifier = Modifier.fillMaxWidth(),
                        valueRange = 50f..1000f,
                        steps = 18
                    )
                }

                Text("点击「在地图上编辑」可在地图上添加或删除顶点", fontSize = 11.sp, color = NeonBlue)
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm(fence.copy(name = name, color = color, alpha = alpha, radius = if (fence.type == GeoFenceType.CIRCLE) radius else fence.radius))
                }
            ) { Text("在地图上编辑") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDelete) { Text("删除", color = Danger) }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    )
}
