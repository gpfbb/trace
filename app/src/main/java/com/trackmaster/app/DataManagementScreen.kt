package com.trackmaster.app

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.*
import com.trackmaster.app.R
import com.trackmaster.app.ui.design.currentPalette
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import com.trackmaster.app.ui.design.DesignSecondaryButton

@Composable
fun DataManagementScreen(
    onDismiss: () -> Unit,
    dataManager: DataManager,
    app: TrackMasterApplication
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val prefs = app.appPreferences

    var storageInfo by remember { mutableStateOf(StorageInfo(0, 0, 0, 0)) }
    var exportFiles by remember { mutableStateOf<List<File>>(emptyList()) }
    var isExporting by remember { mutableStateOf(false) }
    var isImporting by remember { mutableStateOf(false) }
    var exportMessage by remember { mutableStateOf("") }
    var importMessage by remember { mutableStateOf("") }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showSuccessDialog by remember { mutableStateOf(false) }
    var successMessage by remember { mutableStateOf("") }

    // 自动清理设置
    var autoCleanupDays by remember { mutableStateOf(prefs.autoCleanupDays) }
    var showAutoCleanupDialog by remember { mutableStateOf(false) }

    val ctx = context

    fun refreshData() {
        scope.launch {
            storageInfo = dataManager.getStorageInfo()
            exportFiles = dataManager.getExportFiles()
        }
    }

    // 文件选择器：导入
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                isImporting = true
                importMessage = ctx.getString(R.string.reading_file)

                val tempFile = copyUriToFile(context, uri)
                if (tempFile != null) {
                    importMessage = ctx.getString(R.string.importing_data)
                    val result = dataManager.importData(tempFile.absolutePath)

                    isImporting = false
                    if (result.success) {
                        if (result.importedFences > 0) {
                            app.geoFences.clear()
                            app.geoFences.addAll(app.dbHelper.getAllFences())
                            app.refreshEngineFences()
                        }
                        successMessage = ctx.getString(R.string.import_success, result.importedFences, result.importedEvents, result.importedStays)
                        showSuccessDialog = true
                        refreshData()
                    } else {
                        successMessage = ctx.getString(R.string.import_failed, result.message)
                        showSuccessDialog = true
                    }
                } else {
                    isImporting = false
                    successMessage = ctx.getString(R.string.file_read_error)
                    showSuccessDialog = true
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        refreshData()
    }
    com.trackmaster.app.ui.design.AppDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(R.string.data_import_export),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = currentPalette.accent
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 500.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                StorageInfoCard(storageInfo = storageInfo)

                HorizontalDivider(color = currentPalette.separator)

                // Export Import Section
                ExportImportSection(
                    onExportAll = {
                        scope.launch {
                            isExporting = true
                            exportMessage = ctx.getString(R.string.preparing_export)
                            val result = dataManager.exportData(
                                options = DataExportOptions(exportAll = true),
                                onProgress = { _, msg -> exportMessage = msg }
                            )
                            isExporting = false
                            if (result.success) {
                                successMessage = ctx.getString(R.string.export_success,
                                    result.exportedFences, result.exportedEvents,
                                    result.exportedStays, result.exportedDailyStays,
                                    result.filePath ?: "")
                            } else {
                                successMessage = ctx.getString(R.string.export_failed, result.message)
                            }
                            showSuccessDialog = true
                            refreshData()
                        }
                    },
                    onShareFences = {
                        val fences = app.geoFences.toList()
                        if (fences.isEmpty()) {
                            successMessage = ctx.getString(R.string.no_fences_to_share)
                            showSuccessDialog = true
                            return@ExportImportSection
                        }
                        scope.launch {
                            isExporting = true
                            exportMessage = ctx.getString(R.string.generating_fence_template)
                            try {
                                val jsonContent = app.exportFencesToJson(fences)
                                if (jsonContent != null) {
                                    val fileName = "TrackMaster_Fence_${SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())}.json"
                                    val file = File(context.cacheDir, fileName)
                                    file.writeText(jsonContent)
                                    val uri = androidx.core.content.FileProvider.getUriForFile(
                                        context, "${context.packageName}.fileprovider", file
                                    )
                                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "application/json"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        putExtra(Intent.EXTRA_SUBJECT, ctx.getString(R.string.share_fence_title))
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(Intent.createChooser(shareIntent, ctx.getString(R.string.share_fence)).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    })
                                    successMessage = ctx.getString(R.string.fence_share_ready, fences.size)
                                } else {
                                    successMessage = ctx.getString(R.string.fence_generate_error)
                                }
                            } catch (e: Exception) {
                                successMessage = ctx.getString(R.string.share_failed, e.message ?: "Unknown")
                            }
                            isExporting = false
                            showSuccessDialog = true
                        }
                    },
                    onImportFile = {
                        filePickerLauncher.launch("application/json")
                    },
                    isExporting = isExporting,
                    exportMessage = exportMessage,
                    isImporting = isImporting,
                    importMessage = importMessage
                )

                HorizontalDivider(color = currentPalette.separator)

                // Auto Cleanup Section
                AutoCleanupSection(
                    autoCleanupDays = autoCleanupDays,
                    onOpenSettings = { showAutoCleanupDialog = true },
                    onCleanupNow = {
                        scope.launch {
                            val deleted = dataManager.deleteEventsBefore(
                                Date(System.currentTimeMillis() - autoCleanupDays * 24 * 60 * 60 * 1000L)
                            )
                            successMessage = ctx.getString(R.string.cleaned_old_logs, deleted)
                            showSuccessDialog = true
                            refreshData()
                        }
                    }
                )

                HorizontalDivider(color = currentPalette.separator)

                // Delete Section
                DeleteSection(
                    onDeleteAll = { showDeleteDialog = true }
                )

                HorizontalDivider(color = currentPalette.separator)

                // Exported Files Section
                ExportedFilesSection(
                    files = exportFiles,
                    onDeleteFile = { file ->
                        scope.launch {
                            file.delete()
                            refreshData()
                        }
                    },
                    onImportFile = { file ->
                        scope.launch {
                            isImporting = true
                            importMessage = ctx.getString(R.string.importing_data)
                            val result = dataManager.importData(file.absolutePath)
                            isImporting = false
                            if (result.success) {
                                if (result.importedFences > 0) {
                                    app.geoFences.clear()
                                    app.geoFences.addAll(app.dbHelper.getAllFences())
                                    app.refreshEngineFences()
                                }
                                successMessage = ctx.getString(R.string.import_success, result.importedFences, result.importedEvents, result.importedStays)
                            } else {
                                successMessage = result.message
                            }
                            showSuccessDialog = true
                            refreshData()
                        }
                    }
                )
            }
        },
        confirmButton = {
            com.trackmaster.app.ui.design.DesignSecondaryButton(onClick = onDismiss) {
                Text(stringResource(R.string.close), color = currentPalette.accent)
            }
        }
    )

    // Delete Confirmation Dialog
    if (showDeleteDialog) {
        DeleteOptionsDialog(
            onDismiss = { showDeleteDialog = false },
            onConfirm = { beforeDate ->
                scope.launch {
                    val deletedCount = dataManager.deleteData(beforeDate)
                    showDeleteDialog = false
                    successMessage = ctx.getString(R.string.delete_success_records, deletedCount)
                    showSuccessDialog = true
                    refreshData()
                }
            }
        )
    }

    // Auto Cleanup Dialog
    if (showAutoCleanupDialog) {
        AutoCleanupDialog(
            currentDays = autoCleanupDays,
            onDismiss = { showAutoCleanupDialog = false },
            onConfirm = { days ->
                autoCleanupDays = days
                prefs.autoCleanupDays = days
                showAutoCleanupDialog = false
            }
        )
    }

    // currentPalette.success/Failure Dialog
    if (showSuccessDialog) {
        com.trackmaster.app.ui.design.AppDialog(
            onDismissRequest = { showSuccessDialog = false },
            title = { Text(stringResource(R.string.operation_complete), color = currentPalette.accent) },
            text = {
                Text(
                    successMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = currentPalette.label
                )
            },
            confirmButton = {
                com.trackmaster.app.ui.design.DesignSecondaryButton(onClick = { showSuccessDialog = false }) {
                    Text(stringResource(R.string.confirm), color = currentPalette.accent)
                }
            }
        )
    }
}

