package com.trackmaster.app.ui.map

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.trackmaster.app.R
import com.trackmaster.app.core.FenceTreeFlattener
import com.trackmaster.app.core.FenceTreeManager
import com.trackmaster.app.core.GeoFenceType
import com.trackmaster.app.data.GeoFence
import com.trackmaster.app.data.GeoFencePoint
import com.trackmaster.app.ui.design.AppIcons
import com.trackmaster.app.ui.design.AppType
import com.trackmaster.app.ui.design.PrimaryButton
import com.trackmaster.app.ui.design.Space
import com.trackmaster.app.ui.design.currentPalette
import androidx.compose.foundation.border

/**
 * 地图页的四个围栏弹窗：新建、编辑、列出、编辑中。
 *
 * 从 `MapScreen` 拆出来的。这些弹窗原来把文字色写死成 `Color.White` /
 * `Color.Gray`，在旧暗色主题下没问题；新设计系统的卡片是白底，
 * 于是整个围栏列表变成白底白字、完全读不了。这里统一改用调色板。
 */

// ============================================================
// 新建围栏
// ============================================================

@Composable
fun CreateFenceDialog(
    fenceType: GeoFenceType,
    points: List<GeoFencePoint>,
    color: String,
    alpha: Float,
    onDismiss: () -> Unit,
    onConfirm: (name: String, color: String, alpha: Float, radius: Float) -> Unit
) {
    val palette = currentPalette
    var name by remember { mutableStateOf("") }
    var radius by remember { mutableStateOf(100f) }

    AlertDialog(
        onDismissRequest = onDismiss,
        // Material 的默认弹窗底色会叠加 tonalElevation，被主色染成
        // 淡色。显式指定纯色卡片底，和分组卡片保持一致。
        containerColor = palette.groupedCell,
        title = { Text(text = stringResource(R.string.create_fence), style = AppType.title2) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Space.md)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.fence_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                when (fenceType) {
                    GeoFenceType.CIRCLE -> {
                        Text(
                            text = stringResource(R.string.radius_meters, radius.toInt()),
                            style = AppType.footnote,
                            color = palette.labelSecondary
                        )
                        Slider(
                            value = radius,
                            onValueChange = { radius = it },
                            valueRange = 50f..1000f,
                            steps = 18
                        )
                    }

                    GeoFenceType.POLYGON -> Text(
                        text = stringResource(R.string.vertices_count, points.size),
                        style = AppType.footnote,
                        color = palette.labelSecondary
                    )
                }
                Text(
                    text = stringResource(R.string.fence_hint),
                    style = AppType.caption,
                    color = palette.labelSecondary
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name, color, alpha, radius) },
                enabled = name.isNotBlank()
            ) {
                Text(
                    text = stringResource(R.string.create),
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
}

// ============================================================
// 围栏列表（父子树 + 展开收起 + 编辑/删除）
// ============================================================

@Composable
fun FenceListDialog(
    fences: List<GeoFence>,
    fenceTreeManager: FenceTreeManager,
    currentMatchedIds: Set<String>,
    highlightedFenceId: String?,
    onDismiss: () -> Unit,
    onHighlightClick: (GeoFence) -> Unit,
    onEditClick: (GeoFence) -> Unit,
    onDeleteClick: (GeoFence) -> Unit
) {
    val palette = currentPalette
    var expandedFenceIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    // fenceTreeManager.version 作为 key：重新计算父子关系后能刷新
    val parentMap by remember(fenceTreeManager.version) {
        mutableStateOf(fenceTreeManager.getParentMap())
    }

    // 有子围栏的节点才显示展开箭头。用 parentMap 反查，
    // 注意成环时这里也会返回 true，展开逻辑本身由 Flattener 兜住。
    val parentIds = remember(parentMap) { parentMap.values.toSet() }

    val visibleFences = remember(expandedFenceIds, fences, parentMap) {
        FenceTreeFlattener.flatten(fences, parentMap, expandedFenceIds)
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
            shape = RoundedCornerShape(24.dp),
            color = palette.groupedCell,
            tonalElevation = 0.dp
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(Space.lg),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = AppIcons.mapService,
                            contentDescription = null,
                            tint = palette.accent,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(Modifier.width(Space.md))
                        Text(
                            text = stringResource(R.string.fence_list_title, visibleFences.size),
                            style = AppType.headline,
                            color = palette.label
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = AppIcons.close,
                            contentDescription = stringResource(R.string.close),
                            tint = palette.labelSecondary
                        )
                    }
                }

                HorizontalDivider(color = palette.separator)

                if (visibleFences.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = AppIcons.outsideFence,
                                contentDescription = null,
                                tint = palette.labelTertiary,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(Modifier.height(Space.sm))
                            Text(
                                text = stringResource(R.string.no_fences),
                                style = AppType.body,
                                color = palette.labelSecondary
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = Space.sm)
                    ) {
                        items(visibleFences) { (fence, depth) ->
                            FenceListRow(
                                fence = fence,
                                depth = depth,
                                matched = fence.id in currentMatchedIds,
                                isHighlighted = fence.id == highlightedFenceId,
                                hasChildren = fence.id in parentIds,
                                isExpanded = fence.id in expandedFenceIds,
                                onToggleExpand = {
                                    expandedFenceIds = if (fence.id in expandedFenceIds) {
                                        expandedFenceIds - fence.id
                                    } else {
                                        expandedFenceIds + fence.id
                                    }
                                },
                                onHighlightClick = { onHighlightClick(fence) },
                                onEditClick = { onEditClick(fence) },
                                onDeleteClick = { onDeleteClick(fence) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FenceListRow(
    fence: GeoFence,
    depth: Int,
    matched: Boolean,
    isHighlighted: Boolean,
    hasChildren: Boolean,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onHighlightClick: () -> Unit,
    onEditClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    val palette = currentPalette
    val indent = (depth * 16).dp

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.sm)
            .clip(RoundedCornerShape(Space.sm))
            .background(
                when {
                    isHighlighted -> palette.accentSubtle
                    matched -> palette.success.copy(alpha = 0.10f)
                    else -> Color.Transparent
                }
            )
            .clickable(onClick = onHighlightClick)
            .padding(start = indent + Space.sm, end = Space.sm, top = Space.md, bottom = Space.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (hasChildren) {
            IconButton(onClick = onToggleExpand, modifier = Modifier.size(24.dp)) {
                Icon(
                    imageVector = AppIcons.expandChevron(isExpanded),
                    contentDescription = stringResource(
                        if (isExpanded) R.string.collapse_short else R.string.expand_short
                    ),
                    tint = palette.labelSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }
        } else {
            Spacer(Modifier.width(24.dp))
        }

        // 围栏自己的颜色（用户选的），不是主题色
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(safeParseColor(fence.color, fallback = palette.accent))
        )
        Spacer(Modifier.width(Space.md))

        Column(Modifier.weight(1f)) {
            Text(
                text = fence.name,
                style = AppType.body,
                fontWeight = FontWeight.Medium,
                color = palette.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = when (fence.type) {
                    GeoFenceType.CIRCLE ->
                        stringResource(R.string.circle_with_radius, fence.radius?.toInt() ?: 0)

                    GeoFenceType.POLYGON ->
                        stringResource(R.string.polygon_with_vertices, fence.points?.size ?: 0)
                },
                style = AppType.caption,
                color = palette.labelSecondary
            )
        }

        if (matched) {
            Icon(
                imageVector = AppIcons.checkCircle,
                contentDescription = stringResource(R.string.hit),
                tint = palette.success,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(Space.xs))
        }
        if (isHighlighted) {
            Icon(
                imageVector = AppIcons.insideFence,
                contentDescription = stringResource(R.string.highlighted),
                tint = palette.accent,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(Space.xs))
        }

        IconButton(onClick = onEditClick, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = AppIcons.edit,
                contentDescription = stringResource(R.string.edit),
                tint = palette.accent,
                modifier = Modifier.size(18.dp)
            )
        }
        IconButton(onClick = onDeleteClick, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = AppIcons.delete,
                contentDescription = stringResource(R.string.delete),
                tint = palette.danger,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

// ============================================================
// 编辑围栏（改名字/颜色/透明度/半径，或进入地图上改顶点）
// ============================================================

@Composable
fun EditFenceDialog(
    fence: GeoFence,
    fenceColors: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (GeoFence) -> Unit,
    onDelete: () -> Unit
) {
    val palette = currentPalette
    var name by remember { mutableStateOf(fence.name) }
    var color by remember { mutableStateOf(fence.color) }
    var alpha by remember { mutableStateOf(fence.alpha) }
    var radius by remember { mutableStateOf(fence.radius ?: 100f) }

    AlertDialog(
        onDismissRequest = onDismiss,
        // Material 的默认弹窗底色会叠加 tonalElevation，被主色染成
        // 淡色。显式指定纯色卡片底，和分组卡片保持一致。
        containerColor = palette.groupedCell,
        title = { Text(text = stringResource(R.string.edit_fence), style = AppType.title2) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Space.md)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.fence_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    text = stringResource(R.string.color),
                    style = AppType.caption,
                    color = palette.labelSecondary
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(Space.xs)
                ) {
                    fenceColors.forEach { candidateColor ->
                        ColorSwatch(
                            colorHex = candidateColor,
                            selected = color == candidateColor,
                            onClick = { color = candidateColor }
                        )
                    }
                }

                Text(
                    text = stringResource(R.string.transparency, (alpha * 100).toInt()),
                    style = AppType.caption,
                    color = palette.labelSecondary
                )
                Slider(
                    value = alpha,
                    onValueChange = { alpha = it },
                    modifier = Modifier.fillMaxWidth(),
                    valueRange = 0.1f..0.8f,
                    steps = 6
                )

                if (fence.type == GeoFenceType.CIRCLE) {
                    Text(
                        text = stringResource(R.string.radius_meters, radius.toInt()),
                        style = AppType.caption,
                        color = palette.labelSecondary
                    )
                    Slider(
                        value = radius,
                        onValueChange = { radius = it },
                        modifier = Modifier.fillMaxWidth(),
                        valueRange = 50f..1000f,
                        steps = 18
                    )
                }

                Text(
                    text = stringResource(R.string.edit_on_map_hint),
                    style = AppType.caption,
                    color = palette.labelSecondary
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        fence.copy(
                            name = name,
                            color = color,
                            alpha = alpha,
                            radius = if (fence.type == GeoFenceType.CIRCLE) radius else fence.radius
                        )
                    )
                }
            ) {
                Text(text = stringResource(R.string.edit_on_map), color = palette.accent)
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDelete) {
                    Text(text = stringResource(R.string.delete), color = palette.danger)
                }
                TextButton(onClick = onDismiss) {
                    Text(text = stringResource(R.string.cancel), color = palette.labelSecondary)
                }
            }
        }
    )
}

/** 颜色色块。选中态用主题文字色的描边，而不是叠一层半透明白——后者在浅色下看不见。 */
@Composable
private fun ColorSwatch(
    colorHex: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val palette = currentPalette
    val swatchColor = safeParseColor(colorHex, fallback = palette.labelTertiary)

    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            // 选中态用主题文字色的描边环，两种模式下都看得见。
            // 旧实现是叠一层半透明白，浅色背景上等于没有。
            .then(
                if (selected) {
                    Modifier.border(2.dp, palette.label, CircleShape)
                } else {
                    Modifier
                }
            )
            .padding(if (selected) 4.dp else 2.dp)
            .clip(CircleShape)
            .background(swatchColor)
            .clickable(onClick = onClick)
    )
}

/**
 * 把 `#RRGGBB` 解析成 Color。颜色值来自数据库，可能是用户导入的非法值，
 * 解析失败时退回 [fallback] 而不是抛异常崩掉整个列表。
 */
internal fun safeParseColor(hex: String, fallback: Color): Color =
    runCatching { Color(AndroidColor.parseColor(hex)) }.getOrDefault(fallback)
