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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.*
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
                importMessage = "正在读取文件..."

                val tempFile = copyUriToFile(context, uri)
                if (tempFile != null) {
                    importMessage = "正在导入数据..."
                    val result = dataManager.importData(tempFile.absolutePath)

                    isImporting = false
                    if (result.success) {
                        successMessage = """
                            导入成功！

                            围栏: ${result.importedFences} 个
                            事件: ${result.importedEvents} 条
                            停留: ${result.importedStays} 条

                            数据已成功导入，现在可以正常使用。
                        """.trimIndent()
                        showSuccessDialog = true
                        refreshData()
                    } else {
                        successMessage = "导入失败: ${result.message}"
                        showSuccessDialog = true
                    }
                } else {
                    isImporting = false
                    successMessage = "无法读取文件"
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
                "数据导入导出",
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

                // 导出与导入
                ExportImportSection(
                    onExportAll = {
                        scope.launch {
                            isExporting = true
                            exportMessage = "准备导出..."
                            val result = dataManager.exportData(
                                options = DataExportOptions(exportAll = true),
                                onProgress = { progress, msg ->
                                    exportMessage = msg
                                }
                            )
                            isExporting = false
                            if (result.success) {
                                successMessage = """
                                    导出成功！

                                    围栏: ${result.exportedFences} 个
                                    事件: ${result.exportedEvents} 条
                                    停留: ${result.exportedStays} 条
                                    每日统计: ${result.exportedDailyStays} 条

                                    文件位置: ${result.filePath}
                                """.trimIndent()
                            } else {
                                successMessage = "导出失败: ${result.message}"
                            }
                            showSuccessDialog = true
                            refreshData()
                        }
                    },
                    onShareFences = {
                        // 分享围栏模板：不含ID，导入时自动分配新UUID
                        val fences = app.geoFences.toList()
                        if (fences.isEmpty()) {
                            successMessage = "暂无围栏可分享"
                            showSuccessDialog = true
                            return@ExportImportSection
                        }
                        scope.launch {
                            isExporting = true
                            exportMessage = "生成围栏模板..."
                            try {
                                val jsonContent = app.exportFencesToJson(fences)
                                if (jsonContent != null) {
                                    val fileName = "TrackMaster_围栏_${SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())}.json"
                                    val file = File(context.cacheDir, fileName)
                                    file.writeText(jsonContent)
                                    val uri = androidx.core.content.FileProvider.getUriForFile(
                                        context, "${context.packageName}.fileprovider", file
                                    )
                                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "application/json"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        putExtra(Intent.EXTRA_SUBJECT, "TrackMaster 围栏分享")
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(Intent.createChooser(shareIntent, "分享围栏").apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    })
                                    successMessage = "已生成围栏分享文件，共 ${fences.size} 个围栏"
                                } else {
                                    successMessage = "生成围栏文件失败"
                                }
                            } catch (e: Exception) {
                                successMessage = "分享失败: ${e.message}"
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

                // 日志自动清理
                AutoCleanupSection(
                    autoCleanupDays = autoCleanupDays,
                    onOpenSettings = { showAutoCleanupDialog = true },
                    onCleanupNow = {
                        scope.launch {
                            val deleted = dataManager.deleteEventsBefore(
                                Date(System.currentTimeMillis() - autoCleanupDays * 24 * 60 * 60 * 1000L)
                            )
                            successMessage = "已清理 $deleted 条旧日志"
                            showSuccessDialog = true
                            refreshData()
                        }
                    }
                )

                HorizontalDivider(color = Color(0x30FFFFFF))

                // 删除历史数据（保留围栏和统计）
                DeleteSection(
                    onDeleteAll = { showDeleteDialog = true }
                )

                HorizontalDivider(color = Color(0x30FFFFFF))

                // 已导出文件
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
                            importMessage = "正在导入..."
                            val result = dataManager.importData(file.absolutePath)
                            isImporting = false
                            if (result.success) {
                                // 【关键修复】模板导入（仅围栏）后立即刷新引擎和内存数据，无需重启
                                if (result.importedFences > 0) {
                                    app.importFencesAndRefresh(file.readText(), preserveId = false)
                                }
                                successMessage = """
                                    导入成功！

                                    围栏: ${result.importedFences} 个
                                    事件: ${result.importedEvents} 条
                                    停留: ${result.importedStays} 条
                                """.trimIndent()
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
                Text("关闭", color = Color.White)
            }
        }
    )

    // 删除确认对话框
    if (showDeleteDialog) {
        DeleteOptionsDialog(
            onDismiss = { showDeleteDialog = false },
            onConfirm = { beforeDate ->
                scope.launch {
                    val deletedCount = dataManager.deleteData(beforeDate)
                    showDeleteDialog = false
                    successMessage = "删除成功！共删除 $deletedCount 条记录"
                    showSuccessDialog = true
                    refreshData()
                }
            }
        )
    }

    // 自动清理设置对话框
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

    // 成功/失败提示
    if (showSuccessDialog) {
        GlassDialog(
            onDismissRequest = { showSuccessDialog = false },
            title = { Text("操作完成", color = NeonCoral) },
            text = {
                Text(
                    successMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFE0E0E0)
                )
            },
            confirmButton = {
                GlassButton(onClick = { showSuccessDialog = false }) {
                    Text("确定", color = Color.White)
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
                "存储状态",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = NeonCoral
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                StorageStatItem(label = "围栏数量", value = storageInfo.fenceCount.toString())
                StorageStatItem(label = "事件记录", value = storageInfo.eventCount.toString())
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                StorageStatItem(label = "停留记录", value = storageInfo.stayCount.toString())
                StorageStatItem(label = "数据库大小", value = storageInfo.formattedSize)
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
            "导出与导入",
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
                    Text("分享围栏", color = NeonAmber)
                }
                GlassButton(
                    onClick = onExportAll,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("导出全部", color = Color.White)
                }
                GlassButton(
                    onClick = onImportFile,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp), tint = Color.White)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("导入", color = Color.White)
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
            "日志自动清理",
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
                    "自动清理",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFE0E0E0)
                )
                Text(
                    "保留最近 $autoCleanupDays 天",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFBDBDBD)
                )
            }
            TextButton(onClick = onOpenSettings) {
                Text("设置", color = NeonCoral, fontSize = 13.sp)
            }
        }

        GlassButton(
            onClick = onCleanupNow,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(imageVector = Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp), tint = Danger)
            Spacer(modifier = Modifier.width(4.dp))
            Text("立即清理旧日志", color = Danger, fontSize = 13.sp)
        }

        Text(
            "日志为进出围栏事件记录，清理后围栏和统计数据不受影响",
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
        title = { Text("日志自动清理", color = NeonCoral) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("自动清理超过以下天数的日志事件：", style = MaterialTheme.typography.bodyMedium, color = Color(0xFFE0E0E0))
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
                        Text("$days 天", color = Color(0xFFE0E0E0))
                    }
                }
            }
        },
        confirmButton = {
            GlassButton(onClick = { onConfirm(selected) }) {
                Text("保存", color = Color.White)
            }
        },
        dismissButton = {
            GlassButton(onClick = onDismiss) {
                Text("取消", color = Color.White)
            }
        }
    )
}

