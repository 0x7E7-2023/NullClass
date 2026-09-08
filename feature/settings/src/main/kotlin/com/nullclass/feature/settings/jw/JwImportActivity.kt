package com.nullclass.feature.settings.jw

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.nullclass.core.ui.theme.NullClassTheme
import com.nullclass.importer.jw.JwAdapter
import com.nullclass.importer.jw.JwAdapterSource
import com.nullclass.importer.jw.JwManifest
import dagger.hilt.android.AndroidEntryPoint

/** 「提交我的学校适配」issue 入口。 */
private const val ADAPTER_REQUEST_URL =
    "https://github.com/0x7E7-2023/NullClass/issues/new?template=jw-adapter-request.md"

/**
 * 教务导入宿主：选学校 → WebView 手工登录 → 提取 → 回传课表文档 JSON。
 *
 * **凭证红线**：登录全程用户手工完成，本 Activity 不解析、不存储任何账号密码。
 * **第三方适配器**：用户导入的适配器未经审计，安装前必须由用户确认（见 [JwAdapterSheets]）。
 */
@AndroidEntryPoint
class JwImportActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NullClassTheme {
                val viewModel: JwImportViewModel = hiltViewModel()
                JwImportScreen(
                    viewModel = viewModel,
                    onFinishWithDocument = { json ->
                        setResult(RESULT_OK, Intent().putExtra(EXTRA_DOCUMENT_JSON, json))
                        finish()
                    },
                    onCancel = { finish() },
                )
            }
        }
    }

    companion object {
        const val EXTRA_DOCUMENT_JSON = "documentJson"

        fun intent(context: Context): Intent = Intent(context, JwImportActivity::class.java)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun JwImportScreen(
    viewModel: JwImportViewModel,
    onFinishWithDocument: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    var selected by remember { mutableStateOf<JwAdapter?>(null) }

    val zipLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.importZip(it, it.lastPathSegment?.substringAfterLast('/')) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(selected?.displayName ?: "教务导入") },
                navigationIcon = {
                    IconButton(onClick = { if (selected == null) onCancel() else selected = null }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        val adapter = selected
        if (adapter == null) {
            SchoolPicker(
                state = state,
                onPick = { selected = it },
                onImportZip = { zipLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
                onLoadLibrary = viewModel::loadLibrary,
                onShowDetails = viewModel::showDetails,
                onDelete = viewModel::delete,
                modifier = Modifier.padding(padding),
            )
        } else {
            JwWebViewStep(
                adapter = adapter,
                autoExtract = state.autoExtract && adapter.key == state.lastAdapterKey,
                preferredUrl = if (adapter.key == state.lastAdapterKey) state.lastScheduleUrl else null,
                onExtracted = { documentJson, loadedUrl ->
                    viewModel.rememberRefresh(adapter.key, loadedUrl)
                    onFinishWithDocument(documentJson)
                },
                modifier = Modifier.padding(padding),
            )
        }
    }

    if (state.pendingInstall.isNotEmpty()) {
        InstallConfirmDialog(
            adapters = state.pendingInstall,
            sourceUrl = state.pendingSourceUrl,
            fileName = state.pendingFileName,
            busy = state.busy,
            onConfirm = viewModel::confirmInstall,
            onDismiss = viewModel::dismissInstall,
        )
    }

    state.library?.let { snapshot ->
        LibraryDialog(
            snapshot = snapshot,
            busy = state.busy,
            onPick = viewModel::fetchFromLibrary,
            onDismiss = viewModel::dismissLibrary,
        )
    }

    state.viewing?.let { adapter ->
        AdapterDetailsDialog(
            adapter = adapter,
            onDelete = { viewModel.delete(adapter) },
            onDismiss = viewModel::dismissDetails,
        )
    }

    state.message?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::dismissMessage,
            title = { Text(if (state.messageIsError) "出错了" else "完成") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = viewModel::dismissMessage) { Text("好") } },
        )
    }
}

