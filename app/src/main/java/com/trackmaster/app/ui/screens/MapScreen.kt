package com.trackmaster.app.ui.screens

import android.graphics.Color as AndroidColor
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amap.api.location.AMapLocation
import com.amap.api.maps.model.LatLng
import com.trackmaster.app.GeoFence
import com.trackmaster.app.GeoFencePoint
import com.trackmaster.app.GeoFenceType
import com.trackmaster.app.LocationServiceState
import com.trackmaster.app.TrackMasterApplication
import com.trackmaster.app.ui.components.AMapViewWrapper
import com.trackmaster.app.ui.theme.*
import java.util.UUID

@Composable
fun MapScreen(app: TrackMasterApplication) {
    var localFences by remember { mutableStateOf(app.geoFences.toList()) }
    var localMatchedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var localLocation by remember { mutableStateOf<AMapLocation?>(null) }

    var isDrawingMode by remember { mutableStateOf(false) }
    var selectedFenceType by remember { mutableStateOf(GeoFenceType.CIRCLE) }
    var drawingPoints by remember { mutableStateOf<List<GeoFencePoint>>(emptyList()) }
    var selectedColor by remember { mutableStateOf("#FF0000") }
    var selectedAlpha by remember { mutableStateOf(0.3f) }

    var showFenceList by remember { mutableStateOf(false) }
    var selectedFence by remember { mutableStateOf<GeoFence?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf<GeoFence?>(null) }

    var isEditingFence by remember { mutableStateOf(false) }
    var editingPoints by remember { mutableStateOf<List<GeoFencePoint>>(emptyList()) }
    var editingFence by remember { mutableStateOf<GeoFence?>(null) }

    val fenceColors = listOf(
        "#FF0000", "#E91E63", "#9C27B0", "#673AB7", "#3F51B5",
        "#2196F3", "#00BCD4", "#009688", "#4CAF50", "#8BC34A",
        "#FF9800", "#FF5722", "#795548", "#607D8B", "#000000"
    )

    val fenceStateVersion by app.fenceStateVersion.collectAsState()

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

    val onMapClick: (LatLng) -> Unit = { latLng ->
        when {
            isDrawingMode -> {
                drawingPoints = drawingPoints + GeoFencePoint(latLng.latitude, latLng.longitude)
            }
            isEditingFence && editingFence != null -> {
                editingPoints = editingPoints + GeoFencePoint(latLng.latitude, latLng.longitude)
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AMapViewWrapper(
            modifier = Modifier.fillMaxSize(),
            currentLocation = localLocation,
            fences = localFences,
            currentMatchedFenceIds = localMatchedIds,
            selectedFenceId = selectedFence?.id,
            isDrawingMode = isDrawingMode,
            drawingPoints = drawingPoints,
            isEditingFence = isEditingFence,
            editingPoints = editingPoints,
            onMapClick = onMapClick
        )

        TopStatusIsland(
            currentMatchedFenceIds = localMatchedIds,
            fences = localFences,
            onFenceListClick = { showFenceList = true },
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
                    onClick = { },
                    containerColor = NeonBlue,
                    contentColor = Color.White
                ) {
                    Icon(Icons.Default.Add, contentDescription = "点击地图添加点")
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
                    else -> NeonCoral
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

                showCreateDialog = false
                drawingPoints = emptyList()
                isDrawingMode = false
            }
        )
    }

    if (showFenceList) {
        FenceListDialog(
            fences = localFences,
            currentMatchedIds = localMatchedIds,
            onDismiss = { showFenceList = false },
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
    onFenceListClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(GlassPanelRich.copy(alpha = 0.9f))
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            val matchedFence = fences.find { it.id in currentMatchedFenceIds }
            if (matchedFence != null) {
                Icon(Icons.Filled.LocationOn, contentDescription = null, tint = Success, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(matchedFence.name, fontSize = 14.sp, color = Color.White, fontWeight = FontWeight.Medium)
            } else {
                Icon(Icons.Filled.LocationOff, contentDescription = null, tint = Warning, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("${fences.size} 个围栏", fontSize = 14.sp, color = Color.White, fontWeight = FontWeight.Medium)
            }

            Spacer(Modifier.width(16.dp))
            Box(Modifier.width(1.dp).height(20.dp).background(Color.White.copy(alpha = 0.3f)))
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
    onFenceTypeChange: (GeoFenceType) -> Unit,
    onColorChange: (String) -> Unit,
    onAlphaChange: (Float) -> Unit,
    onUndo: () -> Unit,
    onCancel: () -> Unit,
    onCreate: () -> Unit
) {
    GlassCard(Modifier.fillMaxWidth()) {
        Column {
            Row(Modifier.fillMaxWidth().padding(8.dp)) {
                Text(
                    when (fenceType) {
                        GeoFenceType.CIRCLE -> "圆形 - 点击地图选圆心"
                        GeoFenceType.POLYGON -> {
                            val remaining = 3 - drawingPoints.size
                            if (remaining > 0) "多边形 - 需${remaining}个顶点"
                            else "多边形 - ${drawingPoints.size}个顶点"
                        }
                    },
                    fontSize = 12.sp,
                    color = NeonCoral,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onUndo) {
                    Icon(
                        Icons.AutoMirrored.Filled.Undo,
                        contentDescription = "撤回",
                        tint = if (drawingPoints.isNotEmpty()) NeonAmber else Color.Gray
                    )
                }
                IconButton(onClick = onCancel) {
                    Icon(Icons.Filled.Close, contentDescription = "取消", tint = Color.Gray)
                }
            }

            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                FilterChip(
                    selected = fenceType == GeoFenceType.CIRCLE,
                    onClick = { onFenceTypeChange(GeoFenceType.CIRCLE) },
                    label = { Text("圆形") },
                    leadingIcon = { Icon(Icons.Filled.Circle, contentDescription = null, modifier = Modifier.size(16.dp)) }
                )
                Spacer(Modifier.width(8.dp))
                FilterChip(
                    selected = fenceType == GeoFenceType.POLYGON,
                    onClick = { onFenceTypeChange(GeoFenceType.POLYGON) },
                    label = { Text("多边形") },
                    leadingIcon = { Icon(Icons.Filled.Category, contentDescription = null, modifier = Modifier.size(16.dp)) }
                )
            }

            Text("颜色", fontSize = 10.sp, color = Color.Gray, modifier = Modifier.padding(top = 8.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                fenceColors.take(8).forEach { c ->
                    val sel = selectedColor == c
                    Box(
                        Modifier.size(28.dp).clip(CircleShape)
                            .background(Color(AndroidColor.parseColor(c)))
                            .then(if (sel) Modifier.background(Color.White.copy(alpha = 0.4f)) else Modifier)
                            .clickable { onColorChange(c) }
                    )
                    Spacer(Modifier.width(4.dp))
                }
            }

            Text(
                "透明度: ${(selectedAlpha * 100).toInt()}%",
                fontSize = 10.sp,
                color = Color.Gray,
                modifier = Modifier.padding(top = 8.dp)
            )
            Slider(
                value = selectedAlpha,
                onValueChange = onAlphaChange,
                modifier = Modifier.fillMaxWidth(),
                valueRange = 0.1f..0.8f,
                steps = 6
            )

            Button(
                onClick = onCreate,
                enabled = when (fenceType) {
                    GeoFenceType.CIRCLE -> drawingPoints.isNotEmpty()
                    GeoFenceType.POLYGON -> drawingPoints.size >= 3
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = NeonCoral)
            ) {
                Text("创建围栏")
            }
        }
    }
}

@Composable
private fun EditingToolbar(
    fence: GeoFence?,
    editingPoints: List<GeoFencePoint>,
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
    currentMatchedIds: Set<String>,
    onDismiss: () -> Unit,
    onEditClick: (GeoFence) -> Unit,
    onDeleteClick: (GeoFence) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("围栏列表 (${fences.size})") },
        text = {
            if (fences.isEmpty()) {
                Text("暂无围栏", color = Color.Gray)
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(fences.size) { i ->
                        val fence = fences[i]
                        val matched = fence.id in currentMatchedIds
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                                .background(if (matched) Success.copy(alpha = 0.1f) else Color.Transparent)
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(Modifier.size(24.dp).clip(CircleShape).background(Color(AndroidColor.parseColor(fence.color))))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(fence.name, fontWeight = FontWeight.Medium)
                                Text(
                                    when (fence.type) {
                                        GeoFenceType.CIRCLE -> "圆形 ${fence.radius?.toInt() ?: 0}m"
                                        GeoFenceType.POLYGON -> "多边形 ${fence.points?.size ?: 0}顶点"
                                    },
                                    fontSize = 12.sp,
                                    color = Color.Gray
                                )
                            }
                            if (matched) Icon(Icons.Filled.CheckCircle, contentDescription = "已命中", tint = Success)
                            IconButton(onClick = { onEditClick(fence) }) {
                                Icon(Icons.Filled.Edit, contentDescription = "编辑", tint = NeonCoral)
                            }
                            IconButton(onClick = { onDeleteClick(fence) }) {
                                Icon(Icons.Filled.Delete, contentDescription = "删除", tint = Danger)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
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
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    fenceColors.take(8).forEach { c ->
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
