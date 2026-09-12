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
    fun `整行里的星期优先于它所在的列`() {
        // 合班课那类页面会把一格画得横跨好几个星期列，格子自己写的「星期3」比列位置可信
        assertNull(JwCourseTextParser.parseDayInLine("1-16周"))
        assertNull(JwCourseTextParser.parseDayInLine("第三周"))
        assertEquals(1, JwCourseTextParser.parseDayInLine("2-5周,星期1,1-2节,汇智楼105"))
        assertEquals(3, JwCourseTextParser.parseDayInLine("1-16周,星期3,1-2节"))
        assertEquals(7, JwCourseTextParser.parseDayInLine("星期天"))
        assertEquals(2, JwCourseTextParser.parseDayInLine("周二,周日"))
        assertEquals(1, JwCourseTextParser.parseDayInLine("周一,周二"))

        val boxes = mutableListOf<OcrBox>()
        listOf("周一", "周二", "周三", "周四", "周五").forEachIndexed { index, label ->
            boxes += OcrBox(label, 130 + index * 100, 10, 180 + index * 100, 40)
        }
        listOf(1, 2, 3, 4).forEachIndexed { index, period ->
            val center = 110 + index * 100
            boxes += OcrBox("$period", 20, center - 15, 60, center + 15)
        }
        // 画在第 1 列里，格子自己写着星期 3
        boxes += OcrBox("高等数学", 130, 70, 230, 100)
        boxes += OcrBox("1-16周,星期3,1-2节", 130, 100, 230, 130)

        val table = JwTableAligner.align(OcrPage(800, 600, boxes))
        val result = JwOcrScheduleBuilder.build(table, "T", 20000, 20)
        val block = result.payload.terms.single().courses.single().blocks.single()
        assertEquals(3, block.dayOfWeek)
        assertEquals(1, block.startPeriod)
        assertEquals(2, block.endPeriod)
    }

    @Test
    fun `节次列不在页宽左侧四分之一内也能定行`() {
        // 表格靠右、页很宽时，按「页宽的 1/4」判会把整列节次漏掉；现在按「在星期列外侧」判
        val boxes = mutableListOf<OcrBox>()
        listOf("周一", "周二", "周三", "周四", "周五").forEachIndexed { index, label ->
            boxes += OcrBox(label, 400 + index * 100, 10, 450 + index * 100, 40)
        }
        listOf(1, 2, 3, 4).forEachIndexed { index, period ->
            val center = 110 + index * 100
            boxes += OcrBox("$period", 280, center - 15, 320, center + 15)
        }
        boxes += OcrBox("高等数学", 410, 70, 510, 100)
        boxes += OcrBox("1-16周", 410, 100, 510, 130)

        val table = JwTableAligner.align(OcrPage(width = 1000, height = 600, boxes = boxes))
        assertTrue(table.reliable, table.warnings.toString())
        assertEquals(listOf(1, 2, 3, 4), table.rowPeriods)
        assertEquals("高等数学", JwOcrScheduleBuilder.build(table, "T", 20000, 20)
            .payload.terms.single().courses.single().name)
    }

    @Test
    fun `同一门课被画在两个列里也只收一份课块`() {
        val boxes = mutableListOf<OcrBox>()
        listOf("周一", "周二", "周三", "周四", "周五").forEachIndexed { index, label ->
            boxes += OcrBox(label, 130 + index * 100, 10, 180 + index * 100, 40)
        }
        listOf(1, 2, 3, 4).forEachIndexed { index, period ->
            val center = 110 + index * 100
            boxes += OcrBox("$period", 20, center - 15, 60, center + 15)
        }
        // 同一段文字出现在两个列里（页面把跨列的课各画了一遍）
        listOf(130, 330).forEach { left ->
            boxes += OcrBox("高等数学", left, 70, left + 100, 100)
            boxes += OcrBox("1-16周,星期1,1-2节", left, 100, left + 100, 130)
        }

        val table = JwTableAligner.align(OcrPage(800, 600, boxes))
        val course = JwOcrScheduleBuilder.build(table, "T", 20000, 20)
            .payload.terms.single().courses.single()
        assertEquals(1, course.blocks.size, course.blocks.toString())
    }

    @Test
    fun `行标是上课时间也能定行 并提醒核对节次`() {
        val boxes = mutableListOf<OcrBox>()
        listOf("周一", "周二", "周三", "周四", "周五").forEachIndexed { index, label ->
            boxes += OcrBox(label, 130 + index * 100, 10, 180 + index * 100, 40)
        }
        listOf("08:00-08:45", "08:55-09:40", "10:00-10:45", "10:55-11:40").forEachIndexed { index, label ->
            val center = 110 + index * 100
            boxes += OcrBox(label, 20, center - 15, 120, center + 15)
        }
        boxes += OcrBox("高等数学", 130, 70, 230, 100)
        boxes += OcrBox("1-16周,星期1,1-2节", 130, 100, 230, 130)

        val table = JwTableAligner.align(OcrPage(800, 600, boxes))
        assertTrue(table.reliable, table.warnings.toString())
        assertEquals(4, table.rowAnchors.size)
        assertTrue(table.warnings.any { it.contains("按上课时间") }, table.warnings.toString())

        val result = JwOcrScheduleBuilder.build(table, "T", 20000, 20)
        val block = result.payload.terms.single().courses.single().blocks.single()
        assertEquals(1, block.dayOfWeek)
        // 节次优先取格子里写的「1-2节」，不是按行序推出来的
        assertEquals(1, block.startPeriod)
        assertEquals(2, block.endPeriod)
        // 推断出来的节次号必须出现在校对页上，不能只躺在 warnings 里
        assertTrue(result.issues.any { it.contains("按上课时间") }, result.issues.toString())
    }

    @Test
    fun `整页没有周次时逐格还原 而不是把一列糊成一门课`() {
        // 「本周课表」那类视图：格子里只有课名/教师/教室，没有周次。按周次切段就无从谈起，
        // 这时候必须退回按格子分组，否则一整列的课会被拼成一门课（课名还只剩第一个）。
        val boxes = mutableListOf<OcrBox>()
        listOf("周一", "周二", "周三", "周四", "周五").forEachIndexed { index, label ->
            boxes += OcrBox(label, 130 + index * 100, 10, 180 + index * 100, 40)
        }
        listOf(1, 2, 3, 4).forEachIndexed { index, period ->
            val center = 110 + index * 100
            boxes += OcrBox("$period", 20, center - 15, 60, center + 15)
        }
        boxes += OcrBox("大学英语", 130, 70, 230, 100)
        boxes += OcrBox("李四", 130, 100, 230, 130)
        boxes += OcrBox("教1-101", 130, 130, 230, 160)
        boxes += OcrBox("高等数学", 230, 70, 330, 100)
        boxes += OcrBox("王五", 230, 100, 330, 130)
        boxes += OcrBox("教2-201", 230, 130, 330, 160)

        val table = JwTableAligner.align(OcrPage(800, 600, boxes))
        assertTrue(table.reliable, table.warnings.toString())
        val result = JwOcrScheduleBuilder.build(table, "T", 20000, 20)
        val courses = result.payload.terms.single().courses
        assertEquals(listOf("大学英语", "高等数学"), courses.map { it.name }.sorted())
        assertTrue(result.issues.any { it.contains("没有周次信息") }, result.issues.toString())

        val english = courses.first { it.name == "大学英语" }
        assertEquals("李四", english.teacher)
        val block = english.blocks.single()
        assertEquals(1, block.dayOfWeek)
        assertEquals("教1-101", block.location)
        // 周次认不出 → 按整学期兜底，并逐条提示核对
        assertEquals(1, block.startWeek)
        assertEquals(20, block.endWeek)
        assertTrue(result.issues.any { it.contains("没识别出周次") }, result.issues.toString())
    }

    @Test
    fun `表格上下的页头页脚不该把课表判成不可靠`() {
        // OCR 整屏截图必然带上页头菜单与页脚版权：它们不是课表内容，
        // 不该按「落不进网格」计入比例（真机实测把一张干净课表压到 33% 直接拒识）
        val boxes = mutableListOf<OcrBox>()
        boxes += OcrBox("首页 选课 成绩查询 教学安排", 20, 5, 400, 35)
        boxes += OcrBox("版权所有 © 教务处", 20, 700, 200, 730)
        listOf("周一", "周二", "周三", "周四", "周五").forEachIndexed { index, label ->
            boxes += OcrBox(label, 130 + index * 100, 60, 180 + index * 100, 90)
        }
        listOf(1, 2, 3, 4).forEachIndexed { index, period ->
            val center = 160 + index * 100
            boxes += OcrBox("$period", 20, center - 15, 60, center + 15)
        }
        boxes += OcrBox("高等数学", 130, 120, 230, 150)
        boxes += OcrBox("1-16周", 130, 150, 230, 180)

        val table = JwTableAligner.align(OcrPage(800, 760, boxes))
        assertTrue(table.reliable, table.warnings.toString())
        assertTrue(table.unassigned.isEmpty(), table.unassigned.toString())
        assertEquals("高等数学\n1-16周", table.cells[0][0])
    }

    @Test
    fun `周次文本不会被当成节次标注`() {

        assertNull(JwCourseTextParser.parsePeriodLabel("1-16周"))
        assertNull(JwCourseTextParser.parsePeriodLabel("1-16周(单)"))
        assertEquals(3..4, JwCourseTextParser.parsePeriodLabel("3-4"))
        assertEquals(5..5, JwCourseTextParser.parsePeriodLabel("第5节"))
        // 节次与上课时间叠在同一格（多行文本用换行拼接）时，逐行找
        assertEquals(3..4, JwCourseTextParser.parsePeriodLabel("3-4\n10:00-10:45"))
        assertEquals(2..2, JwCourseTextParser.parsePeriodLabel("第2节\n08:55-09:40"))
        assertEquals(1..2, JwCourseTextParser.parsePeriodLabel("上午\n1-2\n08:00-08:45"))
        // 时间不能当节次：整行"08:00-08:45"里没有纯节次标注
        assertNull(JwCourseTextParser.parsePeriodLabel("08:00-08:45"))
        assertNull(JwCourseTextParser.parsePeriodLabel("1-16周\n3-4周"))
    }

    @Test
    fun `总周数取表里最大的周次`() {
        val table = JwTableAligner.align(syntheticPage())
        // 合成表里最大周次是 1-16 周，默认 20 更大时保持默认
        assertEquals(20, JwOcrScheduleBuilder.inferTotalWeeks(table))
        val longTerm = AlignedTable(
            rowAnchors = listOf(0),
            rowPeriods = listOf(1),
            rowEndPeriods = listOf(1),
            colAnchors = listOf(0),
            colDays = listOf(1),
            cells = listOf(listOf("高等数学\n1-22周\n教1-101"), listOf("大学英语\n2-16双周")),
            reliable = true,
            warnings = emptyList(),
        )
        // 表里出现 22 周 → 总周数抬到 22（默认 20 是下限，不是上限）
        assertEquals(22, JwOcrScheduleBuilder.inferTotalWeeks(longTerm))
        assertEquals(30, JwOcrScheduleBuilder.inferTotalWeeks(longTerm, fallback = 30))
        assertEquals(18, JwOcrScheduleBuilder.inferTotalWeeks(longTerm, maxWeeks = 18))
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
