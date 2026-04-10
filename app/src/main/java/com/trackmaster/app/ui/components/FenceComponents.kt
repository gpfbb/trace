package com.trackmaster.app.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.trackmaster.app.MainActivity
import com.trackmaster.app.data.GeoFence
import com.trackmaster.app.core.GeoFenceType
import com.trackmaster.app.ui.theme.*

/**
 * Radio button with label component
 */
@Composable
fun RadioButtonWithLabel(
    selected: Boolean,
    label: String,
    description: String? = null,
    onSelected: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 6.dp)
            .clickable { onSelected() },
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = onSelected,
            colors = RadioButtonDefaults.colors(
                selectedColor = NeonCoral,
                unselectedColor = TextSecondary
            )
        )
        Column(modifier = Modifier.padding(start = 10.dp)) {
            ResponsiveSubtitleText(
                text = label,
                priority = if (selected) TextPriority.PRIMARY else TextPriority.SECONDARY,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
            )
            if (description != null) {
                Spacer(modifier = Modifier.height(2.dp))
                ResponsiveLabelText(
                    text = description,
                    priority = TextPriority.TERTIARY
                )
            }
        }
    }
}

/**
 * Fence item card component
 */
@Composable
fun FenceItem(fence: GeoFence, mainActivity: MainActivity) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp, horizontal = 4.dp)
            .clickable {
                mainActivity.highlightGeoFence(fence.id)
            },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
        ),
        shape = RoundedCornerShape(6.dp),
        elevation = CardDefaults.cardElevation(1.dp)
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = fence.name,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                Badge(
                    containerColor = when (fence.type) {
                        GeoFenceType.CIRCLE -> MaterialTheme.colorScheme.primary
                        GeoFenceType.POLYGON -> MaterialTheme.colorScheme.secondary
                    }
                ) {
                    Text(
                        text = when (fence.type) {
                            GeoFenceType.CIRCLE -> "圆形"
                            GeoFenceType.POLYGON -> "多边形"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
                IconButton(
                    onClick = { mainActivity.deleteGeoFence(fence.id) },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        Icons.Default.DeleteForever,
                        contentDescription = "删除围栏",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
            Text(
                text = "创建时间: ${fence.createdAt}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

/**
 * Fence item with move actions component
 */
@Composable
fun FenceItemWithMove(
    fence: GeoFence,
    mainActivity: MainActivity,
    onEditClick: () -> Unit,
    onCategoryClick: () -> Unit,
    onDeleteClick: (GeoFence) -> Unit
) {
    AnimatedVisibility(
        visible = true,
        enter = fadeIn(animationSpec = tween(300)) + slideInVertically(
            initialOffsetY = { -40 },
            animationSpec = tween(300)
        ),
        exit = fadeOut(animationSpec = tween(300)) + slideOutVertically(
            targetOffsetY = { 40 },
            animationSpec = tween(300)
        )
    ) {
        GlassCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp, horizontal = 6.dp)
                .clickable {
                    mainActivity.highlightGeoFence(fence.id)
                }
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ResponsiveSubtitleText(
                        text = fence.name,
                        priority = TextPriority.PRIMARY,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    GlassBadge(
                        containerColor = when (fence.type) {
                            GeoFenceType.CIRCLE -> NeonCoral
                            GeoFenceType.POLYGON -> NeonAmber
                        }
                    ) {
                        Text(
                            text = when (fence.type) {
                                GeoFenceType.CIRCLE -> "圆形"
                                GeoFenceType.POLYGON -> "多边形"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (fence.type == GeoFenceType.POLYGON) Color.Black else Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))

                    // Category button - move fence to category
                    GlassFloatingActionButton(
                        onClick = onCategoryClick,
                        modifier = Modifier.size(44.dp),
                        containerColor = NeonBlue.copy(alpha = 0.4f)
                    ) {
                        Icon(
                            Icons.Default.Category,
                            contentDescription = "移动围栏到分类",
                            tint = Color.White,
                            modifier = Modifier.size(40.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    // Edit fence button
                    GlassFloatingActionButton(
                        onClick = onEditClick,
                        modifier = Modifier.size(44.dp),
                        containerColor = NeonCoral.copy(alpha = 0.4f)
                    ) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = "编辑围栏",
                            tint = Color.White,
                            modifier = Modifier.size(40.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    // Delete fence button
                    GlassFloatingActionButton(
                        onClick = { onDeleteClick(fence) },
                        modifier = Modifier.size(44.dp),
                        containerColor = Danger.copy(alpha = 0.4f)
                    ) {
                        Icon(
                            Icons.Default.DeleteForever,
                            contentDescription = "删除围栏",
                            tint = Color.White,
                            modifier = Modifier.size(40.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                ResponsiveLabelText(
                    text = "创建时间: ${fence.createdAt}",
                    priority = TextPriority.TERTIARY,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}
