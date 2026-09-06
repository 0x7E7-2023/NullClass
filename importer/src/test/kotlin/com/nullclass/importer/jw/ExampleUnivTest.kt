package com.nullclass.importer.jw

import com.nullclass.importer.jw.adapters.ExampleUniv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ExampleUnivTest {

    private fun fixture(): String =
        javaClass.classLoader!!.getResourceAsStream("jw/example_extracted.json")!!
            .readBytes().toString(Charsets.UTF_8)

    @Test
    fun `fixture 解析 - 产出结构完整的课表文档`() {
        val doc = ExampleUniv.parseExtracted(fixture())
        assertEquals(1, doc.terms.size)
        assertEquals("2026-2027-1 学期课表", doc.terms[0].name)
        assertEquals(4, doc.courses.size)
        assertEquals(4, doc.blocks.size)
        assertEquals(12, doc.periodTimes.size)
        assertEquals(2, doc.formatVersion)
    }

    @Test
    fun `fixture 解析 - 表头行被跳过，字段映射正确`() {
        val doc = ExampleUniv.parseExtracted(fixture())
        val math = doc.courses.first { it.name == "高等数学A(一)" }
        val block = doc.blocks.first { it.courseId == math.id }
        assertEquals(1, block.dayOfWeek)
        assertEquals(1, block.startPeriod) // "1-2" 启发式：取首节，连两节
        assertEquals(2, block.endPeriod)
        assertEquals("教1-101", block.location)
        assertEquals("ALL", block.weekType)
    }

    @Test
    fun `fixture 解析 - 周次范围与单周标记`() {
        val doc = ExampleUniv.parseExtracted(fixture())
        val english = doc.blocks.first { it.location == "外语楼B202" }
        assertEquals("ODD", english.weekType) // "1-16周(单)"
        val ds = doc.blocks.first { it.location == "实验楼404" }
        assertEquals(2, ds.startWeek) // "2-16周"
        assertEquals(16, ds.endWeek)
        val marx = doc.blocks.first { it.location == "文科楼A305" }
        assertEquals(1, marx.startWeek)
        assertEquals(8, marx.endWeek)
    }

    @Test
    fun `空表格 - 抛可读错误`() {
        assertFailsWith<IllegalArgumentException> {
            ExampleUniv.parseExtracted("""{"url":"x","title":"t","rows":[]}""")
        }
        assertFailsWith<IllegalArgumentException> {
            ExampleUniv.parseExtracted("""{"url":"x"}""")
        }
    }

    @Test
    fun `注册表 - 能按 key 取到适配器`() {
        assertEquals(ExampleUniv, JwAdapterRegistry.byKey("example-univ"))
        assertEquals(null, JwAdapterRegistry.byKey("not-exist"))
        assertTrue(JwAdapterRegistry.adapters.isNotEmpty())
        assertTrue(JwAdapterRegistry.ADAPTER_REQUEST_URL.contains("issues/new"))
    }

    @Test
    fun `提取脚本是 ES5 风格自执行函数`() {
        // 不含箭头函数/let/const/模板串——保守兼容旧 WebView
        val s = ExampleUniv.extractScript
        assertTrue(s.startsWith("(function()"))
        assertTrue("!function".let { true })
        listOf("=>", "let ", "const ", "`").forEach {
            assertTrue(it !in s, "extractScript 不应包含 $it（ES5 兼容）")
        }
    }
}
