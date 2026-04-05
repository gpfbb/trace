package com.trackmaster.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Add
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
import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import com.trackmaster.app.ui.theme.*

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
    
    GlassDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "数据与日志管理",
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
                StorageInfoCard(
                    storageInfo = storageInfo
                )
                
                HorizontalDivider(color = Color(0x30FFFFFF))
                
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
                
                HorizontalDivider(color = Color(0x30FFFFFF))
                
                DeleteSection(
                    onDeleteAll = {
                        showDeleteDialog = true
                    }
                )
                
                HorizontalDivider(color = Color(0x30FFFFFF))
                
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
            GlassButton(onClick = onDismiss) {
                Text("关闭", color = Color.White)
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
fun StorageInfoCard(storageInfo: StorageInfo) {
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
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    LinearProgressIndicator(
                        progress = { exportProgress / 100f },
                        modifier = Modifier.fillMaxWidth(),
                        color = NeonCoral
                    )
                    Text(
                        text = exportMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFE0E0E0)
                    )
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
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = NeonAmber
                    )
                    Text(
                        text = importMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFE0E0E0)
                    )
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                GlassButton(
                    onClick = onExportAll,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("导出", color = Color.White)
                }
                GlassButton(
                    onClick = onImportFile,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = Color.White
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("选择文件", color = Color.White)
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
            fontWeight = FontWeight.Bold,
            color = NeonCoral
        )
        
        GlassButton(
            onClick = onDeleteAll,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("删除历史数据", color = Color.White)
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
            fontWeight = FontWeight.Bold,
            color = NeonCoral
        )
        
        if (files.isEmpty()) {
            Text(
                "暂无导出文件",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFBDBDBD)
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
            containerColor = Color(0x4D2A221C)
        ),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(0.dp)
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
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFFE0E0E0)
                )
                Text(
                    text = "${dateFormat.format(Date(file.lastModified()))} · ${file.length() / 1024} KB",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFBDBDBD)
                )
                Text(
                    text = "点击导入",
                    style = MaterialTheme.typography.labelSmall,
                    color = NeonCoral
                )
            }
            
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "删除文件",
                    tint = Danger
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
    
    GlassDialog(
        onDismissRequest = onDismiss,
        title = { Text("导出选项", color = NeonCoral) },
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
                        },
                        colors = CheckboxDefaults.colors(checkedColor = NeonCoral)
                    )
                    Text("导出全部数据", color = Color(0xFFE0E0E0))
                }
                
                if (!exportAll) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = useBeforeDate,
                            onCheckedChange = { useBeforeDate = it },
                            colors = CheckboxDefaults.colors(checkedColor = NeonCoral)
                        )
                        Text("导出指定日期之前的数据", color = Color(0xFFE0E0E0))
                    }
                }
            }
        },
        confirmButton = {
            GlassButton(
                onClick = {
                    val options = DataExportOptions(
                        exportAll = exportAll,
                        beforeDate = if (useBeforeDate) beforeDate else null
                    )
                    onConfirm(options)
                }
            ) {
                Text("开始导出", color = Color.White)
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
fun DeleteOptionsDialog(
    onDismiss: () -> Unit,
    onConfirm: (Date?) -> Unit
) {
    var deleteAll by remember { mutableStateOf(true) }
    var deleteBefore by remember { mutableStateOf(false) }
    
    val calendar = Calendar.getInstance()
    calendar.add(Calendar.MONTH, -1)
    val oneMonthAgo = calendar.time
    
    GlassDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除选项", color = NeonCoral) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    "警告：删除操作不可恢复！",
                    color = Danger,
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
                        },
                        colors = CheckboxDefaults.colors(checkedColor = NeonCoral)
                    )
                    Text("删除全部历史数据", color = Color(0xFFE0E0E0))
                }
                
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = deleteBefore,
                        onCheckedChange = { 
                            deleteBefore = it
                            if (it) deleteAll = false
                        },
                        colors = CheckboxDefaults.colors(checkedColor = NeonCoral)
                    )
                    Text("删除1个月前的数据", color = Color(0xFFE0E0E0))
                }
            }
        },
        confirmButton = {
            GlassButton(
                onClick = {
                    val before = when {
                        deleteAll -> null
                        deleteBefore -> oneMonthAgo
                        else -> null
                    }
                    onConfirm(before)
                }
            ) {
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
