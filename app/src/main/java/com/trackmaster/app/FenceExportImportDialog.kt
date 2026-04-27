package com.trackmaster.app

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import com.trackmaster.app.data.GeoFence
import com.trackmaster.app.core.GeoFenceType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.trackmaster.app.ui.theme.GlassButton
import com.trackmaster.app.ui.theme.NeonAmber
import com.trackmaster.app.ui.theme.NeonBlue
import com.trackmaster.app.R
import com.trackmaster.app.ui.theme.NeonCoral
import java.io.BufferedReader
import java.io.InputStreamReader

@Composable
fun FenceExportImportDialog(
    onDismiss: () -> Unit,
    fences: List<GeoFence>,
    onExport: (List<GeoFence>) -> Unit,
    onImport: (String) -> Unit
) {
    val context = LocalContext.current
    var selectedFences by remember { mutableStateOf<Set<String>>(emptySet()) }
    var isExportMode by remember { mutableStateOf(true) } // true = export, false = import

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            try {
                context.contentResolver.openInputStream(it)?.use { inputStream ->
                    BufferedReader(InputStreamReader(inputStream)).use { reader ->
                        val content = reader.readText()
                        onImport(content)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isExportMode) stringResource(R.string.share_fence) else stringResource(R.string.import_fence),
                    fontWeight = FontWeight.Bold
                )
                Row {
                    // 切换标签
                    GlassButton(
                        onClick = { isExportMode = !isExportMode },
                        modifier = Modifier.height(32.dp)
                    ) {
                        Icon(
                            imageVector = if (isExportMode) Icons.Default.Share else Icons.Default.FileDownload,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (isExportMode) stringResource(R.string.import_short) else stringResource(R.string.share_short),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        },
        text = {
            Column {
                if (isExportMode) {
                    // 分享模式：选择围栏
                    if (fences.isEmpty()) {
                        Text(
                            text = stringResource(R.string.no_fences_to_export),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.select_fences_to_share, selectedFences.size, fences.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 300.dp)
                        ) {
                            items(fences) { fence ->
                                FenceSelectItem(
                                    fence = fence,
                                    isSelected = selectedFences.contains(fence.id),
                                    onToggle = {
                                        selectedFences = if (selectedFences.contains(fence.id)) {
                                            selectedFences - fence.id
                                        } else {
                                            selectedFences + fence.id
                                        }
                                    }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            GlassButton(
                                onClick = {
                                    selectedFences = if (selectedFences.size == fences.size) {
                                        emptySet()
                                    } else {
                                        fences.map { it.id }.toSet()
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = if (selectedFences.size == fences.size) stringResource(R.string.deselect_all) else stringResource(R.string.select_all),
                                    color = NeonBlue,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            GlassButton(
                                onClick = {
                                    val selected = fences.filter { selectedFences.contains(it.id) }
                                    onExport(selected)
                                    onDismiss()
                                },
                                enabled = selectedFences.isNotEmpty(),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    Icons.Default.Share,
                                    contentDescription = null,
                                    tint = NeonBlue,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(stringResource(R.string.share_short), color = NeonBlue)
                            }
                        }
                    }
                } else {
                    // 导入模式
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Icon(
                            Icons.Default.FileDownload,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = NeonAmber
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.import_from_file),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.select_fence_file),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        GlassButton(
                            onClick = { filePickerLauncher.launch("application/json") },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                Icons.Default.FileUpload,
                                contentDescription = null,
                                tint = NeonAmber,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.select_file), color = NeonAmber)
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            )
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = stringResource(R.string.import_instructions),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "${stringResource(R.string.import_instruction_1)}\n${stringResource(R.string.import_instruction_2)}\n${stringResource(R.string.import_instruction_3)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        }
    )
}

@Composable
private fun FenceSelectItem(
    fence: GeoFence,
    isSelected: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = isSelected,
            onCheckedChange = { onToggle() },
            colors = CheckboxDefaults.colors(
                checkedColor = NeonCoral
            )
        )
        Spacer(modifier = Modifier.width(8.dp))

        // 颜色预览
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(Color(android.graphics.Color.parseColor(fence.color)))
                .border(1.dp, Color.Gray, CircleShape)
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = fence.name,
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = when (fence.type) {
                    GeoFenceType.CIRCLE -> stringResource(R.string.circle_meters, fence.radius?.toInt() ?: 0)
                    GeoFenceType.POLYGON -> stringResource(R.string.polygon_vertices, fence.points?.size ?: 0)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // 透明度指示
        Text(
            text = "${(fence.alpha * 100).toInt()}%",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
