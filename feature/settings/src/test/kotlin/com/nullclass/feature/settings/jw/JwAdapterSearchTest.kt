package com.nullclass.feature.settings.jw

import com.nullclass.importer.jw.JwAdapter
import com.nullclass.importer.jw.JwAdapterSource
import com.nullclass.importer.jw.JwBrokenAdapter
import com.nullclass.importer.jw.JwLibraryEntry
import com.nullclass.importer.jw.JwManifest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 适配器列表搜索的匹配规则（纯函数，不需要设备）。 */
class JwAdapterSearchTest {

    private fun adapter(
        key: String,
        name: String,
        loginUrl: String = "",
        allowHosts: List<String> = emptyList(),
        author: String? = null,
        homepage: String? = null,
    ) = JwAdapter(
        manifest = JwManifest(
            key = key,
            name = name,
            version = "1.0.0",
            loginUrl = loginUrl,
            author = author,
            homepage = homepage,
            allowHosts = allowHosts,
        ),
        source = JwAdapterSource.BUILTIN,
        extractScript = "",
        parseScript = null,
        files = emptyMap(),
    )

    private val dlutci = adapter(
        key = "dlutci",
        name = "大连工程学院（原大连理工大学城市学院）",
        loginUrl = "https://jw.dlutci.edu.cn/",
        author = "NullClass",
        homepage = "https://github.com/0x7E-2023/NullClass",
    )
    private val ustc = adapter(
        key = "ustc",
        name = "中国科学技术大学",
        loginUrl = "https://passport.ustc.edu.cn/login",
        allowHosts = listOf("*.ustc.edu.cn"),
    )

    // 各种「看不见但会被粘进来」的空白：不换行空格、数字空格、窄不换行空格、全角空格
    private val NBSP = "\u00A0"
    private val FIGURE_SPACE = "\u2007"
    private val NARROW_NBSP = "\u202F"
    private val IDEOGRAPHIC_SPACE = "\u3000"

    @Test
    fun `空查询不过滤`() {
        assertTrue(dlutci.matchesQuery(""))
        assertTrue(dlutci.matchesQuery("   "))
        assertTrue(dlutci.matchesQuery(IDEOGRAPHIC_SPACE))
    }

    @Test
    fun `学校名按子串命中`() {
        assertTrue(dlutci.matchesQuery("大连工程"))
        assertTrue(dlutci.matchesQuery("大连工程学院"))
        assertTrue(ustc.matchesQuery("科学技术"))
        assertFalse(dlutci.matchesQuery("清华"))
    }

    @Test
    fun `旧名后缀也搜得到`() {
        // 适配器名里挂着「原大连理工大学城市学院」，学生报的常常是旧名
        assertTrue(dlutci.matchesQuery("城市学院"))
        assertTrue(dlutci.matchesQuery("大连理工"))
    }

    @Test
    fun `key 与教务域名参与匹配`() {
        // 学生记得住域名/缩写，未必记得住学校全称
        assertTrue(dlutci.matchesQuery("dlutci"))
        assertTrue(dlutci.matchesQuery("jw.dlutci.edu.cn"))
        assertTrue(dlutci.matchesQuery("https://jw.dlutci.edu.cn/"))
        assertTrue(ustc.matchesQuery("ustc.edu.cn"))
        assertTrue(ustc.matchesQuery("passport"))
        assertTrue(ustc.matchesQuery("*.ustc.edu.cn"))
    }

    @Test
    fun `大小写不敏感`() {
        assertTrue(ustc.matchesQuery("USTC"))
        assertTrue(ustc.matchesQuery("Ustc.Edu.Cn"))
        assertTrue(dlutci.matchesQuery("DLUTCI"))
    }

    @Test
    fun `多个词要全部命中`() {
        assertTrue(dlutci.matchesQuery("大连 工程"))
        assertTrue(dlutci.matchesQuery("dlutci 大连"))
        assertTrue(dlutci.matchesQuery("大连，工程"))  // 中文逗号当分隔符
        assertTrue(dlutci.matchesQuery("大连、dlutci"))
        assertFalse(dlutci.matchesQuery("大连 清华"))
        assertFalse(ustc.matchesQuery("ustc 大连"))
    }

