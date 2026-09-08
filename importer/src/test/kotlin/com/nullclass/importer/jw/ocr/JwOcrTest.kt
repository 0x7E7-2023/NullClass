package com.nullclass.importer.jw.ocr

import com.nullclass.importer.jw.JwScheduleNormalizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JwOcrTest {

    // ---- 文本解析 ----

    @Test
    fun `周次解析覆盖常见写法`() {
        assertEquals(JwCourseTextParser.WeekSpec(1, 16, "ALL"), JwCourseTextParser.parseWeeks("1-16周"))
        assertEquals(JwCourseTextParser.WeekSpec(1, 16, "ODD"), JwCourseTextParser.parseWeeks("1-16周(单)"))
        assertEquals(JwCourseTextParser.WeekSpec(2, 16, "EVEN"), JwCourseTextParser.parseWeeks("2-16双周"))
        assertEquals(JwCourseTextParser.WeekSpec(3, 3, "ALL"), JwCourseTextParser.parseWeeks("第3周"))
        assertEquals(JwCourseTextParser.WeekSpec(1, 16, "ALL"), JwCourseTextParser.parseWeeks("1-16周", totalWeeks = 20))
        assertNull(JwCourseTextParser.parseWeeks(""))
        assertNull(JwCourseTextParser.parseWeeks("单周"))
        // 教室号不能当周次：「教1-101」里的 1-10、「教3-201」里的 3-20 都是合法周次形状
        assertNull(JwCourseTextParser.parseWeeks("教1-101"))
        assertNull(JwCourseTextParser.parseWeeks("教3-201"))
        assertNull(JwCourseTextParser.parseWeeks("教4-102"))
        // 超出学期总周数 → 不猜，返回 null
        assertNull(JwCourseTextParser.parseWeeks("1-30周", totalWeeks = 16))
    }

    @Test
    fun `节次解析`() {
        assertEquals(1 to 2, JwCourseTextParser.parsePeriods("1-2"))
        assertEquals(8 to 8, JwCourseTextParser.parsePeriods("8"))
        assertEquals(3 to 4, JwCourseTextParser.parsePeriods("第3-4节"))
        assertNull(JwCourseTextParser.parsePeriods(""))
        assertNull(JwCourseTextParser.parsePeriods("99"))
    }

    @Test
    fun `星期与教室识别`() {
        assertEquals(1, JwCourseTextParser.parseDayOfWeek("周一"))
        assertEquals(3, JwCourseTextParser.parseDayOfWeek("星期三"))
        assertEquals(7, JwCourseTextParser.parseDayOfWeek("SUN"))
        assertNull(JwCourseTextParser.parseDayOfWeek("节次"))
        assertTrue(JwCourseTextParser.looksLikeLocation("教1-101"))
        assertTrue(JwCourseTextParser.looksLikeLocation("实验楼404"))
        assertTrue(JwCourseTextParser.looksLikeLocation("计算中心A"))
        assertFalse(JwCourseTextParser.looksLikeLocation("高等数学"))
        assertTrue(JwCourseTextParser.looksLikeTeacher("张三"))
        assertFalse(JwCourseTextParser.looksLikeTeacher("高等数学A(一)"))
    }

    // ---- 网格对齐 ----

    /** 构造一张 7 列 × 3 行的合成课表：表头 + 节次列（垂直居中）+ 若干课程。 */
    private fun syntheticPage(): OcrPage {
        val boxes = mutableListOf<OcrBox>()
        val days = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
        days.forEachIndexed { index, label ->
            boxes += OcrBox(label, left = 120 + index * 100, top = 10, right = 170 + index * 100, bottom = 40)
        }
        // 节次号在行内垂直居中（真实课表的样子）
        listOf(1, 2, 3, 4).forEachIndexed { index, period ->
            val center = 110 + index * 100
            boxes += OcrBox("$period", left = 20, top = center - 15, right = 60, bottom = center + 15)
        }
        boxes += OcrBox("高等数学", left = 130, top = 70, right = 230, bottom = 100)
        boxes += OcrBox("1-16周", left = 130, top = 100, right = 230, bottom = 130)
        boxes += OcrBox("教1-101", left = 130, top = 130, right = 230, bottom = 160)
        boxes += OcrBox("大学英语", left = 330, top = 170, right = 430, bottom = 200)
        boxes += OcrBox("2-16周", left = 330, top = 200, right = 430, bottom = 230)
        return OcrPage(width = 800, height = 400, boxes = boxes)
    }

    @Test
    fun `规则课表能对齐并判定可靠`() {
        val table = JwTableAligner.align(syntheticPage())
        assertTrue(table.reliable, table.warnings.toString())
        assertEquals(7, table.colDays.size)
        assertEquals(listOf(1, 2, 3, 4), table.rowPeriods)
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7), table.colDays)
        assertTrue(table.cells[0][0].contains("高等数学"))
        assertTrue(table.cells[0][0].contains("1-16周"))
        assertTrue(table.cells[1][2].contains("大学英语"))
    }

    @Test
    fun `没有星期表头时判定不可靠`() {
        val page = OcrPage(
            width = 800,
            height = 400,
            boxes = listOf(OcrBox("高等数学", 10, 10, 100, 40), OcrBox("1-16周", 10, 50, 100, 80)),
        )
        val table = JwTableAligner.align(page)
        assertFalse(table.reliable)
        assertTrue(table.warnings.any { it.contains("表头") }, table.warnings.toString())
    }

    @Test
    fun `没有节次列时判定不可靠`() {
        val boxes = listOf(
            OcrBox("周一", 100, 10, 150, 40),
            OcrBox("周二", 200, 10, 250, 40),
            OcrBox("周三", 300, 10, 350, 40),
            OcrBox("周四", 400, 10, 450, 40),
            OcrBox("周五", 500, 10, 550, 40),
            OcrBox("高等数学", 110, 60, 200, 90),
        )
        val table = JwTableAligner.align(OcrPage(800, 400, boxes))
        assertFalse(table.reliable)
        assertTrue(table.warnings.any { it.contains("节次") }, table.warnings.toString())
    }

    @Test
    fun `空图片判定不可靠`() {
        assertFalse(JwTableAligner.align(OcrPage(100, 100, emptyList())).reliable)
    }

    // ---- 网格 → 载荷 ----

    @Test
    fun `对齐结果能生成课表载荷`() {
        val table = JwTableAligner.align(syntheticPage())
        val result = JwOcrScheduleBuilder.build(table, termName = "2026 秋", firstDayEpochDay = 20000, totalWeeks = 20)

        val term = result.payload.terms.single()
        assertEquals("2026 秋", term.name)
        assertEquals(20, term.totalWeeks)
        assertTrue(result.payload.ocrAssisted)

        val math = term.courses.first { it.name == "高等数学" }
        val block = math.blocks.single()
        assertEquals(1, block.dayOfWeek)
        assertEquals(1, block.startPeriod)
        assertEquals(1, block.startWeek)
        assertEquals(16, block.endWeek)
        assertEquals("教1-101", block.location)

        val english = term.courses.first { it.name == "大学英语" }
        assertEquals(3, english.blocks.single().dayOfWeek)
        assertEquals(2, english.blocks.single().startWeek)

        // 载荷必须能通过应用校验
        JwScheduleNormalizer.normalize(result.payload, "ocr", now = 0L)
    }

    @Test
    fun `合并节次标注 3-4 也能作为行锚点 不丢行`() {
        val boxes = mutableListOf<OcrBox>()
        listOf("周一", "周二", "周三", "周四", "周五").forEachIndexed { index, label ->
            boxes += OcrBox(label, 120 + index * 100, 10, 170 + index * 100, 40)
        }
        listOf("1", "2", "3-4", "5").forEachIndexed { index, label ->
            val center = 110 + index * 100
            boxes += OcrBox(label, 20, center - 15, 80, center + 15)
        }
        boxes += OcrBox("高等数学", 130, 270, 230, 300) // 落在 3-4 行

        val table = JwTableAligner.align(OcrPage(800, 600, boxes))

        assertEquals(listOf(1, 2, 3, 5), table.rowPeriods)
        assertEquals(listOf(1, 2, 4, 5), table.rowEndPeriods)
        assertTrue(table.reliable, table.warnings.toString())
        assertTrue(table.cells[2][0].contains("高等数学"), table.cells.toString())

        val result = JwOcrScheduleBuilder.build(table, "T", 20000, 20)
        val block = result.payload.terms.single().courses.single().blocks.single()
        assertEquals(3, block.startPeriod)
        assertEquals(4, block.endPeriod)
    }

    @Test
    fun `周次文本不会被当成节次标注`() {
        assertNull(JwCourseTextParser.parsePeriodLabel("1-16周"))
        assertNull(JwCourseTextParser.parsePeriodLabel("1-16周(单)"))
        assertEquals(3..4, JwCourseTextParser.parsePeriodLabel("3-4"))
        assertEquals(5..5, JwCourseTextParser.parsePeriodLabel("第5节"))
    }

    @Test
    fun `教室号排在周次之前时 周次与教室都要正确`() {
        // 真实教务格子常见顺序是「课名 / 教师 / 教室 / 周次」——
        // 教室号在周次之前，早期实现会把 "教1-101" 读成 1-10 周并丢掉教室
        val boxes = mutableListOf<OcrBox>()
        listOf("周一", "周二", "周三", "周四", "周五").forEachIndexed { index, label ->
            boxes += OcrBox(label, 120 + index * 100, 10, 170 + index * 100, 40)
        }
        listOf(1, 2, 3, 4).forEachIndexed { index, period ->
            val center = 110 + index * 200
            boxes += OcrBox("$period", 20, center - 15, 60, center + 15)
        }
        boxes += OcrBox("高等数学", 130, 60, 230, 80)
        boxes += OcrBox("张三", 130, 80, 230, 100)
        boxes += OcrBox("教1-101", 130, 100, 230, 120)
        boxes += OcrBox("1-16周", 130, 120, 230, 140)

        val table = JwTableAligner.align(OcrPage(800, 800, boxes))
        val result = JwOcrScheduleBuilder.build(table, "T", 20000, 20)
        val block = result.payload.terms.single().courses.single().blocks.single()

        assertEquals(1, block.startWeek)
        assertEquals(16, block.endWeek)
        assertEquals("教1-101", block.location)
        assertFalse(result.issues.any { it.contains("没识别出周次") }, result.issues.toString())
    }

    @Test
    fun `周次认不出时记 issue 而不是静默猜测`() {
        val page = OcrPage(
            width = 800,
            height = 600,
            boxes = buildList {
                listOf("周一", "周二", "周三", "周四", "周五").forEachIndexed { index, label ->
                    add(OcrBox(label, 120 + index * 100, 10, 170 + index * 100, 40))
                }
                listOf(1, 2, 3, 4).forEachIndexed { index, period ->
                    val center = 110 + index * 100
                    add(OcrBox("$period", 20, center - 15, 60, center + 15))
                }
                add(OcrBox("高等数学", 130, 70, 230, 100))
            },
        )
        val table = JwTableAligner.align(page)
        val result = JwOcrScheduleBuilder.build(table, "T", 20000, 20)

        assertTrue(result.issues.any { it.contains("没识别出周次") }, result.issues.toString())
        val block = result.payload.terms.single().courses.single().blocks.single()
        assertEquals(1, block.startWeek)
        assertEquals(20, block.endWeek)
    }
}
