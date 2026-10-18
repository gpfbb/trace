package com.trackmaster.app.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import com.trackmaster.app.model.Category
import com.trackmaster.app.data.GeoFence
import com.trackmaster.app.core.FenceTreeResult
import com.trackmaster.app.core.FenceTreeManager
import com.trackmaster.app.R
import com.trackmaster.app.core.Geofence

/**
 * 分类围栏列表（树形展示）
 *
 * 功能：
 * - 按分类分组展示围栏
 * - 嵌套围栏以缩进方式展示层级
 * - 可折叠/展开分类和父级围栏
 * - 长按可设置父子关系
 */
@Composable
fun CategorizedFenceListScreen(
    categories: List<Category>,
    fences: List<GeoFence>,
    treeManager: FenceTreeManager,
    onFenceClick: (GeoFence) -> Unit = {},
    @Suppress("UNUSED_PARAMETER") _onSetParent: (fenceId: String, parentId: String?) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    // 构建围栏树
    val tree by remember(fences, treeManager.version) {
        mutableStateOf(treeManager.buildTree(fences))
    }

    var expandedCategories by remember { mutableStateOf(setOf<String>()) }
    var expandedFences by remember { mutableStateOf(setOf<String>()) }

    LazyColumn(modifier = modifier.animateContentSize()) {
        // 未分类围栏
        val unassignedFences = tree.leafFences.filter { it.category == null } +
                tree.fenceMap.values.filter { it.category == null && it.id !in tree.leafFences.map { l -> l.id } }

        if (unassignedFences.isNotEmpty()) {
            item {
                CategoryHeader(
                    category = Category.UNASSIGNED,
                    fenceCount = unassignedFences.size,
                    isExpanded = "unassigned" in expandedCategories,
                    onToggle = {
                        expandedCategories = if ("unassigned" in expandedCategories)
                            expandedCategories - "unassigned"
                        else
                            expandedCategories + "unassigned"
                    }
                )
            }
            if ("unassigned" in expandedCategories) {
                val rootFences = unassignedFences.filter { it.parentId == null }
                items(rootFences) { fence ->
                    FenceTreeNode(
                        fence = fence,
                        tree = tree,
                        depth = 0,
                        isExpanded = fence.id in expandedFences,
                        onToggleExpand = {
                            expandedFences = if (fence.id in expandedFences)
                                expandedFences - fence.id
                            else
                                expandedFences + fence.id
                        },
                        onClick = {
                            val oldFence = fences.find { f -> f.id == fence.id }
                            if (oldFence != null) onFenceClick(oldFence)
                        },
                        expandedFences = expandedFences,
                        onExpandedChange = { expandedFences = it }
                    )
                }
            }
        }

        // 按分类展示
        for (category in categories) {
            val catFences = tree.fenceMap.values.filter { it.category == category.id }
            if (catFences.isEmpty()) continue

            item {
                CategoryHeader(
                    category = category,
                    fenceCount = catFences.size,
                    isExpanded = category.id in expandedCategories,
                    onToggle = {
                        expandedCategories = if (category.id in expandedCategories)
                            expandedCategories - category.id
                        else
                            expandedCategories + category.id
                    }
                )
            }

            if (category.id in expandedCategories) {
                val rootFences = catFences.filter { it.parentId == null }
                items(rootFences) { fence ->
                    FenceTreeNode(
                        fence = fence,
                        tree = tree,
                        depth = 0,
                        isExpanded = fence.id in expandedFences,
                        onToggleExpand = {
                            expandedFences = if (fence.id in expandedFences)
                                expandedFences - fence.id
                            else
                                expandedFences + fence.id
                        },
                        onClick = {
                            val oldFence = fences.find { f -> f.id == fence.id }
                            if (oldFence != null) onFenceClick(oldFence)
                        },
                        expandedFences = expandedFences,
                        onExpandedChange = { expandedFences = it }
                    )
                }
            }
        }
    }
}

