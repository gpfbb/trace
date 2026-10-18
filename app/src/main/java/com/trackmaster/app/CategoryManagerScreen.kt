package com.trackmaster.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trackmaster.app.model.Category
import com.trackmaster.app.ui.design.AppType
import com.trackmaster.app.ui.design.Divider
import com.trackmaster.app.ui.design.GroupedSection
import com.trackmaster.app.ui.design.LargeTitle
import com.trackmaster.app.ui.design.PrimaryButton
import com.trackmaster.app.ui.design.Space
import com.trackmaster.app.ui.design.currentPalette
import kotlinx.coroutines.launch

/**
 * 分类管理。
 *
 * 原来是一个装满内容的 AlertDialog（对话框里套 LazyColumn，
 * 高度写死 400dp），现在改成正常的全屏页面 + 顶栏返回。
 * 对话框只用来做"新建/编辑/删除"这类确认动作。
 */
@Composable
fun CategoryManagerScreen(
    onDismiss: () -> Unit
) {
    val palette = currentPalette
    val context = LocalContext.current
    val app = remember(context) {
        context.applicationContext as TrackMasterApplication
    }
    val scope = rememberCoroutineScope()

    var categories by remember { mutableStateOf<List<Category>>(emptyList()) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf<Category?>(null) }
    var showDeleteDialog by remember { mutableStateOf<Category?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val createFailedMsg = stringResource(R.string.create_failed)
    val updateFailedMsg = stringResource(R.string.update_failed)
    val deleteFailedMsg = stringResource(R.string.delete_failed)

    suspend fun loadCategories() {
        categories = app.categoryManager.getAllCategories()
    }

    LaunchedEffect(Unit) { loadCategories() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background)
            .statusBarsPadding()
    ) {
        // 顶栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.sm, vertical = Space.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.close),
                    tint = palette.accent
                )
            }
            Spacer(Modifier.width(Space.xs))
            Text(
                text = stringResource(R.string.category_management),
                style = AppType.headline,
                color = palette.label
            )
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { showCreateDialog = true }) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = stringResource(R.string.new_category),
                    tint = palette.accent
                )
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = Space.screenHorizontal,
                end = Space.screenHorizontal,
                top = Space.md,
                bottom = Space.xxl
            ),
            verticalArrangement = Arrangement.spacedBy(Space.sectionGap)
        ) {
            item {
                LargeTitle(text = stringResource(R.string.category_management))
            }

            errorMessage?.let { message ->
                item { ErrorBanner(message = message) }
            }

            if (categories.isEmpty()) {
                item {
                    EmptyCategories(
                        onAdd = { showCreateDialog = true }
                    )
                }
            } else {
                item {
                    GroupedSection {
                        categories.forEachIndexed { index, category ->
                            if (index > 0) Divider()
                            CategoryRow(
                                category = category,
                                onEdit = { showEditDialog = category },
                                onDelete = { showDeleteDialog = category }
                            )
                        }
                    }
                }
            }
        }
    }

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
private fun CategoryRow(
    category: Category,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val palette = currentPalette
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Space.lg, top = Space.md, bottom = Space.md, end = Space.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = category.icon ?: "📁", fontSize = 22.sp)
        Spacer(Modifier.width(Space.md))
        Text(
            text = category.name,
            style = AppType.body,
            color = palette.label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onEdit) {
            Icon(
                imageVector = Icons.Default.Edit,
                contentDescription = stringResource(R.string.edit),
                tint = palette.accent,
                modifier = Modifier.size(20.dp)
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = stringResource(R.string.delete),
                tint = palette.danger,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun EmptyCategories(onAdd: () -> Unit) {
    val palette = currentPalette
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Space.xxl),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.no_categories_hint),
            style = AppType.body,
            color = palette.labelSecondary
        )
        Spacer(Modifier.height(Space.lg))
        PrimaryButton(
            text = stringResource(R.string.new_category),
            onClick = onAdd,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun ErrorBanner(message: String) {
    val palette = currentPalette
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Space.md))
            .background(palette.danger.copy(alpha = 0.12f))
            .padding(Space.lg)
    ) {
        Text(
            text = message,
            style = AppType.footnote,
            color = palette.danger
        )
    }
}

@Composable
fun CategoryItem(
    category: Category,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    CategoryRow(category = category, onEdit = onEdit, onDelete = onDelete)
}

/**
 * 新建/编辑分类。
 *
 * 图标选择仍然保留"常用 8 个 + 更多"，但把选中态从
 * `primaryContainer` 换成强调色描边，浅深色下都清楚。
 */
@Composable
fun CreateEditCategoryDialog(
    category: Category?,
    onDismiss: () -> Unit,
    onConfirm: (String, String?) -> Unit
) {
    val palette = currentPalette
    var name by remember { mutableStateOf(category?.name ?: "") }
    var selectedIcon by remember { mutableStateOf(category?.icon) }
    var showIconPicker by remember { mutableStateOf(false) }

    val dialogTitle = if (category == null) {
        stringResource(R.string.new_category)
    } else {
        stringResource(R.string.edit_category)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = dialogTitle, style = AppType.title2) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Space.lg)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.category_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    text = stringResource(R.string.select_icon),
                    style = AppType.footnote,
                    color = palette.labelSecondary
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Space.sm)
                ) {
                    CategoryManager.CATEGORY_ICONS.take(8).forEach { icon ->
                        IconChoice(
                            icon = icon,
                            selected = selectedIcon == icon,
                            onClick = { selectedIcon = icon },
                            size = 44.dp
                        )
                    }
                }

                TextButton(onClick = { showIconPicker = true }) {
                    Text(
                        text = stringResource(R.string.more_icons),
                        color = palette.accent
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name, selectedIcon) },
                enabled = name.isNotBlank()
            ) {
                Text(
                    text = if (category == null) {
                        stringResource(R.string.create)
                    } else {
                        stringResource(R.string.save)
                    },
                    color = if (name.isNotBlank()) palette.accent else palette.labelDisabled
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.cancel), color = palette.labelSecondary)
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
    val palette = currentPalette
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.select_icon), style = AppType.title2) },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                items(CategoryManager.CATEGORY_ICONS.chunked(6)) { rowIcons ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Space.sm)
                    ) {
                        rowIcons.forEach { icon ->
                            IconChoice(
                                icon = icon,
                                selected = false,
                                onClick = { onSelect(icon) },
                                size = 52.dp
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.cancel), color = palette.labelSecondary)
            }
        }
    )
}

/** 单个 emoji 图标选项，选中时用强调色描边。 */
@Composable
private fun IconChoice(
    icon: String,
    selected: Boolean,
    onClick: () -> Unit,
    size: androidx.compose.ui.unit.Dp
) {
    val palette = currentPalette
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(Space.md))
            .background(
                if (selected) palette.accentSubtle else palette.fill
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text = icon, fontSize = (size.value * 0.45f).sp)
    }
}

@Composable
fun DeleteCategoryDialog(
    category: Category,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val palette = currentPalette
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.delete_category), style = AppType.title2) },
        text = {
            Text(
                text = stringResource(R.string.delete_category_confirm, category.name),
                style = AppType.body
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = stringResource(R.string.delete), color = palette.danger)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.cancel), color = palette.labelSecondary)
            }
        }
    )
}