@Composable
private fun DeleteSection(onDeleteAll: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "删除历史数据",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = NeonCoral
        )
        Text(
            "删除事件和停留记录（保留围栏和每日统计）",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFFBDBDBD)
        )
        GlassButton(
            onClick = onDeleteAll,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(imageVector = Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp), tint = Danger)
            Spacer(modifier = Modifier.width(4.dp))
            Text("删除历史数据", color = Danger)
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
            "已导出文件（点击文件名导入）",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = NeonCoral
        )
        if (files.isEmpty()) {
            Text("暂无导出文件", style = MaterialTheme.typography.bodySmall, color = Color(0xFFBDBDBD))
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
                    text = "${dateFormat.format(Date(file.lastModified()))} · ${file.length() / 1024} KB",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFBDBDBD)
                )
                Text(text = "点击导入", style = MaterialTheme.typography.labelSmall, color = NeonCoral)
            }
            IconButton(onClick = onDelete) {
                Icon(imageVector = Icons.Default.Delete, contentDescription = "删除", tint = Danger)
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
        title = { Text("删除选项", color = NeonCoral) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("警告：删除操作不可恢复！", color = Danger, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = deleteAll, onCheckedChange = { deleteAll = it; if (it) deleteBefore = false }, colors = CheckboxDefaults.colors(checkedColor = NeonCoral))
                    Text("删除全部历史数据", color = Color(0xFFE0E0E0))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = deleteBefore, onCheckedChange = { deleteBefore = it; if (it) deleteAll = false }, colors = CheckboxDefaults.colors(checkedColor = NeonCoral))
                    Text("删除1个月前的数据", color = Color(0xFFE0E0E0))
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
                Text("确认删除", color = Color.White)
            }
        },
        dismissButton = {
            GlassButton(onClick = onDismiss) {
                Text("取消", color = Color.White)
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
