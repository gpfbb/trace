package com.trackmaster.app

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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trackmaster.app.model.Category
import kotlinx.coroutines.launch

@Composable
fun CategoryManagerScreen(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as TrackMasterApplication
    val scope = rememberCoroutineScope()
    
    var categories by remember { mutableStateOf<List<Category>>(emptyList()) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf<Category?>(null) }
    var showDeleteDialog by remember { mutableStateOf<Category?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    
    fun formatDuration(seconds: Long): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        return String.format("%02d:%02d", hours, minutes)
    }
    
    suspend fun loadCategories() {
        categories = app.categoryManager.getAllCategories()
    }
    
    LaunchedEffect(Unit) {
        loadCategories()
    }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "📁",
                    fontSize = 28.sp,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text("分类管理", fontWeight = FontWeight.Bold, fontSize = 20.sp)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp),
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
                
                if (categories.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "暂无分类，点击下方按钮创建",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(categories, key = { it.id }) { category ->
                            CategoryItem(
                                category = category,
                                onEdit = { showEditDialog = category },
                                onDelete = { showDeleteDialog = category }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = { showCreateDialog = true }) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("新建分类")
                }
                Button(onClick = onDismiss) {
                    Text("关闭")
                }
            }
        }
    )
    
    if (showCreateDialog) {
        CreateEditCategoryDialog(
            category = null,
            onDismiss = { showCreateDialog = false },
            onConfirm = { name, icon ->
                scope.launch {
                    val result = app.categoryManager.createCategory(name, icon)
                    if (result.isSuccess) {
                        loadCategories()
                        showCreateDialog = false
                        errorMessage = null
                    } else {
                        errorMessage = result.exceptionOrNull()?.message ?: "创建失败"
                    }
                }
            }
        )
    }
    
    showEditDialog?.let { category ->
        CreateEditCategoryDialog(
            category = category,
            onDismiss = { showEditDialog = null },
            onConfirm = { name, icon ->
                scope.launch {
                    val updated = category.copy(name = name, icon = icon)
                    val result = app.categoryManager.updateCategory(updated)
                    if (result.isSuccess) {
                        loadCategories()
                        showEditDialog = null
                        errorMessage = null
                    } else {
                        errorMessage = result.exceptionOrNull()?.message ?: "更新失败"
                    }
                }
            }
        )
    }
    
    showDeleteDialog?.let { category ->
        DeleteCategoryDialog(
            category = category,
            onDismiss = { showDeleteDialog = null },
            onConfirm = {
                scope.launch {
                    val result = app.categoryManager.deleteCategory(category.id)
                    if (result.isSuccess) {
                        loadCategories()
                        showDeleteDialog = null
                        errorMessage = null
                    } else {
                        errorMessage = result.exceptionOrNull()?.message ?: "删除失败"
                    }
                }
            }
        )
    }
}

@Composable
fun CategoryItem(
    category: Category,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
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
                    text = category.icon ?: "📁",
                    fontSize = 24.sp
                )
                Text(
                    text = category.name,
                    fontWeight = FontWeight.Medium,
                    fontSize = 16.sp
                )
            }
            
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = "编辑")
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "删除")
                }
            }
        }
    }
}

@Composable
fun CreateEditCategoryDialog(
    category: Category?,
    onDismiss: () -> Unit,
    onConfirm: (String, String?) -> Unit
) {
    var name by remember { mutableStateOf(category?.name ?: "") }
    var selectedIcon by remember { mutableStateOf(category?.icon) }
    var showIconPicker by remember { mutableStateOf(false) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (category == null) "新建分类" else "编辑分类")
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("分类名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                
                Text("选择图标", style = MaterialTheme.typography.labelMedium)
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CategoryManager.CATEGORY_ICONS.take(8).forEach { icon ->
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clickable { selectedIcon = icon }
                                .background(
                                    color = if (selectedIcon == icon) 
                                        MaterialTheme.colorScheme.primaryContainer 
                                    else 
                                        MaterialTheme.colorScheme.surfaceVariant,
                                    shape = MaterialTheme.shapes.medium
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(text = icon, fontSize = 24.sp)
                        }
                    }
                }
                
                TextButton(onClick = { showIconPicker = true }) {
                    Text("更多图标")
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name, selectedIcon) },
                enabled = name.isNotBlank()
            ) {
                Text(if (category == null) "创建" else "保存")
            }
        },
        dismissButton = {
            Button(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
    
    if (showIconPicker) {
        IconPickerDialog(
            onDismiss = { showIconPicker = false },
            onSelect = { icon ->
                selectedIcon = icon
                showIconPicker = false
            }
        )
    }
}

@Composable
fun IconPickerDialog(
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择图标") },
        text = {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(CategoryManager.CATEGORY_ICONS.chunked(6)) { rowIcons ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        rowIcons.forEach { icon ->
                            Box(
                                modifier = Modifier
                                    .size(56.dp)
                                    .clickable { onSelect(icon) }
                                    .background(
                                        color = MaterialTheme.colorScheme.surfaceVariant,
                                        shape = MaterialTheme.shapes.medium
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(text = icon, fontSize = 28.sp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
fun DeleteCategoryDialog(
    category: Category,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除分类") },
        text = {
            Text(
                "确定要删除分类「${category.name}」吗？\n\n" +
                "该分类下的所有围栏将被移动到「未分类」分组。"
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("删除")
            }
        },
        dismissButton = {
            Button(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
