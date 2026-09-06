package com.nullclass.feature.settings.transfer

import android.content.Intent
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 导入 / 导出中心：导出文件、二维码分享、文件导入、扫码导入、WakeUp 迁移。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransferScreen(
    onBack: () -> Unit,
    onImported: () -> Unit = {},
    pendingImport: androidx.compose.runtime.State<android.net.Uri?>? = null,
    onPendingImportConsumed: () -> Unit = {},
    viewModel: TransferViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingFileName by remember { mutableStateOf<String?>(null) }

    // ---- SAF / 扫码 launchers ----
    val saveFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        uri?.let { viewModel.writeDocumentTo(uri) }
    }

    val openFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let { viewModel.importFromUri(it, displayName(uri)) }
    }

    val openWakeUpLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let { viewModel.importWakeUpFromUri(it, displayName(uri)) }
    }

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { viewModel.parseQrPayload(it) }
    }

    val jwLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val json = result.data?.getStringExtra(com.nullclass.feature.settings.jw.JwImportActivity.EXTRA_DOCUMENT_JSON)
        if (json != null) viewModel.parseExtractedDocument(json, source = "教务导入")
    }

    // 系统「用其他应用打开」.nullclass → 待导入 URI
    val pendingUri = pendingImport?.value
    LaunchedEffect(pendingUri) {
        pendingUri?.let { uri ->
            viewModel.importFromUri(uri, displayName(uri))
            onPendingImportConsumed()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("导入 / 导出") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ---- 导出 ----
            Text("备份与分享", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "导出包含全部学期（含回收站记录），可用于备份、换机，或直接发给同学导入。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            val name = viewModel.suggestedFileName()
                            saveFileLauncher.launch(name)
                        }
                    },
                    enabled = !state.busy,
                    modifier = Modifier.weight(1f),
                ) { Text("保存到文件") }
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            val file = withContext(Dispatchers.IO) { viewModel.buildShareFile() }
                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                            context.startActivity(
                                Intent.createChooser(
                                    Intent(Intent.ACTION_SEND).apply {
                                        type = "application/x-nullclass"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    },
                                    "分享课表文件",
                                ),
                            )
                        }
                    },
                    enabled = !state.busy,
                    modifier = Modifier.weight(1f),
                ) { Text("分享") }
            }
            OutlinedButton(
                onClick = { viewModel.generateQr() },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("生成二维码") }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // ---- 导入 ----
            Text("导入", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            OutlinedButton(onClick = {
                openFileLauncher.launch(arrayOf("application/json", "application/x-nullclass", "*/*"))
            }, modifier = Modifier.fillMaxWidth()) { Text("从 .nullclass 文件导入") }
            OutlinedButton(onClick = {
                scanLauncher.launch(
                    ScanOptions().apply {
                        setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        setPrompt("扫描空课课表二维码")
                        setBeepEnabled(false)
                        setOrientationLocked(true)
                    },
                )
            }, modifier = Modifier.fillMaxWidth()) { Text("扫码导入") }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // ---- WakeUp ----
            Text("从 WakeUp 迁移", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "在 WakeUp 课表里把课表备份为 .wakeup_schedule 文件，选择该文件即可迁移" +
                    "（连堂、单双周、节次时间与颜色都会保留）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = {
                openWakeUpLauncher.launch(arrayOf("*/*"))
            }, modifier = Modifier.fillMaxWidth()) { Text("选择 .wakeup_schedule 文件") }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // ---- 教务导入 ----
            Text("从教务系统导入", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "在网页里自己登录教务系统（空课不碰你的账号密码），打开课表页后一键提取。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = {
                    jwLauncher.launch(com.nullclass.feature.settings.jw.JwImportActivity.intent(context))
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("选择学校并登录提取") }

            // ---- 状态 ----
            if (state.busy) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator()
                    Text("处理中…", style = MaterialTheme.typography.bodyMedium)
                }
            }
            state.message?.let { message ->
                Text(
                    message,
                    color = if (state.messageIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text(
                "导入采用合并语义：同一记录以修改时间新者胜，不会覆盖更新的本地数据。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 24.dp),
            )
        }
    }

    // ---- 二维码弹层 ----
    state.qrPayload?.let { payload ->
        QrShareDialog(payload = payload, viewModel = viewModel, onDismiss = viewModel::dismissQr)
    }

    // ---- 导入预览 ----
    state.preview?.let { preview ->
        ImportPreviewDialog(
            preview = preview,
            busy = state.busy,
            onConfirm = {
                viewModel.confirmMerge()
                onImported()
            },
            onDismiss = viewModel::dismissPreview,
        )
    }
}

@Composable
private fun ImportPreviewDialog(
    preview: ImportPreview,
    busy: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导入预览") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "来源：${preview.source}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                preview.termSummaries.forEach { term ->
                    Text(
                        "学期「${term.name}」· ${term.totalWeeks} 周 · ${term.courseCount} 门课 · ${term.blockCount} 条安排",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (preview.activateTermId != null) {
                    Text(
                        "WakeUp 课表将作为新学期导入并设为当前学期。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                preview.warnings.forEach { warning ->
                    Text(
                        "⚠ $warning",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                Text(
                    "与本地数据合并后生效。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !busy) { Text("合并导入") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** content URI 的显示名（SAF 与_intent 均可用）。 */
private fun displayName(uri: android.net.Uri): String? = try {
    uri.lastPathSegment?.substringAfterLast('/')
} catch (e: Exception) {
    null
}
