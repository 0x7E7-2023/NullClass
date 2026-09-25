package com.nullclass.feature.settings.transfer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.view.HapticFeedbackConstants
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
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
import com.nullclass.core.ui.R as CoreR
import com.nullclass.core.ui.i18n.UiText
import com.nullclass.core.ui.i18n.resolve
import com.nullclass.feature.settings.R
import com.nullclass.core.ui.layout.AdaptiveColumn
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
        if (granted) {
            scanning = true
        } else {
            viewModel.onScanFailed(UiText.Res(R.string.settings_transfer_permission_camera))
        }
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
                source = if (adapterName.isNullOrBlank()) {
                    UiText.Res(R.string.settings_transfer_jw_running_generic)
                } else {
                    UiText.Res(R.string.settings_transfer_jw_running, adapterName)
                },
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

    val appBarScrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(appBarScrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_transfer_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(CoreR.string.common_back),
                        )
                    }
                },
                scrollBehavior = appBarScrollBehavior,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        AdaptiveColumn(
            modifier = Modifier.padding(padding),
            scrollState = rememberScrollState(),
            contentPadding = PaddingValues(horizontal = 16.dp),
            imePadding = false,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ---- 教务导入 ----
            Text(
                stringResource(R.string.settings_transfer_jw_section),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                stringResource(R.string.settings_transfer_jw_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = {
                    jwLauncher.launch(com.nullclass.feature.settings.jw.JwImportActivity.intent(context))
                },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.settings_transfer_jw_pick_school)) }
            com.nullclass.feature.settings.jw.JwLibraryUpdateSection()

            SectionFeedback(state, TransferSection.JW)

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // ---- 导出 ----
            Text(
                stringResource(R.string.settings_transfer_backup_section),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                stringResource(R.string.settings_transfer_backup_desc),
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
                                viewModel.onOperationFailed(
                                    TransferSection.BACKUP,
                                    UiText.Res(
                                        R.string.settings_transfer_export_failed,
                                        e.message ?: e.javaClass.simpleName,
                                    ),
                                )
                            }
                        }
                    },
                    enabled = !state.busy,
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.settings_transfer_save_file)) }
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
                                        context.getString(R.string.settings_transfer_share_file),
                                    ),
                                )
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                viewModel.onOperationFailed(
                                    TransferSection.BACKUP,
                                    UiText.Res(
                                        R.string.settings_transfer_share_failed,
                                        e.message ?: e.javaClass.simpleName,
                                    ),
                                )
                            }
                        }
                    },
                    enabled = !state.busy,
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.settings_transfer_share)) }
            }
            OutlinedButton(
                onClick = { viewModel.generateQr() },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.settings_transfer_qr_generate)) }
            Text(
                stringResource(R.string.settings_transfer_qr_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionFeedback(state, TransferSection.BACKUP)

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // ---- 日历导出 ----
            Text(
                stringResource(R.string.settings_transfer_ics_section),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                stringResource(R.string.settings_transfer_ics_desc) +
                    stringResource(R.string.settings_transfer_ics_once),
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
                            viewModel.onOperationFailed(
                                TransferSection.CALENDAR,
                                UiText.Res(
                                    R.string.settings_transfer_ics_failed_to_open,
                                    e.message ?: e.javaClass.simpleName,
                                ),
                            )
                        }
                    }
                },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.settings_transfer_ics_export)) }

            SectionFeedback(state, TransferSection.CALENDAR)

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // ---- 导入 ----
            Text(
                stringResource(R.string.settings_transfer_import_section),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            OutlinedButton(onClick = {
                openFileLauncher.launch(arrayOf("application/json", "application/x-nullclass", "*/*"))
            }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_transfer_import_file))
            }
            SectionFeedback(state, TransferSection.FILE)
            OutlinedButton(onClick = {
                viewModel.beginScan()
                when (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)) {
                    PackageManager.PERMISSION_GRANTED -> scanning = true
                    else -> cameraPermission.launch(Manifest.permission.CAMERA)
                }
            }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_transfer_import_qr))
            }
            OutlinedButton(
                onClick = {
                    viewModel.beginScan()
                    openQrImageLauncher.launch("image/*")
                },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.settings_transfer_import_qr_album)) }
            SectionFeedback(state, TransferSection.QR)

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // ---- WakeUp ----
            Text(
                stringResource(R.string.settings_transfer_wakeup_section),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                stringResource(R.string.settings_transfer_wakeup_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = {
                openWakeUpLauncher.launch(arrayOf("*/*"))
            }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_transfer_wakeup_pick))
            }
            SectionFeedback(state, TransferSection.WAKEUP)

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // ---- 拾光课程表 ----
            Text(
                stringResource(R.string.settings_transfer_shiguang_section),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                stringResource(R.string.settings_transfer_shiguang_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = {
                openShiguangLauncher.launch(arrayOf("application/json", "*/*"))
            }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_transfer_shiguang_pick))
            }
            SectionFeedback(state, TransferSection.SHIGUANG)

            Text(
                stringResource(R.string.settings_transfer_merge_hint),
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
                Text(
                    stringResource(R.string.settings_transfer_processing),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        state.message?.let { message ->
            Text(
                message.resolve(),
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
        title = { Text(stringResource(R.string.settings_transfer_preview_title)) },
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
                    stringResource(R.string.settings_transfer_preview_source, preview.source.resolve()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                preview.termSummaries.forEach { term ->
                    Text(
                        stringResource(
                            R.string.settings_transfer_preview_term,
                            term.name,
                            term.totalWeeks,
                            term.courseCount,
                            term.blockCount,
                            term.examCount,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (preview.pendingDeletions > 0) {
                    Text(
                        stringResource(
                            R.string.settings_transfer_preview_deletions,
                            preview.pendingDeletions,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold,
                    )
                }
                if (preview.activateTermName != null) {
                    Text(
                        stringResource(R.string.settings_transfer_preview_activate),
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
                        stringResource(R.string.settings_transfer_preview_adapter_notes),
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
                    stringResource(R.string.settings_transfer_preview_merge_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !busy) {
                Text(stringResource(R.string.settings_transfer_preview_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text(stringResource(CoreR.string.common_cancel))
            }
        },
    )
}

/** content URI 的显示名（SAF 与_intent 均可用）。 */
private fun displayName(uri: android.net.Uri): String? = try {
    uri.lastPathSegment?.substringAfterLast('/')
} catch (e: Exception) {
    null
}
