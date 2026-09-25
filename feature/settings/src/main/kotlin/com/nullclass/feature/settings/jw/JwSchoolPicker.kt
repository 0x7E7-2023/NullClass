package com.nullclass.feature.settings.jw

import android.content.Intent
import android.icu.text.AlphabeticIndex
import android.icu.text.Collator
import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.nullclass.core.ui.R as CoreR
import com.nullclass.feature.settings.R
import com.nullclass.core.ui.layout.LocalWindowSize
import com.nullclass.importer.jw.JwAdapter
import com.nullclass.importer.jw.JwBrokenAdapter
import kotlinx.coroutines.launch
import java.util.Locale

/** 「提交我的学校适配」issue 入口。 */
private const val ADAPTER_REQUEST_URL =
    "https://github.com/0x7E7-2023/NullClass-adapters/issues/new?template=jw-adapter-request.md"

/** 分页。名称与命中数都在渲染时按当前语言取，枚举本身不持有文字。 */
private enum class PickerTab(@StringRes val labelRes: Int) {
    SCHOOLS(R.string.settings_jw_tab_schools),
    GENERIC(R.string.settings_jw_tab_generic),
    MINE(R.string.settings_jw_tab_mine),
}

/**
 * 学校选择：顶部搜索 + 三个分页（学校 / 通用适配 / 我添加的）。
 *
 * 「学校」页按拼音首字母分组，右侧字母条可点可拖；上次用过的适配器置顶成「一键刷新」卡片。
 * 搜索跨分页生效，分页标题上带命中数，免得结果藏在没点开的那一页里。
 */
@Composable
internal fun SchoolPicker(
    state: JwUiState,
    onBack: () -> Unit,
    onPick: (JwAdapter) -> Unit,
    onRefresh: (JwAdapter) -> Unit,
    onImportZip: () -> Unit,
    onLoadLibrary: (String) -> Unit,
    onShowDetails: (JwAdapter) -> Unit,
    onDelete: (JwAdapter) -> Unit,
    modifier: Modifier = Modifier,
) {
    var linkDialog by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var tab by rememberSaveable { mutableStateOf(PickerTab.SCHOOLS) }

    // 与匹配函数同一套判据：只有分隔符的那种查询不算搜索态
    val searching = hasQueryTerms(query)
    val schools = state.builtin.filter { !it.isFallback && it.matchesQuery(query) }
    val userAdapters = state.user.filter { it.matchesQuery(query) }
    val brokenAdapters = state.broken.filter { it.matchesQuery(query) }
    val lastAdapter = state.lastAdapterKey?.let { key ->
        state.builtin.firstOrNull { it.key == key } ?: state.user.firstOrNull { it.key == key }
    }

    Column(modifier.fillMaxSize()) {
        PickerSearchBar(query = query, onQueryChange = { query = it }, onBack = onBack)
        PrimaryTabRow(selectedTabIndex = tab.ordinal, containerColor = MaterialTheme.colorScheme.background) {
            PickerTab.entries.forEach { t ->
                val count = when (t) {
                    PickerTab.SCHOOLS -> schools.size
                    PickerTab.MINE -> userAdapters.size + brokenAdapters.size
                    PickerTab.GENERIC -> null // 通用适配不认学校，不参与搜索
                }
                Tab(
                    selected = tab == t,
                    onClick = { tab = t },
                    text = {
                        val label = stringResource(t.labelRes)
                        Text(if (searching && count != null) "$label $count" else label)
                    },
                )
            }
        }
        Box(Modifier.weight(1f)) {
            when (tab) {
                PickerTab.SCHOOLS -> SchoolsTab(
                    schools = schools,
                    recent = lastAdapter.takeIf { !searching },
                    noHitsQuery = query.trimQuery().takeIf { searching && schools.isEmpty() },
                    mineHits = if (searching) userAdapters.size + brokenAdapters.size else 0,
                    fallbacks = state.builtin.filter { it.isFallback },
                    onPick = onPick,
                    onRefresh = onRefresh,
                    onDetails = onShowDetails,
                    onGoTab = { tab = it },
                )
                PickerTab.GENERIC -> GenericTab(
                    fallbacks = state.builtin.filter { it.isFallback },
                    onPick = onPick,
                    onDetails = onShowDetails,
                )
                PickerTab.MINE -> MineTab(
                    userAdapters = userAdapters,
                    brokenAdapters = brokenAdapters,
                    empty = state.user.isEmpty() && state.broken.isEmpty(),
                    onPick = onPick,
                    onDetails = onShowDetails,
                    onDelete = onDelete,
                    onImportZip = onImportZip,
                    onAddLink = { linkDialog = true },
                )
            }
        }
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SchoolsTab(
    schools: List<JwAdapter>,
    recent: JwAdapter?,
    noHitsQuery: String?,
    mineHits: Int,
    fallbacks: List<JwAdapter>,
    onPick: (JwAdapter) -> Unit,
    onRefresh: (JwAdapter) -> Unit,
    onDetails: (JwAdapter) -> Unit,
    onGoTab: (PickerTab) -> Unit,
) {
    val groups = remember(schools) { groupByInitial(schools) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // 每个字母小标题在 LazyColumn 里的位置，给右侧字母条跳转用；必须和下面 item 的顺序一一对应
    val headerPositions = remember(groups, recent) {
        var i = if (recent != null) 2 else 0
        groups.map { (_, list) -> i.also { i += 1 + list.size } }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(start = 16.dp, end = 36.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (recent != null) {
                item(key = "recent-label") { GroupLabel(stringResource(R.string.settings_jw_recent_used)) }
                item(key = "recent") { RecentCard(recent, onRefresh = onRefresh) }
            }
            groups.forEach { (label, list) ->
                stickyHeader(key = "h-$label") {
                    Text(
                        label,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.background)
                            .padding(top = 8.dp, bottom = 4.dp),
                    )
                }
                items(list, key = { "b-${it.key}" }) { AdapterRow(it, badge = null, onPick = onPick, onDetails = onDetails) }
            }
            if (noHitsQuery != null) {
                item(key = "no-hits") {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 16.dp)) {
                        Text(
                            stringResource(R.string.settings_jw_no_school_match, noHitsQuery),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (mineHits > 0) {
                            TextButton(onClick = { onGoTab(PickerTab.MINE) }) {
                                Text(stringResource(R.string.settings_jw_mine_hits, mineHits))
                            }
                        }
                        Text(
                            stringResource(R.string.settings_jw_try_generic),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                // 搜不到学校时通用适配器直接端上来 —— 「搜不到我的学校」正是它存在的理由
                items(fallbacks, key = { "f-${it.key}" }) { adapter ->
                    AdapterRow(
                        adapter,
                        badge = stringResource(R.string.settings_jw_badge_generic),
                        onPick = onPick,
                        onDetails = onDetails,
                    )
                }
            } else {
                item(key = "footer") {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                        Text(
                            stringResource(R.string.settings_jw_no_your_school),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = { onGoTab(PickerTab.GENERIC) }) {
                            Text(stringResource(R.string.settings_jw_use_generic))
                        }
                    }
                }
            }
        }

        // 矮屏（手机横屏、分屏）放不下整条字母条，放了反而点不准：收起来，靠滚动和搜索
        if (groups.size > 1 && !LocalWindowSize.current.isCompactHeight) {
            LetterIndexBar(
                letters = groups.map { it.first },
                onSelect = { i -> scope.launch { listState.scrollToItem(headerPositions[i]) } },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 4.dp),
            )
        }
    }
}

@Composable
private fun GenericTab(
    fallbacks: List<JwAdapter>,
    onPick: (JwAdapter) -> Unit,
    onDetails: (JwAdapter) -> Unit,
) {
    val context = LocalContext.current
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            Text(
                stringResource(R.string.settings_jw_generic_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(fallbacks, key = { "f-${it.key}" }) { AdapterRow(it, badge = null, onPick = onPick, onDetails = onDetails) }
        item {
            TextButton(onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ADAPTER_REQUEST_URL)))
            }) { Text(stringResource(R.string.settings_jw_request_adapter)) }
        }
    }
}

