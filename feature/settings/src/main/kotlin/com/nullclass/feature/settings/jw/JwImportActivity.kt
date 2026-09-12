package com.nullclass.feature.settings.jw

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
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
                    onFinishWithDocument = { json, notes, adapterName ->
                        setResult(
                            RESULT_OK,
                            Intent()
                                .putExtra(EXTRA_DOCUMENT_JSON, json)
                                // 适配器要用户重点核对的话（开学日是推算的、识别可能错位…）
                                // 一路带到导入预览去，不能在这一跳丢掉
                                .putStringArrayListExtra(EXTRA_DOCUMENT_NOTES, ArrayList(notes))
                                // 名字也带上：预览里那一段第三方说明必须写清是谁说的
                                .putExtra(EXTRA_ADAPTER_NAME, adapterName),
                        )
                        finish()
                    },
                    onCancel = { finish() },
                )
            }
        }
    }

    companion object {
        const val EXTRA_DOCUMENT_JSON = "documentJson"
        const val EXTRA_DOCUMENT_NOTES = "documentNotes"
        const val EXTRA_ADAPTER_NAME = "adapterName"

        fun intent(context: Context): Intent = Intent(context, JwImportActivity::class.java)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun JwImportScreen(
    viewModel: JwImportViewModel,
    onFinishWithDocument: (documentJson: String, notes: List<String>, adapterName: String) -> Unit,
    onCancel: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    var selected by remember { mutableStateOf<JwAdapter?>(null) }
    /** 通用适配器（startUrlPrompt）手输的教务地址。 */
    var startUrl by remember { mutableStateOf<String?>(null) }
    var pendingStartUrl by remember { mutableStateOf<JwAdapter?>(null) }

    val zipLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.importZip(it, it.lastPathSegment?.substringAfterLast('/')) }
    }

    // 系统返回手势对齐顶栏返回键：选中学校后返回 = 回学校列表，而不是把整个
    // 导入 Activity 退掉——手势退出走的是系统跨 Activity 预测动画，和顶栏的
    // 「回退一步」行为不一致，登录到一半误滑一下整个导入流程就没了。
    BackHandler(enabled = selected != null) { selected = null }

    /** 上次成功的课表页地址；只对「上次就是这个适配器」有意义。 */
    fun rememberedUrl(adapter: JwAdapter): String? =
        if (adapter.key == state.lastAdapterKey) state.lastScheduleUrl else null

    /**
     * 选中一个适配器。
     *
     * @param askAddress 列表里点选时为 true：通用适配器**每次都问一次地址**（预填上次的），
     *   否则用户永远换不了学校、也修不了失效的地址——WebView 里没有地址栏可跳转。
     *   「一键刷新」按钮走 false：它本来就是「照上次再来一遍」，有地址就直接进。
     */
    fun pick(adapter: JwAdapter, askAddress: Boolean) {
        if (adapter.promptsForStartUrl && (askAddress || rememberedUrl(adapter) == null)) {
            pendingStartUrl = adapter
        } else {
            // 「照上次再来一遍」：别把上一轮手填的地址带进来
            startUrl = null
            selected = adapter
        }
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
                onPick = { pick(it, askAddress = true) },
                onRefresh = { pick(it, askAddress = false) },
                onImportZip = { zipLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
                onLoadLibrary = viewModel::loadLibrary,
                onShowDetails = viewModel::showDetails,
                onDelete = viewModel::delete,
                modifier = Modifier.padding(padding),
            )
        } else {
            JwWebViewStep(
                adapter = adapter,
                // 通用适配器**不自动提取**：它的课表是页面自己渲染出来的，而页面加载完（onPageFinished）
                // 不等于课表画好了。提取一开始就冻结网络，抢跑会把还在加载页面资源（常见于资源放在
                // CDN 域上的教务系统）的 SPA 掐死在白屏上，用户连补救都无从下手——金智课表实测如此。
                // 它的文案本来写的就是「打开课表页后点提取课表」。
                autoExtract = state.autoExtract && adapter.key == state.lastAdapterKey &&
                    !adapter.promptsForStartUrl,
                preferredUrl = when {
                    // 通用适配器：**这次弹窗里填的地址优先**。那是用户换学校、修失效地址的唯一入口，
                    // 被「上次成功那页」顶掉的话就还是无门（真机实测：填了新地址，进网页的仍是旧地址）。
                    adapter.promptsForStartUrl ->
                        startUrl ?: state.lastScheduleUrl.takeIf { adapter.key == state.lastAdapterKey }
                    adapter.key == state.lastAdapterKey -> state.lastScheduleUrl
                    else -> null
                },
                onExtracted = { documentJson, loadedUrl, notes ->
                    viewModel.rememberRefresh(adapter.key, loadedUrl)
                    onFinishWithDocument(documentJson, notes, adapter.displayName)
                },
                modifier = Modifier.padding(padding),
            )
        }
    }

    pendingStartUrl?.let { adapter ->
        StartUrlDialog(
            initial = startUrl ?: rememberedUrl(adapter).orEmpty(),
            onSubmit = { url ->
                startUrl = url
                selected = adapter
                pendingStartUrl = null
            },
            onDismiss = { pendingStartUrl = null },
        )
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
    onRefresh: (JwAdapter) -> Unit,
    onImportZip: () -> Unit,
    onLoadLibrary: (String) -> Unit,
    onShowDetails: (JwAdapter) -> Unit,
    onDelete: (JwAdapter) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var linkDialog by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val lastAdapter = state.lastAdapterKey?.let { key ->
        state.builtin.firstOrNull { it.key == key } ?: state.user.firstOrNull { it.key == key }
    }

    // 与匹配函数同一套判据：只有分隔符的那种查询不算搜索态，否则会收起说明文案却一条不筛
    val searching = hasQueryTerms(query)
    val fallbackAdapters = state.builtin.filter { it.isFallback }
    val schools = state.builtin.filter { !it.isFallback }.filter { it.matchesQuery(query) }
    val matchedFallbacks = fallbackAdapters.filter { it.matchesQuery(query) }
    val userAdapters = state.user.filter { it.matchesQuery(query) }
    val brokenAdapters = state.broken.filter { it.matchesQuery(query) }
    val hits = schools.size + matchedFallbacks.size + userAdapters.size + brokenAdapters.size
    val noHits = searching && hits == 0
    // 搜不到任何东西时，兜底适配器照样端上来 —— 「搜不到我的学校」正是它存在的理由。
    // 平时它只在命中查询时出现（搜「通用」/「univ」能找到它，其余时候不占地方）。
    val fallbackRows = if (noHits) fallbackAdapters else matchedFallbacks

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("从教务系统导入课表", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        // 搜索时把开场白收起来，结果多留几行
        if (!searching) {
            Text(
                "登录由你在下方网页里手工完成（验证码/扫码/短信都自己操作），空课不会碰你的教务账号密码；" +
                    "登录后进入课表页面，点「提取课表」即可。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        AdapterSearchField(query = query, onQueryChange = { query = it })

        if (lastAdapter != null) {
            Button(onClick = { onRefresh(lastAdapter) }, modifier = Modifier.fillMaxWidth()) {
                Text("一键刷新：${lastAdapter.displayName}")
            }
            if (!searching) {
                Text(
                    "复用上次的登录状态重新提取（学校改了课表时用）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (noHits) {
                item {
                    Text(
                        "没找到匹配「${query.trimQuery()}」的适配器。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (schools.isNotEmpty()) {
                item { SectionLabel("内置适配器") }
                items(schools, key = { "b-${it.key}" }) { adapter ->
                    AdapterRow(adapter, badge = null, onPick = onPick, onDetails = onShowDetails)
                }
            }
            if (!searching) {
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
            } else if (userAdapters.isNotEmpty() || brokenAdapters.isNotEmpty()) {
                item { SectionLabel("用户添加") }
            }
            items(userAdapters, key = { "u-${it.key}" }) { adapter ->
                AdapterRow(adapter, badge = "用户添加", onPick = onPick, onDetails = onShowDetails)
            }
            items(brokenAdapters, key = { "x-${it.key}" }) { broken ->
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
            // 兜底适配器平时置底：找得到学校的人不该被它分散注意力。
            // 搜不到东西时它就是答案，改由上面那行「没找到匹配…」起头，不再重复小标题。
            if (fallbackRows.isNotEmpty()) {
                if (!noHits) item { SectionLabel("找不到你的学校？") }
                item {
                    Text(
                        "通用适配器不认学校：填上你的教务地址，登录后由空课读页面文字自己还原出表格" +
                            "（课表是图片/画布画的则走离线 OCR）。结果会先给你核对，确认后才导入。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(fallbackRows, key = { "f-${it.key}" }) { adapter ->
                    AdapterRow(adapter, badge = "通用", onPick = onPick, onDetails = onShowDetails)
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

/**
 * 适配器搜索框。学校名、key、教务域名、作者都参与匹配 ——
 * `dlutci`、`ustc.edu.cn`、`大连 工程` 都找得到（见 [matchesQuery]）。
 */
@Composable
internal fun AdapterSearchField(query: String, onQueryChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        textStyle = MaterialTheme.typography.bodyMedium,
        // 提示语要短到单行放得下，否则输入框会被撑成两行高
        placeholder = { Text("搜索学校名称或域名") },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Default.Clear, contentDescription = "清空搜索")
                }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
    )
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

/**
 * 通用适配器的入口：先问学校地址（它不认学校，没有可内置的登录页）。
 *
 * 地址会被记成「一键刷新」的入口，所以下次预填在这里，直接「打开」即可；换学校就改掉它。
 */
@Composable
private fun StartUrlDialog(
    initial: String,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var url by remember { mutableStateOf(initial) }
    val normalized = normalizeStartUrl(url)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("输入教务系统网址") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "填学校教务系统的登录页或课表页地址，例如 jw.example.edu.cn。" +
                        "登录、验证码、扫码都在下一页里由你自己完成，空课不碰你的账号密码。",
                    style = MaterialTheme.typography.bodySmall,
                )
                androidx.compose.material3.OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    singleLine = true,
                    label = { Text("教务系统地址") },
                    isError = url.isNotBlank() && normalized == null,
                    supportingText = {
                        Text(
                            if (url.isBlank()) {
                                "不确定？在浏览器里打开学校教务系统，把地址栏整条复制过来"
                            } else if (normalized == null) {
                                "这个地址看不懂，检查一下有没有多余的空格或中文"
                            } else {
                                "将打开：$normalized（只支持 http/https；很多学校只有 http，打不开就换个协议试试）"
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { normalized?.let(onSubmit) }, enabled = normalized != null) { Text("打开") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 补全协议并做基本校验；不合法返回 null。 */
private fun normalizeStartUrl(raw: String): String? {
    val text = raw.trim()
    if (text.isEmpty()) return null
    val withScheme = if (text.startsWith("http://") || text.startsWith("https://")) text else "https://$text"
    val uri = runCatching { java.net.URI(withScheme) }.getOrNull() ?: return null
    if (uri.host.isNullOrBlank()) return null
    if (!uri.scheme.equals("http", true) && !uri.scheme.equals("https", true)) return null
    return withScheme
}

@Composable
private fun LinkDialog(onSubmit: (String) -> Unit, onDismiss: () -> Unit) {    var url by remember { mutableStateOf("") }
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