@Composable
private fun StorageInfoCard(storageInfo: StorageInfo) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = currentPalette.groupedCell
        ),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                stringResource(R.string.storage_status),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = currentPalette.accent
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                StorageStatItem(label = stringResource(R.string.fence_quantity), value = storageInfo.fenceCount.toString())
                StorageStatItem(label = stringResource(R.string.event_record), value = storageInfo.eventCount.toString())
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                StorageStatItem(label = stringResource(R.string.stay_record), value = storageInfo.stayCount.toString())
                StorageStatItem(label = stringResource(R.string.database_size), value = storageInfo.formattedSize)
            }
        }
    }
}

@Composable
private fun StorageStatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = currentPalette.accent
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = currentPalette.labelSecondary
        )
    }
}

@Composable
private fun ExportImportSection(
    onExportAll: () -> Unit,
    onShareFences: () -> Unit,
    onImportFile: () -> Unit,
    isExporting: Boolean,
    exportMessage: String,
    isImporting: Boolean,
    importMessage: String
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringResource(R.string.export_import),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = currentPalette.accent
        )

        if (isExporting) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0x33FF7F50)
                ),
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = currentPalette.accent
                    )
                    Text(text = exportMessage, style = MaterialTheme.typography.bodySmall, color = currentPalette.label)
                }
            }
        } else if (isImporting) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0x33FFBF00)
                ),
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = currentPalette.warning)
                    Text(text = importMessage, style = MaterialTheme.typography.bodySmall, color = currentPalette.label)
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 三个按钮统一用设计系统的按钮，不再各自指定文字色
                // （旧代码给后两个传 Color.White，在浅色卡片上等于隐形）
                DataActionButton(
                    text = stringResource(R.string.share_fence),
                    icon = Icons.Default.Add,
                    onClick = onShareFences,
                    modifier = Modifier.weight(1f)
                )
                DataActionButton(
                    text = stringResource(R.string.export_all),
                    onClick = onExportAll,
                    modifier = Modifier.weight(1f)
                )
                DataActionButton(
                    text = stringResource(R.string.import_action),
                    icon = Icons.Default.Add,
                    onClick = onImportFile,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/**
 * 数据管理页的操作按钮。
 *
 * 固定为"淡强调色底 + 强调色文字"，图标与文字同色，
 * 不再依赖调用方传进来的文字色（那是旧暗色主题的遗留做法）。
 */
@Composable
private fun DataActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null
) {
    val palette = currentPalette
    Row(
        modifier = modifier
            .heightIn(min = 64.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(palette.accentSubtle)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = palette.accent,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(4.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = palette.accent,
            textAlign = TextAlign.Center,
            maxLines = 2
        )
    }
}

@Composable
private fun AutoCleanupSection(
    autoCleanupDays: Int,
    onOpenSettings: () -> Unit,
    onCleanupNow: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.auto_cleanup_section),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = currentPalette.accent
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.auto_cleanup_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = currentPalette.label
                )
                Text(
                    stringResource(R.string.keep_recent_days, autoCleanupDays),
                    style = MaterialTheme.typography.bodySmall,
                    color = currentPalette.labelSecondary
                )
            }
            TextButton(onClick = onOpenSettings) {
                Text(stringResource(R.string.settings_button), color = currentPalette.accent, fontSize = 13.sp)
            }
        }

        com.trackmaster.app.ui.design.DesignSecondaryButton(
            onClick = onCleanupNow,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(imageVector = Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp), tint = currentPalette.danger)
            Spacer(modifier = Modifier.width(4.dp))
            Text(stringResource(R.string.cleanup_now), color = currentPalette.danger, fontSize = 13.sp)
        }

        Text(
            stringResource(R.string.cleanup_reminder),
            style = MaterialTheme.typography.bodySmall,
            // 用次级灰而不是三级灰：这是需要用户读的说明，不是装饰
            color = currentPalette.labelSecondary
        )
    }
}

