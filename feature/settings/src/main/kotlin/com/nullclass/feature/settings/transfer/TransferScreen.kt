package com.nullclass.feature.settings.transfer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.view.HapticFeedbackConstants
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.core.content.ContextCompat
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
    val hostView = LocalView.current
    var resumeScannerOnCancel by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }

    // ---- SAF / 扫码 launchers ----
    val saveFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        uri?.let { viewModel.writeDocumentTo(uri) }
    }

    val saveIcsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/calendar"),
    ) { uri ->
        uri?.let { viewModel.writeIcsTo(uri) }
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

    val openShiguangLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let { viewModel.importShiguangFromUri(it, displayName(uri)) }
    }

    val openQrImageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) viewModel.importQrFromUri(uri)
        else if (resumeScannerOnCancel) scanning = true
        resumeScannerOnCancel = false
    }

    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) scanning = true else viewModel.onScanFailed("需要相机权限才能扫码")
    }

    val jwLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        val json = data?.getStringExtra(com.nullclass.feature.settings.jw.JwImportActivity.EXTRA_DOCUMENT_JSON)
        if (json != null) {
            val notes = data.getStringArrayListExtra(
                com.nullclass.feature.settings.jw.JwImportActivity.EXTRA_DOCUMENT_NOTES,
            ).orEmpty()
            val adapterName = data.getStringExtra(
                com.nullclass.feature.settings.jw.JwImportActivity.EXTRA_ADAPTER_NAME,
            )
            viewModel.parseExtractedDocument(
                json,
                source = if (adapterName.isNullOrBlank()) "教务导入" else "教务导入 · $adapterName",
                adapterNotes = notes,
            )
        }
    }

    // 系统「用其他应用打开」.nullclass → 待导入 URI
    val pendingUri = pendingImport?.value
    LaunchedEffect(pendingUri) {
        pendingUri?.let { uri ->
            viewModel.importFromUri(uri, displayName(uri))
            onPendingImportConsumed()
        }
    }

    LaunchedEffect(state.preview) {
        if (state.preview?.section == TransferSection.QR) {
            hostView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
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
                            try {
                                saveFileLauncher.launch(viewModel.suggestedFileName())
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                viewModel.onOperationFailed(TransferSection.BACKUP, "无法导出：${e.message}")
                            }
                        }
                    },
                    enabled = !state.busy,
                    modifier = Modifier.weight(1f),
                ) { Text("保存到文件") }
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            try {
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
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                viewModel.onOperationFailed(TransferSection.BACKUP, "分享失败：${e.message}")
                            }
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
            Text(
                "只含当前学期的有效课程，扫码后并入对方当前课表并切过去；完整备份请用上面的文件。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionFeedback(state, TransferSection.BACKUP)

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // ---- 日历导出 ----
            Text("日历导出", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "将当前学期的课程和考试导出为标准 .ics 文件，可用手机日历、Google 日历等打开。" +
                    "这是一次性导出，课程或考试修改后需要重新导出。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = {
                    scope.launch {
                        try {
                            saveIcsLauncher.launch(viewModel.suggestedIcsFileName())
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            viewModel.onOperationFailed(TransferSection.CALENDAR, "无法导出日历：${e.message}")
                        }
                    }
                },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("导出当前学期 .ics") }

            SectionFeedback(state, TransferSection.CALENDAR)

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // ---- 导入 ----
            Text("导入", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            OutlinedButton(onClick = {
                openFileLauncher.launch(arrayOf("application/json", "application/x-nullclass", "*/*"))
            }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("从 .nullclass 文件导入") }
            SectionFeedback(state, TransferSection.FILE)
            OutlinedButton(onClick = {
                viewModel.beginScan()
                when (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)) {
                    PackageManager.PERMISSION_GRANTED -> scanning = true
                    else -> cameraPermission.launch(Manifest.permission.CAMERA)
                }
            }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("扫码导入") }
            OutlinedButton(
                onClick = {
                    viewModel.beginScan()
                    openQrImageLauncher.launch("image/*")
                },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("从相册识别二维码") }
            SectionFeedback(state, TransferSection.QR)

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
            }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("选择 .wakeup_schedule 文件") }
            SectionFeedback(state, TransferSection.WAKEUP)

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // ---- 拾光课程表 ----
            Text("从拾光课程表迁移", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "在拾光课程表里「我的 → 高级功能 → 课表导入/导出」导出课程文件，" +
                    "选择那个 shiguangschedule_*.json 即可迁移（课程、单双周、作息表与开学日期都会带过来）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = {
                openShiguangLauncher.launch(arrayOf("application/json", "*/*"))
            }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("选择拾光导出的 .json 文件") }
            SectionFeedback(state, TransferSection.SHIGUANG)

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
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("选择学校并登录提取") }

            SectionFeedback(state, TransferSection.JW)
            Text(
                "导入采用合并语义：同一记录以修改时间新者胜，不会覆盖更新的本地数据。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 24.dp),
            )
        }
    }

    if (scanning) {
        Dialog(
            onDismissRequest = { scanning = false },
            properties = DialogProperties(
                dismissOnBackPress = true,
                dismissOnClickOutside = false,
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
        ) {
            QrScanScreen(
                onPayload = { payload ->
                    scanning = false
                    viewModel.parseQrPayload(payload)
                },
                onCancel = { scanning = false },
                onPickImage = {
                    scanning = false
                    resumeScannerOnCancel = true
                    openQrImageLauncher.launch("image/*")
                },
                onError = { message ->
                    scanning = false
                    viewModel.onScanFailed(message)
                },
            )
        }
    }

    // ---- 二维码弹层 ----
    state.qrPayload?.let { payload ->
        QrShareDialog(payload = payload, onDismiss = viewModel::dismissQr)
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
private fun SectionFeedback(state: TransferUiState, section: TransferSection) {
    if (state.section != section) return
    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(state.message) {
        if (state.message != null) requester.bringIntoView()
    }
    Column(Modifier.bringIntoViewRequester(requester).semantics { liveRegion = LiveRegionMode.Polite }) {
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
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("导入预览") },
        text = {
            // 弹窗高度封顶 + 可滚动：这些条目里有**第三方脚本**提供的文本，
            // 条数上限（20 条 ×200 字）是给校验用的，不代表屏幕上放得下。
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "来源：${preview.source}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                preview.termSummaries.forEach { term ->
                    Text(
                        "学期「${term.name}」· ${term.totalWeeks} 周 · ${term.courseCount} 门课 · " +
                            "${term.blockCount} 条安排 · ${term.examCount} 场考试",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (preview.pendingDeletions > 0) {
                    Text(
                        "⚠ 注意：该文件会对本地 $preview.pendingDeletions 条现有记录产生删除效果" +
                            "（合并按修改时间裁决，删除会传播）。请确认文件来源可信！",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold,
                    )
                }
                if (preview.activateTermName != null) {
                    Text(
                        "导入后设为当前学期，今日 / 课表 / 小组件立即切到它。",
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
                if (preview.adapterNotes.isNotEmpty()) {
                    // 来源必须写明白：这些字是适配器脚本写的，脚本可以逐字抄我们上面那句，
                    // 也可以编一句「不会删除任何本地记录」—— 用户得知道该信谁。
                    Text(
                        "以下说明由适配器（第三方脚本）提供，不是空课官方的判断：",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Bold,
                    )
                    preview.adapterNotes.forEach { note ->
                        Text(
                            "· $note",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
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
            TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") }
        },
    )
}

/** content URI 的显示名（SAF 与_intent 均可用）。 */
private fun displayName(uri: android.net.Uri): String? = try {
    uri.lastPathSegment?.substringAfterLast('/')
} catch (e: Exception) {
    null
}