@Composable
private fun MineTab(
    userAdapters: List<JwAdapter>,
    brokenAdapters: List<JwBrokenAdapter>,
    empty: Boolean,
    onPick: (JwAdapter) -> Unit,
    onDetails: (JwAdapter) -> Unit,
    onDelete: (JwAdapter) -> Unit,
    onImportZip: () -> Unit,
    onAddLink: () -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onImportZip, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_jw_import_zip))
                }
                OutlinedButton(onClick = onAddLink, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_jw_add_from_link))
                }
            }
        }
        if (empty) {
            item {
                Text(
                    stringResource(R.string.settings_jw_mine_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(userAdapters, key = { "u-${it.key}" }) { AdapterRow(it, badge = null, onPick = onPick, onDetails = onDetails) }
        items(brokenAdapters, key = { "x-${it.key}" }) { broken ->
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("⚠ ${broken.key}", style = MaterialTheme.typography.bodyLarge)
                        Text(broken.reason, style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { onDelete(JwAdapterPlaceholder.of(broken.key)) }) {
                        Text(stringResource(R.string.settings_jw_adapter_delete))
                    }
                }
            }
        }
    }
}

/**
 * 顶部圆角搜索栏，兼作返回。学校名、key、教务域名、作者都参与匹配（见 [matchesQuery]）。
 */
@Composable
private fun PickerSearchBar(query: String, onQueryChange: (String) -> Unit, onBack: () -> Unit) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .height(56.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(CoreR.string.common_back),
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) {
                            Text(
                                stringResource(R.string.settings_jw_search_hint),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        inner()
                    }
                },
            )
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        Icons.Default.Clear,
                        contentDescription = stringResource(R.string.settings_jw_search_clear),
                    )
                }
            } else {
                Icon(
                    Icons.Default.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
        }
    }
}