@Composable
private fun AutoCleanupDialog(
    currentDays: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    val options = listOf(3, 7, 14, 30, 90)
    var selected by remember { mutableStateOf(if (options.contains(currentDays)) currentDays else 7) }

    com.trackmaster.app.ui.design.AppDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.auto_cleanup_dialog_title), color = currentPalette.accent) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.auto_cleanup_dialog_desc), style = MaterialTheme.typography.bodyMedium, color = currentPalette.label)
                Spacer(modifier = Modifier.height(8.dp))
                options.forEach { days ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selected = days }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selected == days,
                            onClick = { selected = days },
                            colors = RadioButtonDefaults.colors(selectedColor = currentPalette.accent)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.days_option, days), color = currentPalette.label)
                    }
                }
            }
        },
        confirmButton = {
            com.trackmaster.app.ui.design.DesignSecondaryButton(onClick = { onConfirm(selected) }) {
                Text(stringResource(R.string.save), color = currentPalette.accent)
            }
        },
        dismissButton = {
            com.trackmaster.app.ui.design.DesignSecondaryButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel), color = currentPalette.accent)
            }
        }
    )
}

@Composable
private fun DeleteSection(onDeleteAll: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringResource(R.string.delete_section),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = currentPalette.accent
        )
        Text(
            stringResource(R.string.delete_data_desc),
            style = MaterialTheme.typography.bodySmall,
            color = currentPalette.labelSecondary
        )
        com.trackmaster.app.ui.design.DesignSecondaryButton(
            onClick = onDeleteAll,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(imageVector = Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp), tint = currentPalette.danger)
            Spacer(modifier = Modifier.width(4.dp))
            Text(stringResource(R.string.delete_data_button), color = currentPalette.danger)
        }
    }
}

