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
import com.trackmaster.app.ui.theme.*

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

    // Pre-compute strings for use in lambdas (stringResource cannot be called in lambdas)
    val readingFileMsg = stringResource(R.string.reading_file)
    val importingDataMsg = stringResource(R.string.importing_data)
    val importSuccessTemplate = stringResource(R.string.import_success, 0, 0, 0)
    val importFailedTemplate = stringResource(R.string.import_failed, "")
    val fileReadErrorMsg = stringResource(R.string.file_read_error)
    val preparingExportMsg = stringResource(R.string.preparing_export)
    val exportSuccessTemplate = stringResource(R.string.export_success, 0, 0, 0, 0, "")
    val exportFailedTemplate = stringResource(R.string.export_failed, "")
    val noFencesToShareMsg = stringResource(R.string.no_fences_to_share)
    val generatingFenceTemplateMsg = stringResource(R.string.generating_fence_template)
    val shareFenceTitleMsg = stringResource(R.string.share_fence_title)
    val shareFenceMsg = stringResource(R.string.share_fence)
    val fenceShareReadyTemplate = stringResource(R.string.fence_share_ready, 0)
    val fenceGenerateErrorMsg = stringResource(R.string.fence_generate_error)
    val shareFailedTemplate = stringResource(R.string.share_failed, "")
    val cleanedOldLogsTemplate = stringResource(R.string.cleaned_old_logs, 0)
    val deleteSuccessTemplate = stringResource(R.string.delete_success_records, 0)

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
                importMessage = readingFileMsg

                val tempFile = copyUriToFile(context, uri)
                if (tempFile != null) {
                    importMessage = importingDataMsg
                    val result = dataManager.importData(tempFile.absolutePath)

                    isImporting = false
                    if (result.success) {
                        successMessage = importSuccessTemplate
                            .replace("%1\$d", result.importedFences.toString())
                            .replace("%2\$d", result.importedEvents.toString())
                            .replace("%3\$d", result.importedStays.toString())
                        showSuccessDialog = true
                        refreshData()
                    } else {
                        successMessage = importFailedTemplate.replace("%s", result.message)
                        showSuccessDialog = true
                    }
                } else {
                    isImporting = false
                    successMessage = fileReadErrorMsg
                    showSuccessDialog = true
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        refreshData()
    }
    GlassDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(R.string.data_import_export),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = NeonCoral
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

                HorizontalDivider(color = Color(0x30FFFFFF))

                // Export Import Section
                ExportImportSection(
                    onExportAll = {
                        scope.launch {
                            isExporting = true
                            exportMessage = preparingExportMsg
                            val result = dataManager.exportData(
                                options = DataExportOptions(exportAll = true),
                                onProgress = { progress, msg ->
                                    exportMessage = msg
                                }
                            )
                            isExporting = false
                            if (result.success) {
                                successMessage = exportSuccessTemplate
                                    .replace("%1", result.exportedFences.toString())
                                    .replace("%2", result.exportedEvents.toString())
                                    .replace("%3", result.exportedStays.toString())
                                    .replace("%4", result.exportedDailyStays.toString())
                                    .replace("%5", result.filePath ?: "")
                            } else {
                                successMessage = exportFailedTemplate.replace("%s", result.message)
                            }
                            showSuccessDialog = true
                            refreshData()
                        }
                    },
                    onShareFences = {
                        val fences = app.geoFences.toList()
                        if (fences.isEmpty()) {
                            successMessage = noFencesToShareMsg
                            showSuccessDialog = true
                            return@ExportImportSection
                        }
                        scope.launch {
                            isExporting = true
                            exportMessage = generatingFenceTemplateMsg
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
                                        putExtra(Intent.EXTRA_SUBJECT, shareFenceTitleMsg)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(Intent.createChooser(shareIntent, shareFenceMsg).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    })
                                    successMessage = fenceShareReadyTemplate.replace("%d", fences.size.toString())
                                } else {
                                    successMessage = fenceGenerateErrorMsg
                                }
                            } catch (e: Exception) {
                                val errorMsg = e.message ?: "Unknown error"
                                successMessage = shareFailedTemplate.replace("%s", errorMsg)
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

                HorizontalDivider(color = Color(0x30FFFFFF))

                // Auto Cleanup Section
                AutoCleanupSection(
                    autoCleanupDays = autoCleanupDays,
                    onOpenSettings = { showAutoCleanupDialog = true },
                    onCleanupNow = {
                        scope.launch {
                            val deleted = dataManager.deleteEventsBefore(
                                Date(System.currentTimeMillis() - autoCleanupDays * 24 * 60 * 60 * 1000L)
                            )
                            successMessage = cleanedOldLogsTemplate.replace("%d", deleted.toString())
                            showSuccessDialog = true
                            refreshData()
                        }
                    }
                )

                HorizontalDivider(color = Color(0x30FFFFFF))

                // Delete Section
                DeleteSection(
                    onDeleteAll = { showDeleteDialog = true }
                )

                HorizontalDivider(color = Color(0x30FFFFFF))

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
                            importMessage = importingDataMsg
                            val result = dataManager.importData(file.absolutePath)
                            isImporting = false
                            if (result.success) {
                                if (result.importedFences > 0) {
                                    app.importFencesAndRefresh(file.readText(), preserveId = false)
                                }
                                successMessage = importSuccessTemplate
                                    .replace("%1", result.importedFences.toString())
                                    .replace("%2", result.importedEvents.toString())
                                    .replace("%3", result.importedStays.toString())
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
            GlassButton(onClick = onDismiss) {
                Text(stringResource(R.string.close), color = Color.White)
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
                    successMessage = deleteSuccessTemplate.replace("%d", deletedCount.toString())
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

    // Success/Failure Dialog
    if (showSuccessDialog) {
        GlassDialog(
            onDismissRequest = { showSuccessDialog = false },
            title = { Text(stringResource(R.string.operation_complete), color = NeonCoral) },
            text = {
                Text(
                    successMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFE0E0E0)
                )
            },
            confirmButton = {
                GlassButton(onClick = { showSuccessDialog = false }) {
                    Text(stringResource(R.string.confirm), color = Color.White)
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
            containerColor = Color(0x4D000000)
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
                color = NeonCoral
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
            color = NeonCoral
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFFBDBDBD)
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
            color = NeonCoral
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
                        color = NeonCoral
                    )
                    Text(text = exportMessage, style = MaterialTheme.typography.bodySmall, color = Color(0xFFE0E0E0))
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
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = NeonAmber)
                    Text(text = importMessage, style = MaterialTheme.typography.bodySmall, color = Color(0xFFE0E0E0))
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                GlassButton(
                    onClick = onShareFences,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp), tint = NeonAmber)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.share_fence), color = NeonAmber)
                }
                GlassButton(
                    onClick = onExportAll,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.export_all), color = Color.White)
                }
                GlassButton(
                    onClick = onImportFile,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp), tint = Color.White)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.import_action), color = Color.White)
                }
            }
        }
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
            color = NeonCoral
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
                    color = Color(0xFFE0E0E0)
                )
                Text(
                    stringResource(R.string.keep_recent_days, autoCleanupDays),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFBDBDBD)
                )
            }
            TextButton(onClick = onOpenSettings) {
                Text(stringResource(R.string.settings_button), color = NeonCoral, fontSize = 13.sp)
            }
        }

        GlassButton(
            onClick = onCleanupNow,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(imageVector = Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp), tint = Danger)
            Spacer(modifier = Modifier.width(4.dp))
            Text(stringResource(R.string.cleanup_now), color = Danger, fontSize = 13.sp)
        }

        Text(
            stringResource(R.string.cleanup_reminder),
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF9E9E9E)
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

    GlassDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.auto_cleanup_dialog_title), color = NeonCoral) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.auto_cleanup_dialog_desc), style = MaterialTheme.typography.bodyMedium, color = Color(0xFFE0E0E0))
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
                            colors = RadioButtonDefaults.colors(selectedColor = NeonCoral)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.days_option, days), color = Color(0xFFE0E0E0))
                    }
                }
            }
        },
        confirmButton = {
            GlassButton(onClick = { onConfirm(selected) }) {
                Text(stringResource(R.string.save), color = Color.White)
            }
        },
        dismissButton = {
            GlassButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel), color = Color.White)
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
            color = NeonCoral
        )
        Text(
            stringResource(R.string.delete_data_desc),
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFFBDBDBD)
        )
        GlassButton(
            onClick = onDeleteAll,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(imageVector = Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp), tint = Danger)
            Spacer(modifier = Modifier.width(4.dp))
            Text(stringResource(R.string.delete_data_button), color = Danger)
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
            color = NeonCoral
        )
        if (files.isEmpty()) {
            Text(stringResource(R.string.no_exported_files), style = MaterialTheme.typography.bodySmall, color = Color(0xFFBDBDBD))
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
        colors = CardDefaults.cardColors(containerColor = Color(0x4D2A221C)),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = file.name, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, color = Color(0xFFE0E0E0))
                Text(
                    text = "${dateFormat.format(Date(file.lastModified()))} · %.1f KB".format(file.length() / 1024.0),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFBDBDBD)
                )
                Text(text = stringResource(R.string.click_to_import), style = MaterialTheme.typography.labelSmall, color = NeonCoral)
            }
            IconButton(onClick = onDelete) {
                Icon(imageVector = Icons.Default.Delete, contentDescription = stringResource(R.string.delete), tint = Danger)
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

    GlassDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_options_title), color = NeonCoral) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.delete_warning), color = Danger, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = deleteAll, onCheckedChange = { deleteAll = it; if (it) deleteBefore = false }, colors = CheckboxDefaults.colors(checkedColor = NeonCoral))
                    Text(stringResource(R.string.delete_all_data), color = Color(0xFFE0E0E0))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = deleteBefore, onCheckedChange = { deleteBefore = it; if (it) deleteAll = false }, colors = CheckboxDefaults.colors(checkedColor = NeonCoral))
                    Text(stringResource(R.string.delete_before_date), color = Color(0xFFE0E0E0))
                }
            }
        },
        confirmButton = {
            GlassButton(onClick = {
                val before = when {
                    deleteAll -> null
                    deleteBefore -> oneMonthAgo
                    else -> null
                }
                onConfirm(before)
            }) {
                Text(stringResource(R.string.confirm_delete_button), color = Color.White)
            }
        },
        dismissButton = {
            GlassButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel), color = Color.White)
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

