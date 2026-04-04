package com.trackmaster.app

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun CategorizedFenceListScreen(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as TrackMasterApplication
    val scope = rememberCoroutineScope()

    var categories by remember { mutableStateOf<List<Category>>(emptyList()) }
    var fences by remember { mutableStateOf<List<GeoFence>>(emptyList()) }
    var expandedCategories by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showMoveDialog by remember { mutableStateOf<GeoFence?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun formatDuration(seconds: Long): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        return String.format("%02d:%02d", hours, minutes)
    }

    fun getCategoryTotalDuration(categoryId: String): Long {
        val categoryFences = fences.filter { it.categoryId == categoryId }
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        var total = 0L
        categoryFences.forEach { fence ->
            val stats = app.dbHelper.getDailyStatsByFenceAndDate(fence.id, today)
            total += stats?.totalStayDuration ?: 0L
        }
        return total
    }

    fun getFenceDuration(fenceId: String): Long {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        return app.dbHelper.getDailyStatsByFenceAndDate(fenceId, today)?.totalStayDuration ?: 0L
    }

    fun toggleCategory(categoryId: String) {
        expandedCategories = if (expandedCategories.contains(categoryId)) {
            expandedCategories - categoryId
        } else {
            expandedCategories + categoryId
        }
    }

    suspend fun loadData() {
        categories = app.categoryManager.getAllCategories()
        fences = app.geoFences
    }

    LaunchedEffect(Unit) {
        loadData()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.List,
                    contentDescription = "围栏列表",
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text("围栏列表", fontWeight = FontWeight.Bold, fontSize = 20.sp)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 500.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                errorMessage?.let { message ->
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Text(
                            text = message,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(12.dp),
                            fontSize = 14.sp
                        )
                    }
                }

                if (categories.isEmpty() && fences.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "暂无围栏",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        categories.forEach { category ->
                            val categoryFences = fences.filter { it.categoryId == category.id }
                            if (categoryFences.isNotEmpty() || category.id == Category.UNASSIGNED_ID) {
                                item(key = "category-${category.id}") {
                                    CategoryHeader(
                                        category = category,
                                        isExpanded = expandedCategories.contains(category.id),
                                        totalDuration = getCategoryTotalDuration(category.id),
                                        onToggle = { toggleCategory(category.id) }
                                    )
                                }

                                if (expandedCategories.contains(category.id)) {
                                    items(categoryFences, key = { "fence-${it.id}" }) { fence ->
                                        FenceItem(
                                            fence = fence,
                                            duration = getFenceDuration(fence.id),
                                            onMove = { showMoveDialog = fence }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )

    showMoveDialog?.let { fence ->
        MoveFenceDialog(
            fence = fence,
            categories = categories,
            onDismiss = { showMoveDialog = null },
            onConfirm = { newCategoryId ->
                scope.launch {
                    val result = app.categoryManager.moveFenceToCategory(fence.id, newCategoryId)
                    if (result.isSuccess) {
                        loadData()
                        showMoveDialog = null
                        errorMessage = null
                    } else {
                        errorMessage = result.exceptionOrNull()?.message ?: "移动失败"
                    }
                }
            }
        )
    }
}

@Composable
fun CategoryHeader(
    category: Category,
    isExpanded: Boolean,
    totalDuration: Long,
    onToggle: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = if (isExpanded) "▼" else "▶",
                    fontSize = 20.sp
                )
                Text(
                    text = category.icon ?: "📁",
                    fontSize = 24.sp
                )
                Text(
                    text = category.name,
                    fontWeight = FontWeight.Medium,
                    fontSize = 16.sp
                )
            }

            Text(
                text = formatDuration(totalDuration),
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun FenceItem(
    fence: GeoFence,
    duration: Long,
    onMove: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.LocationOn,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = fence.name,
                    fontSize = 14.sp
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = formatDuration(duration),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                IconButton(
                    onClick = onMove,
                    modifier = Modifier.size(32.dp)
                ) {
                    Text(
                        text = "⇄",
                        fontSize = 18.sp
                    )
                }
            }
        }
    }
}

@Composable
fun MoveFenceDialog(
    fence: GeoFence,
    categories: List<Category>,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var selectedCategoryId by remember { mutableStateOf(fence.categoryId) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("移动「${fence.name}」") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("选择目标分类：", style = MaterialTheme.typography.bodyMedium)
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.heightIn(max = 300.dp)
                ) {
                    items(categories) { category ->
                        val isSelected = selectedCategoryId == category.id
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedCategoryId = category.id }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = { selectedCategoryId = category.id }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = category.icon ?: "📁", fontSize = 20.sp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = category.name)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(selectedCategoryId) }) {
                Text("移动")
            }
        },
        dismissButton = {
            Button(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

fun formatDuration(seconds: Long): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    return String.format("%02d:%02d", hours, minutes)
}
