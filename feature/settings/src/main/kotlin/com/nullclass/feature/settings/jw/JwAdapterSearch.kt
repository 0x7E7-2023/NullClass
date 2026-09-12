package com.nullclass.feature.settings.jw

import com.nullclass.importer.jw.JwAdapter
import com.nullclass.importer.jw.JwBrokenAdapter
import com.nullclass.importer.jw.JwLibraryEntry

/**
 * 适配器列表的关键词匹配。
 *
 * 查询按空白与常见标点切成若干词，**每个词都得出现**才算命中（`大连 工程` 与 `大连工程` 同效），
 * 大小写不敏感（`USTC` / `ustc` 都行）。
 *
 * 「切完一个词都不剩」= 不过滤，列表照常全列出来 —— 免得手滑敲了个空格就得到一张空列表。
 * UI 那边判断「在不在搜索态」必须用同一个判据 [hasQueryTerms]，否则会出现「进了搜索态却一条没筛」
 * 的中间状态（界面收起了说明文案，列表却还是全量）。
 */
internal fun matchesQuery(haystack: String, query: String): Boolean {
    val tokens = queryTokens(query)
    if (tokens.isEmpty()) return true
    val text = haystack.lowercase()
    return tokens.all(text::contains)
}

/**
 * 查询里有没有真正要搜的词（空查询、只有空白与分隔符时为 false）。
 *
 * 空白不止 ASCII 那几种：全角空格 `　` 与**不换行空格 ` `**（从网页、聊天窗口复制学校名
 * 常会带上它）都得当分隔符，否则粘来的查询会假报「没找到」。
 */
internal fun hasQueryTerms(query: String): Boolean = queryTokens(query).isNotEmpty()

private fun queryTokens(query: String): List<String> =
    query.lowercase().split(QUERY_SEPARATOR).filter(String::isNotEmpty)

/** `\p{Z}` 覆盖各种 Unicode 空格（U+00A0 不换行空格、U+2007 数字空格、U+202F 窄不换行空格、U+3000 全角空格）。 */
private val QUERY_SEPARATOR = Regex("[\\s\\p{Z}，,、；;/]+")

/** 展示给用户看的查询串：把看不见的首尾空白（含 `String.trim()` 不处理的 U+00A0）去掉。 */
internal fun String.trimQuery(): String = trim { it.isWhitespace() }

/**
 * 参与匹配的文本。刻意**包含 key 与教务域名**：用户常常记得住 `jw.dlutci.edu.cn` 或 `ustc`
 * 这样的串，却未必记得住适配器里写的学校全称（本校的全称还挂着「原大连理工大学城市学院」这种旧名）。
 *
 * 不含 homepage：内置适配器的 homepage 都是同一个仓库地址，带上它会让「github」这类词命中
 * 全部适配器，等于没过滤。
 */
internal fun adapterSearchText(adapter: JwAdapter): String = buildString {
    append(adapter.displayName).append('\n')
    append(adapter.key).append('\n')
    append(adapter.manifest.loginUrl).append('\n')
    append(adapter.manifest.scheduleUrlHint.orEmpty()).append('\n')
    append(adapter.manifest.allowHosts.joinToString(" ")).append('\n')
    append(adapter.manifest.author.orEmpty())
}

internal fun JwAdapter.matchesQuery(query: String): Boolean = matchesQuery(adapterSearchText(this), query)

/** 损坏的适配器没有清单可读，只剩 key 与出错原因可搜。 */
internal fun JwBrokenAdapter.matchesQuery(query: String): Boolean = matchesQuery("$key\n$reason", query)

/** 库索引里的候选只有名字/key/版本。 */
internal fun JwLibraryEntry.matchesQuery(query: String): Boolean =
    matchesQuery("$name\n$key\n${version.orEmpty()}", query)
