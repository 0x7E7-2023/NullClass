package com.nullclass.feature.settings.jw

import com.nullclass.feature.settings.R
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nullclass.core.ui.R as CoreR
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
        title = {
            Text(
                if (adapters.size > 1) {
                    stringResource(R.string.settings_jw_install_title_many, adapters.size)
                } else {
                    stringResource(R.string.settings_jw_install_title_one)
                },
            )
        },
        text = {
            Column(
                Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                adapters.forEach { adapter ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            stringResource(
                                R.string.settings_jw_adapter_line,
                                adapter.displayName,
                                adapter.key,
                                adapter.manifest.version,
                            ),
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            stringResource(R.string.settings_jw_adapter_login_url, adapter.manifest.loginUrl),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (adapter.manifest.allowHosts.isNotEmpty()) {
                            Text(
                                stringResource(
                                    R.string.settings_jw_adapter_hosts,
                                    adapter.manifest.allowHosts.joinToString(stringResource(CoreR.string.common_list_separator)),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { viewingScripts = adapter }) {
                            Text(stringResource(R.string.settings_jw_adapter_view_script))
                        }
                    }
                }
                sourceUrl?.let {
                    Text(
                        stringResource(R.string.settings_jw_adapter_source_url, it),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                fileName?.let {
                    Text(
                        stringResource(R.string.settings_jw_adapter_source_file, it),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    stringResource(R.string.settings_jw_adapter_untrusted),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Bold,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !busy) {
                Text(stringResource(R.string.settings_jw_install_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CoreR.string.common_cancel)) }
        },
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
        title = { Text(snapshot.index.name ?: stringResource(R.string.settings_jw_library_dialog_title)) },
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
                    Text(stringResource(R.string.settings_jw_library_empty))
                } else {
                    AdapterSearchField(query = query, onQueryChange = { query = it })
                    if (entries.isEmpty()) {
                        Text(
                            stringResource(R.string.settings_jw_library_no_match, query.trimQuery()),
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
                    stringResource(R.string.settings_jw_library_untrusted),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CoreR.string.common_close)) }
        },
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
                Text(stringResource(R.string.settings_jw_adapter_key, adapter.key))
                Text(stringResource(R.string.settings_jw_adapter_version, adapter.manifest.version))
                Text(
                    stringResource(
                        R.string.settings_jw_adapter_source,
                        stringResource(
                            if (adapter.source == JwAdapterSource.BUILTIN) {
                                R.string.settings_jw_adapter_source_builtin
                            } else {
                                R.string.settings_jw_adapter_source_user
                            },
                        ),
                    ),
                )
                adapter.manifest.author?.let {
                    Text(stringResource(R.string.settings_jw_adapter_author, it))
                }
                adapter.manifest.homepage?.let {
                    Text(stringResource(R.string.settings_jw_adapter_homepage, it))
                }
                Text(stringResource(R.string.settings_jw_adapter_login_url, adapter.manifest.loginUrl))
                if (adapter.manifest.allowHosts.isNotEmpty()) {
                    Text(
                        stringResource(
                            R.string.settings_jw_adapter_hosts,
                            adapter.manifest.allowHosts.joinToString(stringResource(CoreR.string.common_list_separator)),
                        ),
                    )
                }
                adapter.installInfo?.let { info ->
                    info.sourceUrl?.let {
                        Text(stringResource(R.string.settings_jw_adapter_installed_from, it))
                    }
                    info.sha256?.let {
                        Text(stringResource(R.string.settings_jw_adapter_checksum, it.take(16)))
                    }
                }
                TextButton(onClick = { viewingScripts = true }) {
                    Text(stringResource(R.string.settings_jw_adapter_view_script))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CoreR.string.common_close)) }
        },
        dismissButton = {
            if (adapter.source == JwAdapterSource.USER) {
                TextButton(onClick = onDelete) {
                    Text(
                        stringResource(R.string.settings_jw_adapter_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
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
        title = { Text(stringResource(R.string.settings_jw_script_dialog_title, adapter.displayName)) },
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
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CoreR.string.common_close)) }
        },
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
