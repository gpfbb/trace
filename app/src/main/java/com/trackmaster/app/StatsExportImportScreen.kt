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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.trackmaster.app.ui.theme.*
import kotlinx.coroutines.launch
import java.io.File
import com.trackmaster.app.ui.design.currentPalette

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

    // Pre-computed string templates (stringResource cannot be called inside lambdas)
    val importSuccessTemplate = stringResource(R.string.import_success)
    val importFailureTemplate = stringResource(R.string.import_failure)
    val exportSuccessTemplate = stringResource(R.string.export_success)
    val exportFailureTemplate = stringResource(R.string.export_failure)
    val shareSubject = stringResource(R.string.share_stay_records)
    val shareText = stringResource(R.string.export_file_description)
    val shareDialogTitle = stringResource(R.string.share)

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

                        resultMessage = String.format(
                            importSuccessTemplate,
                            0, // fencesCount not available in stats import
                            result.importedEvents,
                            result.importedStays
                        )
                    } else {
                        resultMessage = importFailureTemplate.format(result.message)
                    }
                } catch (e: Exception) {
                    resultMessage = importFailureTemplate.format(e.message ?: "Unknown error")
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
                        putExtra(Intent.EXTRA_SUBJECT, shareSubject)
                        putExtra(Intent.EXTRA_TEXT, shareText)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }

                    context.startActivity(Intent.createChooser(shareIntent, shareDialogTitle))

                    resultMessage = String.format(
                                exportSuccessTemplate,
                                0, // fencesCount not available in stats export
                                result.eventsCount,
                                result.staysCount,
                                result.dailyStaysCount,
                                result.fileName ?: ""
                            )
                } else {
                    resultMessage = exportFailureTemplate.format(result.message)
                }
            } catch (e: Exception) {
                resultMessage = exportFailureTemplate.format(e.message ?: "Unknown error")
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
                    stringResource(R.string.stay_record_import_export),
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
                        containerColor = currentPalette.groupedCell
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            stringResource(R.string.current_data_statistics),
                            fontWeight = FontWeight.Bold,
                            color = NeonCoral
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(stringResource(R.string.stay_record), fontSize = 12.sp, color = currentPalette.labelSecondary)
                                Text(stringResource(R.string.stay_record_count, storageInfo.stayCount), fontWeight = FontWeight.Bold, color = Color.White)
                            }
                            Column {
                                Text(stringResource(R.string.daily_summary), fontSize = 12.sp, color = currentPalette.labelSecondary)
                                Text(
                                    stringResource(R.string.daily_summary_count,
                                        try { dataManager.getAllDailyStayRecords().size } catch (e: Exception) { 0 }),
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                            Column {
                                Text(stringResource(R.string.event_log), fontSize = 12.sp, color = currentPalette.labelSecondary)
                                Text(stringResource(R.string.event_log_count, storageInfo.eventCount), fontWeight = FontWeight.Bold, color = Color.White)
                            }
                        }
                    }
                }

                HorizontalDivider(color = currentPalette.separator)

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
                            Text(stringResource(R.string.about_data_export), fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color.White)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.about_export_desc),
                            fontSize = 11.sp,
                            color = currentPalette.labelSecondary
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
                        Text(stringResource(R.string.exporting))
                    } else {
                        Icon(Icons.Default.Upload, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.export_stay_records))
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
                        Text(stringResource(R.string.importing))
                    } else {
                        Icon(Icons.Default.Download, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.import_stay_records))
                    }
                }

                // 合并说明
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = currentPalette.groupedCell
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
                            Text(stringResource(R.string.merge_rules), fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color.White)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(                            stringResource(R.string.merge_rules_desc),
                            color = currentPalette.labelSecondary
                        )
                    }
                }
            }
        },
        confirmButton = {
            GlassButton(onClick = onDismiss) {
                Text(stringResource(R.string.close), color = Color.White)
            }
        }
    )

    // 结果对话框
    if (showResultDialog) {
        AlertDialog(
            onDismissRequest = { showResultDialog = false },
            title = {
                Text(
                    if (importResult?.success == true || exportResult?.success == true) stringResource(R.string.operation_success) else stringResource(R.string.operation_failed),
                    color = if (importResult?.success == true || exportResult?.success == true) Success else Danger
                )
            },
            text = { Text(resultMessage, color = Color.White) },
            confirmButton = {
                Button(
                    onClick = { showResultDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = NeonCoral)
                ) {
                    Text(stringResource(R.string.ok))
                }
            }
        )
    }
}