@Composable
private fun CategoryHeader(
    category: Category,
    fenceCount: Int,
    isExpanded: Boolean,
    onToggle: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { onToggle() },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        ),
        shape = RoundedCornerShape(8.dp),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = category.icon ?: "",
                fontSize = 16.sp,
                modifier = Modifier.padding(end = 6.dp)
            )
            Text(
                text = category.name,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = stringResource(R.string.fence_count, fenceCount),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = if (isExpanded) "▲" else "▼",
                fontSize = 14.sp
            )
        }
    }
}

@Composable
private fun FenceTreeNode(
    fence: Geofence,
    tree: FenceTreeResult,
    depth: Int,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onClick: () -> Unit,
    /** 全局展开态集合，递归子节点需要它才能各自展开/折叠 */
    expandedFences: Set<String>,
    onExpandedChange: (Set<String>) -> Unit,
) {
    val children = tree.childrenMap[fence.id] ?: emptyList()
    val hasChildren = children.isNotEmpty()
    val typeIcon = when (fence.type.name) {
        "CIRCLE" -> "⭕"
        "POLYGON" -> "⬠"
        else -> "📍"
    }

    Column {
        // 当前围栏节点
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = (16 + depth * 20).dp, end = 8.dp, top = 4.dp, bottom = 4.dp)
                .background(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(8.dp)
                )
                .clickable { onClick() }
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 层级指示线
            if (depth > 0) {
                Text(
                    text = "├─",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(end = 4.dp)
                )
            }

            Text(text = typeIcon, fontSize = 14.sp, modifier = Modifier.padding(end = 6.dp))

            Text(
                text = fence.name,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (depth == 0) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.weight(1f)
            )

            if (hasChildren) {
                Text(
                    text = "(${children.size})",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 4.dp)
                )
                Text(
                    text = if (isExpanded) "▾" else "▸",
                    fontSize = 12.sp,
                    modifier = Modifier.clickable { onToggleExpand() }
                )
            }

            // 深度标签
            if (depth > 0) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.padding(start = 4.dp)
                ) {
                    Text(
                        text = "L$depth",
                        fontSize = 9.sp,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }

        // 递归展示子级
        if (hasChildren && isExpanded) {
            for (child in children.sortedByDescending { it.depth }) {
                FenceTreeNode(
                    fence = child,
                    tree = tree,
                    depth = depth + 1,
                    isExpanded = child.id in expandedFences,
                    onToggleExpand = {
                        onExpandedChange(
                            if (child.id in expandedFences) expandedFences - child.id
                            else expandedFences + child.id
                        )
                    },
                    onClick = onClick,
                    expandedFences = expandedFences,
                    onExpandedChange = onExpandedChange
                )
            }
        }
    }
}

/**
 * 设置父级对话框
 */
@Composable
fun SetParentDialog(
    fence: Geofence,
    allFences: List<Geofence>,
    @Suppress("UNUSED_PARAMETER") _treeManager: FenceTreeManager,
    onConfirm: (parentId: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    // 排除自身和自身后代（防止循环）
    val descendantIds = mutableSetOf<String>()
    fun collectDescendants(parentId: String) {
        allFences.filter { it.parentId == parentId }.forEach { child ->
            descendantIds.add(child.id)
            collectDescendants(child.id)
        }
    }
    collectDescendants(fence.id)

    val candidates = allFences.filter {
        it.id != fence.id && it.id !in descendantIds
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.set_parent_fence)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.select_parent_for, fence.name),
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(8.dp))

                // "无"选项
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onConfirm(null) }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = fence.parentId == null,
                        onClick = { onConfirm(null) }
                    )
                    Text(stringResource(R.string.top_level_no_parent), modifier = Modifier.padding(start = 8.dp))
                }

                HorizontalDivider()

                // 候选父级列表
                LazyColumn(modifier = Modifier.heightIn(max = 200.dp)) {
                    items(candidates) { candidate ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onConfirm(candidate.id) }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = fence.parentId == candidate.id,
                                onClick = { onConfirm(candidate.id) }
                            )
                            Text(
                                candidate.name,
                                modifier = Modifier.padding(start = 8.dp),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        }
    )
}