    @Test
    fun `author 参与匹配 但 homepage 不参与`() {
        // 内置适配器的 homepage 都是同一个仓库地址，带上它会让「github」命中全部适配器
        assertTrue(dlutci.matchesQuery("NullClass"))
        assertTrue(dlutci.matchesQuery("nullclass"))
        assertFalse(dlutci.matchesQuery("github"))
        assertFalse(ustc.matchesQuery("github"))
    }

    @Test
    fun `通用适配器按名字与 key 都能找到`() {
        val universal = adapter(key = "universal", name = "通用适配器")
        assertTrue(universal.matchesQuery("通用"))
        assertTrue(universal.matchesQuery("universal"))
        assertTrue(universal.matchesQuery("univ"))
        // 它没有 loginUrl，不该被任意域名查询命中
        assertFalse(universal.matchesQuery("dlutci.edu.cn"))
    }

    @Test
    fun `损坏的适配器按 key 与原因匹配`() {
        val broken = JwBrokenAdapter(key = "broken-one", reason = "manifest.json 不是合法的适配器清单")
        assertTrue(broken.matchesQuery("broken"))
        assertTrue(broken.matchesQuery("manifest"))
        assertFalse(broken.matchesQuery("ustc"))
    }

    @Test
    fun `库索引条目按名字与 key 匹配`() {
        val entry = JwLibraryEntry(key = "zust", name = "浙江科技大学", version = "1.2.0", path = "adapters/zust")
        assertTrue(entry.matchesQuery("浙江"))
        assertTrue(entry.matchesQuery("zust"))
        assertTrue(entry.matchesQuery("1.2.0"))
        assertFalse(entry.matchesQuery("ustc"))
    }

    @Test
    fun `查询里混着无关字符不会误命中`() {
        assertFalse(dlutci.matchesQuery("清华 北大"))
        assertTrue(dlutci.matchesQuery(" 大连 "))  // 前后空白会被切掉
    }

    @Test
    fun `不换行空格也算分隔符`() {
        // 从网页 / 聊天窗口复制学校名，常把 U+00A0 一起带过来。它不在 Java 正则的 \s 里，
        // 早先按「枚举 ASCII 空白」切词时，粘来的查询会整串当成一个词、假报「没找到」。
        assertTrue(dlutci.matchesQuery("大连工程学院$NBSP"))
        assertTrue(dlutci.matchesQuery("${NBSP}大连${NBSP}工程$NBSP"))
        assertTrue(ustc.matchesQuery("${NBSP}ustc$NBSP"))
        assertTrue(dlutci.matchesQuery("大连${FIGURE_SPACE}工程"))
        assertTrue(dlutci.matchesQuery("大连${NARROW_NBSP}工程"))
        assertTrue(dlutci.matchesQuery("大连${IDEOGRAPHIC_SPACE}工程"))
        // 切成词之后仍是「全部命中」语义，不是把整串当一个词
        assertFalse(dlutci.matchesQuery("大连${NBSP}清华"))
    }

    @Test
    fun `是否在搜索态与匹配函数同一套判据`() {
        // 纯分隔符的查询不进入搜索态：否则界面收起说明文案、列表却一条没筛
        assertFalse(hasQueryTerms(""))
        assertFalse(hasQueryTerms("   "))
        assertFalse(hasQueryTerms(NBSP))
        assertFalse(hasQueryTerms(IDEOGRAPHIC_SPACE))
        assertFalse(hasQueryTerms("，"))
        assertFalse(hasQueryTerms("、/；;"))
        assertTrue(hasQueryTerms("大连"))
        assertTrue(hasQueryTerms("大连，工程"))
        assertTrue(hasQueryTerms("${NBSP}ustc$NBSP"))
    }

    @Test
    fun `回显给用户的查询串去掉看不见的首尾空白`() {
        // String.trim() 走的是 Java 的实现、只剥 U+0020 及以下，NBSP 会原样留在提示里
        assertEquals("ustc", "${NBSP}ustc$NBSP".trimQuery())
        assertEquals("大连 工程", "  大连 工程  ".trimQuery())
        assertEquals("", NBSP.trimQuery())
    }
}