/** 上次成功导入的适配器：点一下复用登录状态重新提取。 */
@Composable
private fun RecentCard(adapter: JwAdapter, onRefresh: (JwAdapter) -> Unit) {
    Surface(
        onClick = { onRefresh(adapter) },
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(16.dp)) {
            Icon(Icons.Default.Refresh, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(adapter.displayName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                Text(
                    stringResource(R.string.settings_jw_recent_refresh_hint),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun GroupLabel(text: String) {
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
    Surface(
        onClick = { onPick(adapter) },
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(36.dp)
                    .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
            ) {
                Text(
                    adapter.displayName.take(1),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(adapter.displayName, style = MaterialTheme.typography.bodyLarge)
                val insecure = adapter.manifest.loginUrl.startsWith("http://")
                val subtitle = listOfNotNull(
                    badge,
                    stringResource(R.string.settings_jw_insecure).takeIf { insecure },
                ).joinToString(stringResource(CoreR.string.common_separator))
                if (subtitle.isNotEmpty()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (insecure) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = { onDetails(adapter) }) {
                Icon(
                    Icons.Outlined.Info,
                    contentDescription = stringResource(R.string.settings_jw_details),
                )
            }
        }
    }
}

/** 右侧字母条：点或拖到哪个字母就跳到哪一组。 */
@Composable
private fun LetterIndexBar(letters: List<String>, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val select by rememberUpdatedState(onSelect)
    var height by remember { mutableIntStateOf(0) }
    var active by remember { mutableStateOf<Int?>(null) }
    fun hit(y: Float) {
        if (height == 0) return
        val i = (y / height * letters.size).toInt().coerceIn(0, letters.lastIndex)
        if (i != active) {
            active = i
            select(i)
        }
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .widthIn(min = 28.dp)
            .onSizeChanged { height = it.height }
            .pointerInput(letters) {
                detectVerticalDragGestures(
                    onDragStart = { hit(it.y) },
                    onDragEnd = { active = null },
                    onDragCancel = { active = null },
                ) { change, _ -> hit(change.position.y) }
            }
            .pointerInput(letters) {
                detectTapGestures {
                    hit(it.y)
                    active = null
                }
            },
    ) {
        letters.forEachIndexed { i, letter ->
            Text(
                letter,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = if (i == active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 2.dp),
            )
        }
    }
}

/**
 * 按首字母分组，组内按拼音排序。
 *
 * 首字母优先用清单里手填的 `initial`（官方库全都填了）；没填的（第三方适配器）才交给系统 ICU 的
 * 中文索引推断，不另带拼音库。
 */
private fun groupByInitial(adapters: List<JwAdapter>): List<Pair<String, List<JwAdapter>>> {
    val index = AlphabeticIndex<JwAdapter>(Locale.SIMPLIFIED_CHINESE).buildImmutableIndex()
    val collator = Collator.getInstance(Locale.SIMPLIFIED_CHINESE)
    return adapters
        .groupBy { it.manifest.initial ?: index.getBucket(index.getBucketIndex(sortName(it.displayName))).label }
        // A-Z 在前，ICU 的溢出桶（非字母开头的名字）垫底
        .toSortedMap(compareBy<String>({ it.length != 1 || it[0] !in 'A'..'Z' }, { it }))
        .map { (label, list) -> label to list.sortedWith(compareBy(collator) { sortName(it.displayName) }) }
}

/**
 * 地名里的多音字 → 同音且只有一个读音的字，只用来排序和推断首字母，不影响显示。
 * 这些全是**教材名词条**（解析用），不是界面文案，故整表标注 i18n-exempt。
 * ICU 对多音字只认最常用的读音（「长」= zhǎng），没填 `initial` 的适配器靠这张表兜底。
 * 学校名的多音字几乎都出在地名上；发现哪个学校分错了组，往这里加一行即可。
 * 已用 ICU 58（Android 8）与 77 对照拾光课程表适配库的 221 所学校核过：加上这张表后全部分对。
 * 「重庆」ICU 自己就认得 chóng，不用收。
 */
private val PLACE_NAME_READINGS = mapOf( // i18n-exempt: 解析用词条
    "长春" to "常春", // cháng，否则按 zhǎng 落进 Z // i18n-exempt: 解析用词条
    "长沙" to "常沙", // i18n-exempt: 解析用词条
    "长江" to "常江", // i18n-exempt: 解析用词条
    "长治" to "常治", // i18n-exempt: 解析用词条
    "长安" to "常安", // i18n-exempt: 解析用词条
    "厦门" to "夏门", // xià，否则按 shà 落进 S // i18n-exempt: 解析用词条
    "番禺" to "潘禺", // pān，否则按 fān 落进 F // i18n-exempt: 解析用词条
)

private fun sortName(name: String): String =
    PLACE_NAME_READINGS.entries.fold(name) { acc, (from, to) -> acc.replace(from, to) }
