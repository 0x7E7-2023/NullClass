package com.nullclass.feature.settings.jw

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nullclass.importer.jw.JwAdapter
import com.nullclass.importer.jw.JwAdapterSource
import com.nullclass.importer.jw.JwLibrarySnapshot

/**
 * 安装确认：**第三方适配器会读到你已登录的教务页面内容**，必须让用户看见并确认。
 *
 * 这里刻意不写「安全」字样：官方合并的适配器会逐个人工审计，用户自己导入的不做任何承诺。
 */
@Composable
fun InstallConfirmDialog(
    adapters: List<JwAdapter>,
    sourceUrl: String?,
    fileName: String?,
    busy: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    var viewingScripts by remember { mutableStateOf<JwAdapter?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (adapters.size > 1) "安装 ${adapters.size} 个适配器？" else "添加这个适配器？") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                adapters.forEach { adapter ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("${adapter.displayName}（${adapter.key} v${adapter.manifest.version}）", fontWeight = FontWeight.Bold)
                        Text(
                            "登录页：${adapter.manifest.loginUrl}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (adapter.manifest.allowHosts.isNotEmpty()) {
                            Text(
                                "会请求的域名：${adapter.manifest.allowHosts.joinToString("、")}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { viewingScripts = adapter }) { Text("查看脚本全文") }
                    }
                }
                sourceUrl?.let {
                    Text("来源：$it", style = MaterialTheme.typography.bodySmall)
                }
                fileName?.let {
                    Text("来源文件：$it", style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    "⚠ 第三方适配器是别人写的代码，提取时会读取你**已登录**的教务页面内容" +
                        "（空课不对它做代码审计）。请确认来源可信，或先查看脚本。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Bold,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !busy) { Text("确认添加") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )

    viewingScripts?.let { adapter ->
        ScriptViewerDialog(adapter = adapter, onDismiss = { viewingScripts = null })
    }
}

/**
 * 库索引候选列表（从链接添加时）。
 *
 * 社区库动辄几十上百个适配器，这里和学校列表共用同一套搜索（[matchesQuery]）。
 */
@Composable
fun LibraryDialog(
    snapshot: JwLibrarySnapshot,
    busy: Boolean,
    onPick: (com.nullclass.importer.jw.JwLibraryEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val entries = snapshot.index.adapters.filter { it.matchesQuery(query) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(snapshot.index.name ?: "适配器库") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    snapshot.url,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (snapshot.index.adapters.isEmpty()) {
                    Text("这个库里还没有适配器。")
                } else {
                    AdapterSearchField(query = query, onQueryChange = { query = it })
                    if (entries.isEmpty()) {
                        Text(
                            "这个库里没有匹配「${query.trimQuery()}」的适配器。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                entries.forEach { entry ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = { onPick(entry) }, enabled = !busy, modifier = Modifier.weight(1f)) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(entry.name)
                                Text(
                                    "${entry.key}${entry.version?.let { " · v$it" } ?: ""}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                Text(
                    "库里的适配器同样由第三方维护，空课不做审计。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

/** 适配器详情：来源、脚本、删除。 */
@Composable
fun AdapterDetailsDialog(
    adapter: JwAdapter,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var viewingScripts by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(adapter.displayName) },
        text = {
            Column(
                Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("key：${adapter.key}")
                Text("版本：v${adapter.manifest.version}")
                Text("来源：${if (adapter.source == JwAdapterSource.BUILTIN) "内置（随应用发布）" else "用户添加"}")
                adapter.manifest.author?.let { Text("作者：$it") }
                adapter.manifest.homepage?.let { Text("主页：$it") }
                Text("登录页：${adapter.manifest.loginUrl}")
                if (adapter.manifest.allowHosts.isNotEmpty()) {
                    Text("会请求的域名：${adapter.manifest.allowHosts.joinToString("、")}")
                }
                adapter.installInfo?.let { info ->
                    info.sourceUrl?.let { Text("安装来源：$it") }
                    info.sha256?.let { Text("内容校验：${it.take(16)}…") }
                }
                TextButton(onClick = { viewingScripts = true }) { Text("查看脚本全文") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        dismissButton = {
            if (adapter.source == JwAdapterSource.USER) {
                TextButton(onClick = onDelete) { Text("删除", color = MaterialTheme.colorScheme.error) }
            }
        },
    )

    if (viewingScripts) {
        ScriptViewerDialog(adapter = adapter, onDismiss = { viewingScripts = false })
    }
}

/** 脚本全文（只读）。让用户能自己看一眼，而不是只靠我们一句「可信」。 */
@Composable
private fun ScriptViewerDialog(adapter: JwAdapter, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${adapter.displayName} 的脚本") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ScriptBlock("extract.js", adapter.extractScript)
                adapter.parseScript?.let { ScriptBlock("parse.js", it) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun ScriptBlock(name: String, source: String) {
    Text(name, style = MaterialTheme.typography.labelLarge)
    Text(
        source,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
    )
}
