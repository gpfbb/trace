package com.trackmaster.app

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MergeType
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.trackmaster.app.ui.theme.*
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun StatsExportImportScreen(
    onDismiss: () -> Unit,
    dataManager: DataManager,
    stayDurationManager: StayDurationManager
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var isExporting by remember { mutableStateOf(false) }
    var isImporting by remember { mutableStateOf(false) }
    var exportResult by remember { mutableStateOf<StatsExportResult?>(null) }
    var importResult by remember { mutableStateOf<StatsImportResult?>(null) }
    var showResultDialog by remember { mutableStateOf(false) }
    var resultMessage by remember { mutableStateOf("") }

    // 统计数据
    val storageInfo = remember { dataManager.getStorageInfo() }

    // 文件选择器
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                isImporting = true
                try {
                    // 复制文件到临时位置
                    val tempFile = File(context.cacheDir, "import_stats.json")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        tempFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }

                    val result = dataManager.importStatsFromJson(tempFile.absolutePath)
                    importResult = result

                    if (result.success) {
                        // 刷新 StayDurationManager 缓存
                        stayDurationManager.loadActiveSessionsFromDatabase()

                        resultMessage = """
                            导入成功！

                            停留记录: ${result.importedStays} 条
                            每日汇总: ${result.importedDailyStays} 条
                            事件日志: ${result.importedEvents} 条

                            数据已合并到现有数据中。
                        """.trimIndent()
                    } else {
                        resultMessage = "导入失败: ${result.message}"
                    }
                } catch (e: Exception) {
                    resultMessage = "导入失败: ${e.message}"
                } finally {
                    isImporting = false
                    showResultDialog = true
                }
            }
        }
    }

    fun doExport() {
        scope.launch {
            isExporting = true
            try {
                val result = dataManager.exportStatsToJson()
                exportResult = result

                if (result.success && result.filePath != null) {
                    // 分享文件
                    val file = File(result.filePath)
                    val uri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        file
                    )

                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/json"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_SUBJECT, "TrackMaster 停留记录")
                        putExtra(Intent.EXTRA_TEXT, "TrackMaster 停留记录导出文件，分享后可导入查看")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }

                    context.startActivity(Intent.createChooser(shareIntent, "分享停留记录"))

                    resultMessage = """
                        导出成功！

                        停留记录: ${result.staysCount} 条
                        每日汇总: ${result.dailyStaysCount} 条
                        事件日志: ${result.eventsCount} 条

                        文件已生成并可分享。
                    """.trimIndent()
                } else {
                    resultMessage = "导出失败: ${result.message}"
                }
            } catch (e: Exception) {
                resultMessage = "导出失败: ${e.message}"
            } finally {
                isExporting = false
                showResultDialog = true
            }
        }
    }

    GlassDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Timeline,
                    contentDescription = null,
                    tint = NeonCoral,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    "停留记录导入导出",
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                    color = NeonCoral
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 统计数据卡片
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0x4D000000)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "当前数据统计",
                            fontWeight = FontWeight.Bold,
                            color = NeonCoral
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text("停留记录", fontSize = 12.sp, color = Color(0xFFBDBDBD))
                                Text("${storageInfo.stayCount} 条", fontWeight = FontWeight.Bold, color = Color.White)
                            }
                            Column {
                                Text("每日汇总", fontSize = 12.sp, color = Color(0xFFBDBDBD))
                                Text(
                                    try {
                                        "${dataManager.getAllDailyStayRecords().size} 条"
                                    } catch (e: Exception) { "未知" },
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                            Column {
                                Text("事件日志", fontSize = 12.sp, color = Color(0xFFBDBDBD))
                                Text("${storageInfo.eventCount} 条", fontWeight = FontWeight.Bold, color = Color.White)
                            }
                        }
                    }
                }

                HorizontalDivider(color = Color(0x30FFFFFF))

                // 导出说明
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0x3300FF00)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                tint = Success,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("关于数据导出", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color.White)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "导出的文件包含停留记录、每日汇总和事件日志。可以分享给朋友，他们用同一 App 导入后可以查看你的数据。",
                            fontSize = 11.sp,
                            color = Color(0xFFBDBDBD)
                        )
                    }
                }

                // 导出按钮
                Button(
                    onClick = { doExport() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isExporting && !isImporting,
                    colors = ButtonDefaults.buttonColors(containerColor = NeonCoral)
                ) {
                    if (isExporting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("正在导出...")
                    } else {
                        Icon(Icons.Default.Upload, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("导出停留记录")
                    }
                }

                // 导入按钮
                OutlinedButton(
                    onClick = { filePickerLauncher.launch("application/json") },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isExporting && !isImporting,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = NeonAmber)
                ) {
                    if (isImporting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = NeonAmber,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("正在导入...")
                    } else {
                        Icon(Icons.Default.Download, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("导入停留记录")
                    }
                }

                // 合并说明
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0x33000000)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.MergeType,
                                contentDescription = null,
                                tint = NeonAmber,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("合并规则", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color.White)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "• 停留记录：按记录 ID 去重\n" +
                                    "• 每日汇总：相同围栏相同日期的时长会累加\n" +
                                    "• 事件日志：按事件 ID 去重",
                            fontSize = 11.sp,
                            color = Color(0xFFBDBDBD)
                        )
                    }
                }
            }
        },
        confirmButton = {
            GlassButton(onClick = onDismiss) {
                Text("关闭", color = Color.White)
            }
        }
    )

    // 结果对话框
    if (showResultDialog) {
        AlertDialog(
            onDismissRequest = { showResultDialog = false },
            title = {
                Text(
                    if (importResult?.success == true || exportResult?.success == true) "操作成功" else "操作失败",
                    color = if (importResult?.success == true || exportResult?.success == true) Success else Danger
                )
            },
            text = { Text(resultMessage, color = Color.White) },
            confirmButton = {
                Button(
                    onClick = { showResultDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = NeonCoral)
                ) {
                    Text("确定")
                }
            }
        )
    }
}