/** 学校选择：内置 / 用户添加 + 导入入口 + 一键刷新。 */
@Composable
private fun SchoolPicker(
    state: JwUiState,
    onPick: (JwAdapter) -> Unit,
    onImportZip: () -> Unit,
    onLoadLibrary: (String) -> Unit,
    onShowDetails: (JwAdapter) -> Unit,
    onDelete: (JwAdapter) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var linkDialog by remember { mutableStateOf(false) }
    val lastAdapter = state.lastAdapterKey?.let { key ->
        state.builtin.firstOrNull { it.key == key } ?: state.user.firstOrNull { it.key == key }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("从教务系统导入课表", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            "登录由你在下方网页里手工完成（验证码/扫码/短信都自己操作），空课不会碰你的教务账号密码；" +
                "登录后进入课表页面，点「提取课表」即可。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (lastAdapter != null) {
            Button(onClick = { onPick(lastAdapter) }, modifier = Modifier.fillMaxWidth()) {
                Text("一键刷新：${lastAdapter.displayName}")
            }
            Text(
                "复用上次的登录状态重新提取（学校改了课表时用）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.builtin.isNotEmpty()) {
                item { SectionLabel("内置适配器") }
                items(state.builtin, key = { "b-${it.key}" }) { adapter ->
                    AdapterRow(adapter, badge = null, onPick = onPick, onDetails = onShowDetails)
                }
            }
            item { SectionLabel("用户添加") }
            if (state.user.isEmpty() && state.broken.isEmpty()) {
                item {
                    Text(
                        "还没有添加过适配器。可以用下面的按钮导入别人做好的适配器包，或直接从适配器库添加。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(state.user, key = { "u-${it.key}" }) { adapter ->
                AdapterRow(adapter, badge = "用户添加", onPick = onPick, onDetails = onShowDetails)
            }
            items(state.broken, key = { "x-${it.key}" }) { broken ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("⚠ ${broken.key}", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            broken.reason,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    TextButton(onClick = { onDelete(JwAdapterPlaceholder.of(broken.key)) }) { Text("删除") }
                }
            }
        }

        HorizontalDivider()
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = onImportZip, modifier = Modifier.weight(1f)) { Text("导入适配器包") }
            OutlinedButton(onClick = { linkDialog = true }, modifier = Modifier.weight(1f)) { Text("从链接添加") }
        }
        TextButton(onClick = {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(ADAPTER_REQUEST_URL)),
            )
        }) { Text("没有我的学校？提交适配请求") }
    }

    if (linkDialog) {
        LinkDialog(
            onSubmit = {
                linkDialog = false
                onLoadLibrary(it)
            },
            onDismiss = { linkDialog = false },
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun AdapterRow(
    adapter: JwAdapter,
    badge: String?,
    onPick: (JwAdapter) -> Unit,
    onDetails: (JwAdapter) -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = { onPick(adapter) }, modifier = Modifier.weight(1f)) {
            Column(Modifier.fillMaxWidth()) {
                Text(adapter.displayName)
                val subtitle = buildString {
                    if (badge != null) append(badge).append(" · ")
                    append("v").append(adapter.manifest.version)
                    if (adapter.manifest.loginUrl.startsWith("http://")) append(" · 不安全连接")
                }
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        TextButton(onClick = { onDetails(adapter) }) { Text("详情") }
    }
}

@Composable
private fun LinkDialog(onSubmit: (String) -> Unit, onDismiss: () -> Unit) {
    var url by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("从适配器库添加") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "粘贴 GitHub 仓库地址或任意 index.json 链接（只允许 https）。" +
                        "适配器由第三方维护，请确认来源可信。",
                    style = MaterialTheme.typography.bodySmall,
                )
                androidx.compose.material3.OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    singleLine = true,
                    label = { Text("库地址") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSubmit(url) }, enabled = url.isNotBlank()) { Text("读取") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 已损坏的用户目录没有 JwAdapter 实体，这里只借它携带 key 用于删除。 */
private object JwAdapterPlaceholder {
    fun of(key: String): JwAdapter = JwAdapter(
        manifest = JwManifest(key = key, name = key, version = "0.0.0", loginUrl = "https://example.invalid/"),
        source = JwAdapterSource.USER,
        extractScript = "",
        parseScript = null,
        files = emptyMap(),
    )
}
