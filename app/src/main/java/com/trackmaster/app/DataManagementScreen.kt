package com.trackmaster.app

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun DataManagementScreen(
    onDismiss: () -> Unit,
    dataManager: DataManager
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    
    var storageInfo by remember { mutableStateOf(StorageInfo(0, 0, 0, 0)) }
    var exportFiles by remember { mutableStateOf<List<java.io.File>>(emptyList()) }
    var isExporting by remember { mutableStateOf(false) }
    var isImporting by remember { mutableStateOf(false) }
    var exportProgress by remember { mutableStateOf(0) }
    var exportMessage by remember { mutableStateOf("") }
    var importMessage by remember { mutableStateOf("") }
    var showExportDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showSuccessDialog by remember { mutableStateOf(false) }
    var successMessage by remember { mutableStateOf("") }
    
    fun refreshData() {
        scope.launch {
            storageInfo = dataManager.getStorageInfo()
            exportFiles = dataManager.getExportFiles()
        }
    }
    
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
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
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "数据与日志管理",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
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
                StorageInfoCard(
                    storageInfo = storageInfo
                )
                
                HorizontalDivider()
                
                ExportSection(
                    onExportAll = {
                        showExportDialog = true
                    },
                    onImportFile = {
                        filePickerLauncher.launch("application/json")
                    },
                    isExporting = isExporting,
                    exportProgress = exportProgress,
                    exportMessage = exportMessage,
                    isImporting = isImporting,
                    importMessage = importMessage
                )
                
                HorizontalDivider()
                
                DeleteSection(
                    onDeleteAll = {
                        showDeleteDialog = true
                    }
                )
                
                HorizontalDivider()
                
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
                                successMessage = result.message
                                showSuccessDialog = true
                            }
                        }
                    }
                )
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
    
    if (showExportDialog) {
        ExportOptionsDialog(
            onDismiss = { showExportDialog = false },
            onConfirm = { options ->
                scope.launch {
                    isExporting = true
                    exportProgress = 0
                    exportMessage = "准备导出..."
                    
                    val result = dataManager.exportData(
                        options = options,
                        onProgress = { progress, message ->
                            exportProgress = progress
                            exportMessage = message
                        }
                    )
                    
                    isExporting = false
                    showExportDialog = false
                    
                    if (result.success) {
                        successMessage = """
                            导出成功！
                            
                            围栏: ${result.exportedFences} 个
                            事件: ${result.exportedEvents} 条
                            停留: ${result.exportedStays} 条
                            
                            文件位置: ${result.filePath}
                            
                            您现在可以安全删除原始数据了。
                        """.trimIndent()
                        showSuccessDialog = true
                        refreshData()
                    } else {
                        successMessage = result.message
                        showSuccessDialog = true
                    }
                }
            }
        )
    }
    
    if (showDeleteDialog) {
        DeleteOptionsDialog(
            onDismiss = { showDeleteDialog = false },
            onConfirm = { beforeDate ->
                scope.launch {
                    val deletedCount = dataManager.deleteData(beforeDate)
                    showDeleteDialog = false
                    successMessage = "删除成功！共删除 ${deletedCount} 条记录"
                    showSuccessDialog = true
                    refreshData()
                }
            }
        )
    }
    
    if (showSuccessDialog) {
        AlertDialog(
            onDismissRequest = { showSuccessDialog = false },
            title = { Text("操作完成") },
            text = { 
                Text(
                    successMessage,
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(onClick = { showSuccessDialog = false }) {
                    Text("确定")
                }
            }
        )
    }
}

@Composable
fun StorageInfoCard(storageInfo: StorageInfo) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "存储状态",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                StorageStatItem(
                    label = "围栏数量",
                    value = storageInfo.fenceCount.toString()
                )
                StorageStatItem(
                    label = "事件记录",
                    value = storageInfo.eventCount.toString()
                )
            }
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                StorageStatItem(
                    label = "停留记录",
                    value = storageInfo.stayCount.toString()
                )
                StorageStatItem(
                    label = "数据库大小",
                    value = storageInfo.formattedSize
                )
            }
        }
    }
}

@Composable
fun StorageStatItem(label: String, value: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun ExportSection(
    onExportAll: () -> Unit,
    onImportFile: () -> Unit,
    isExporting: Boolean,
    exportProgress: Int,
    exportMessage: String,
    isImporting: Boolean,
    importMessage: String
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "数据导出与导入",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        
        if (isExporting) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    LinearProgressIndicator(
                        progress = { exportProgress / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = exportMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else if (isImporting) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = importMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onExportAll,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("导出")
                }
                Button(
                    onClick = onImportFile,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("选择文件")
                }
            }
        }
    }
}

fun copyUriToFile(context: Context, uri: Uri): File? {
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

@Composable
fun DeleteSection(
    onDeleteAll: () -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "数据删除",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        
        Button(
            onClick = onDeleteAll,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer
            )
        ) {
            Text("删除历史数据")
        }
    }
}

@Composable
fun ExportedFilesSection(
    files: List<java.io.File>,
    onDeleteFile: (java.io.File) -> Unit,
    onImportFile: (java.io.File) -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "已导出文件（点击文件名导入）",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        
        if (files.isEmpty()) {
            Text(
                "暂无导出文件",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
fun FileItem(
    file: java.io.File,
    onDelete: () -> Unit,
    onImport: () -> Unit
) {
    val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onImport() },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = file.name,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "${dateFormat.format(Date(file.lastModified()))} · ${file.length() / 1024} KB",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "点击导入",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "删除文件",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
fun ExportOptionsDialog(
    onDismiss: () -> Unit,
    onConfirm: (DataExportOptions) -> Unit
) {
    var exportAll by remember { mutableStateOf(true) }
    var useBeforeDate by remember { mutableStateOf(false) }
    var beforeDate by remember { mutableStateOf(Date()) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导出选项") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = exportAll,
                        onCheckedChange = { 
                            exportAll = it
                            if (it) useBeforeDate = false
                        }
                    )
                    Text("导出全部数据")
                }
                
                if (!exportAll) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = useBeforeDate,
                            onCheckedChange = { useBeforeDate = it }
                        )
                        Text("导出指定日期之前的数据")
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val options = DataExportOptions(
                        exportAll = exportAll,
                        beforeDate = if (useBeforeDate) beforeDate else null
                    )
                    onConfirm(options)
                }
            ) {
                Text("开始导出")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
fun DeleteOptionsDialog(
    onDismiss: () -> Unit,
    onConfirm: (Date?) -> Unit
) {
    var deleteAll by remember { mutableStateOf(true) }
    var deleteBefore by remember { mutableStateOf(false) }
    
    val calendar = Calendar.getInstance()
    calendar.add(Calendar.MONTH, -1)
    val oneMonthAgo = calendar.time
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除选项") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    "警告：删除操作不可恢复！",
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Bold
                )
                
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = deleteAll,
                        onCheckedChange = { 
                            deleteAll = it
                            if (it) deleteBefore = false
                        }
                    )
                    Text("删除全部历史数据")
                }
                
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = deleteBefore,
                        onCheckedChange = { 
                            deleteBefore = it
                            if (it) deleteAll = false
                        }
                    )
                    Text("删除1个月前的数据")
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val before = when {
                        deleteAll -> null
                        deleteBefore -> oneMonthAgo
                        else -> null
                    }
                    onConfirm(before)
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                )
            ) {
                Text("确认删除")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
