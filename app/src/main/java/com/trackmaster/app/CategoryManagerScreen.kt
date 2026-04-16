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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import com.trackmaster.app.R
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

    val createFailedMsg = stringResource(R.string.create_failed)
    val updateFailedMsg = stringResource(R.string.update_failed)
    val deleteFailedMsg = stringResource(R.string.delete_failed)

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
                Text(stringResource(R.string.category_management), fontWeight = FontWeight.Bold, fontSize = 20.sp)
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
                            stringResource(R.string.no_categories_hint),
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
                    Text(stringResource(R.string.new_category))
                }
                Button(onClick = onDismiss) {
                    Text(stringResource(R.string.close))
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
                        errorMessage = result.exceptionOrNull()?.message ?: createFailedMsg
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
                        errorMessage = result.exceptionOrNull()?.message ?: updateFailedMsg
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
                        errorMessage = result.exceptionOrNull()?.message ?: deleteFailedMsg
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
    val editLabel = stringResource(R.string.edit)
    val deleteLabel = stringResource(R.string.delete)
    
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
                    Icon(Icons.Default.Edit, contentDescription = editLabel)
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = deleteLabel)
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
    
    val dialogTitle = if (category == null) stringResource(R.string.new_category) else stringResource(R.string.edit_category)
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(dialogTitle) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.category_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                
                Text(stringResource(R.string.select_icon), style = MaterialTheme.typography.labelMedium)
                
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
                    Text(stringResource(R.string.more_icons))
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name, selectedIcon) },
                enabled = name.isNotBlank()
            ) {
                Text(if (category == null) stringResource(R.string.create) else stringResource(R.string.save))
            }
        },
        dismissButton = {
            Button(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
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
        title = { Text(stringResource(R.string.select_icon)) },
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
                Text(stringResource(R.string.cancel))
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
    val deleteCategoryConfirmMsg = stringResource(R.string.delete_category_confirm, category.name)
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_category)) },
        text = {
            Text(deleteCategoryConfirmMsg)
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text(stringResource(R.string.delete))
            }
        },
        dismissButton = {
            Button(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