@Composable
private fun ExportedFilesSection(
    files: List<File>,
    onDeleteFile: (File) -> Unit,
    onImportFile: (File) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringResource(R.string.exported_files_section),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = currentPalette.accent
        )
        if (files.isEmpty()) {
            Text(stringResource(R.string.no_exported_files), style = MaterialTheme.typography.bodySmall, color = currentPalette.labelSecondary)
        } else {
            files.take(5).forEach { file ->
                FileItem(
                    file = file,
                    onDelete = { onDeleteFile(file) },
                    onImport = { onImportFile(file) }
                )
            }
        }
    }
}

@Composable
private fun FileItem(file: File, onDelete: () -> Unit, onImport: () -> Unit) {
    val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onImport() },
        colors = CardDefaults.cardColors(containerColor = currentPalette.groupedCell),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = file.name, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, color = currentPalette.label)
                Text(
                    text = "${dateFormat.format(Date(file.lastModified()))} · %.1f KB".format(file.length() / 1024.0),
                    style = MaterialTheme.typography.labelSmall,
                    color = currentPalette.labelSecondary
                )
                Text(text = stringResource(R.string.click_to_import), style = MaterialTheme.typography.labelSmall, color = currentPalette.accent)
            }
            IconButton(onClick = onDelete) {
                Icon(imageVector = Icons.Default.Delete, contentDescription = stringResource(R.string.delete), tint = currentPalette.danger)
            }
        }
    }
}

@Composable
private fun DeleteOptionsDialog(onDismiss: () -> Unit, onConfirm: (Date?) -> Unit) {
    var deleteAll by remember { mutableStateOf(true) }
    var deleteBefore by remember { mutableStateOf(false) }
    val calendar = Calendar.getInstance()
    calendar.add(Calendar.MONTH, -1)
    val oneMonthAgo = calendar.time

    com.trackmaster.app.ui.design.AppDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_options_title), color = currentPalette.accent) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.delete_warning), color = currentPalette.danger, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = deleteAll, onCheckedChange = { deleteAll = it; if (it) deleteBefore = false }, colors = CheckboxDefaults.colors(checkedColor = currentPalette.accent))
                    Text(stringResource(R.string.delete_all_data), color = currentPalette.label)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = deleteBefore, onCheckedChange = { deleteBefore = it; if (it) deleteAll = false }, colors = CheckboxDefaults.colors(checkedColor = currentPalette.accent))
                    Text(stringResource(R.string.delete_before_date), color = currentPalette.label)
                }
            }
        },
        confirmButton = {
            com.trackmaster.app.ui.design.DesignSecondaryButton(onClick = {
                val before = when {
                    deleteAll -> null
                    deleteBefore -> oneMonthAgo
                    else -> null
                }
                onConfirm(before)
            }) {
                Text(stringResource(R.string.confirm_delete_button), color = currentPalette.accent)
            }
        },
        dismissButton = {
            com.trackmaster.app.ui.design.DesignSecondaryButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel), color = currentPalette.accent)
            }
        }
    )
}

fun copyUriToFile(context: android.content.Context, uri: android.net.Uri): File? {
    return try {
        val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
        val fileName = "import_${System.currentTimeMillis()}.json"
        val tempFile = File(context.cacheDir, fileName)
        inputStream?.use { input ->
            FileOutputStream(tempFile).use { output ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                }
            }
        }
        tempFile
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

