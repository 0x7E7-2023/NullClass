package com.nullclass.importer.jw.ocr

import com.nullclass.importer.jw.JwPayloadCodec
import com.nullclass.importer.jw.JwScheduleNormalizer
import com.nullclass.importer.jw.JwSchedulePayload
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JwBoxesTest {

    private val libraryDir: File = File(
        System.getProperty("jwLibraryDir") ?: error("缺少 jwLibraryDir 系统属性（见 importer/build.gradle.kts）"),
    )

    private fun fixture(name: String): JwSchedulePayload = JwPayloadCodec.decode(
        File(libraryDir, "universal/fixtures/$name").readText(Charsets.UTF_8),
    )

    @Test
    fun `缺省区域尺寸时按文本块外接框推断`() {
        val payload = JwPayloadCodec.decode(
            """{"kind":"boxes","boxes":[{"text":"周一","x":10,"y":20,"w":30,"h":12}]}""",
        )
        val page = JwBoxes.toOcrPage(payload)
        assertEquals(40, page.width)
        assertEquals(32, page.height)
        assertEquals(10, page.boxes.single().left)
        assertEquals(30, page.boxes.single().width)
    }

    @Test
    fun `缺省宽高时不会塌成零宽`() {
        val payload = JwPayloadCodec.decode(
            """{"kind":"boxes","boxes":[{"text":"周一","x":10,"y":20}]}""",
        )
        val box = JwBoxes.toOcrPage(payload).boxes.single()
        assertEquals(1, box.width)
        assertEquals(1, box.height)
    }

    /**
     * 通用适配器的 fixture 走完整条链路：文本框 → 表格结构层 → 课表载荷 → 归一化。
     *
     * 这是「DOM 几何」这条路的端到端回归（真机上只剩 extract.js 量坐标那一段），
     * fixture 由 `jw-adapters/universal/fixtures/generate.py` 生成。
     */
    @Test
    fun `通用适配器的文本框能还原出课表`() {
        val payload = fixture("dom-table.expected.json")
        assertEquals(JwSchedulePayload.KIND_BOXES, payload.kind)

        val table = JwTableAligner.align(JwBoxes.toOcrPage(payload))
        assertTrue(table.reliable, table.warnings.toString())
        assertEquals(7, table.colAnchors.size, "七个星期列")
        assertEquals(5, table.rowAnchors.size, "五个节次行")

        val built = JwOcrScheduleBuilder.build(
            table = table,
            termName = "通用适配器",
            firstDayEpochDay = 20_000,
            totalWeeks = JwOcrScheduleBuilder.inferTotalWeeks(table),
            ocrAssisted = false,
        )
        val term = built.payload.terms.single()
        assertFalse(built.payload.ocrAssisted, "文本块不是 OCR 认出来的")
        assertEquals(22, term.totalWeeks, "表里出现 1-22 周，总周数要跟着抬上去")
        assertEquals(9, term.courses.size)
        assertTrue(built.issues.isEmpty(), built.issues.toString())

        val math = term.courses.first { it.name == "高等数学A(一)" }
        assertEquals("王强", math.teacher)
        val block = math.blocks.single()
        assertEquals(1, block.dayOfWeek)
        assertEquals(1, block.startPeriod)
        assertEquals(2, block.endPeriod)
        assertEquals(1, block.startWeek)
        assertEquals(16, block.endWeek)
        assertEquals("ALL", block.weekType)
        assertEquals("教1-101", block.location)

        // 单双周与教室照常解析
        val programming = term.courses.first { it.name == "程序设计基础" }
        assertEquals("陈静", programming.teacher)
        assertEquals("EVEN", programming.blocks.single().weekType)
        assertEquals("机房302", programming.blocks.single().location)

        // 归一化必须能通过（周次不越界、节次合法）
        val document = JwScheduleNormalizer.normalize(built.payload, schoolKey = "universal", now = 0L)
        assertEquals(9, document.courses.size)
        assertEquals("jw-universal", document.deviceId)
    }

    @Test
    fun `图片课表页的 fixture 交给 OCR 链路`() {
        val payload = fixture("image-schedule.expected.json")
        assertEquals(JwSchedulePayload.KIND_IMAGE, payload.kind)
        assertTrue(payload.ocrAssisted)
        assertEquals("https://jw.example.edu.cn/kb.png", payload.images.single().url)
    }
}
