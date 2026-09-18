package com.nullclass.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WidgetAgendaTest {

    @Test
    fun `分页可遍历所有课程且末页不重复`() {
        val snap = snapshot(*(1..7).map { i ->
            entry("b$i", courseId = "c$i", startPeriod = i, startMin = 400 + i * 60, endMin = 450 + i * 60)
        }.toTypedArray())
        val pages = (0..2).map { paginateWidgetAgenda(snap, 0, 3, it) }
        assertEquals(listOf(3, 3, 1), pages.map { it.rows.size })
        assertEquals((1..7).map { "b$it" }, pages.flatMap { it.rows }.map { it.placed.block.id })
        assertTrue(!pages.first().hasPrevious)
        assertTrue(pages.first().hasNext)
        assertTrue(pages.last().hasPrevious)
        assertTrue(!pages.last().hasNext)
        assertTrue(pages.all { it.totalRows == 7 && it.pageCount == 3 })
    }

    @Test
    fun `跨下课时刻和调整容量后越界页码被收敛`() {
        val snap = snapshot(
            entry("a", startPeriod = 1, startMin = 480, endMin = 525),
            entry("b", courseId = "c2", startPeriod = 3, startMin = 600, endMin = 645),
        )
        assertEquals(1, paginateWidgetAgenda(snap, 0, 1, Int.MAX_VALUE).index)
        assertEquals(0, paginateWidgetAgenda(snap, 550, 1, 1).index)
        assertEquals(listOf("b"), paginateWidgetAgenda(snap, 550, 1, 1).rows.map { it.placed.block.id })
        assertEquals(0, paginateWidgetAgenda(snap, 0, 5, 1).index)
        assertEquals(0, paginateWidgetAgenda(snap, 0, 1, -5).index)
    }

    @Test
    fun `分页前过滤并合并连堂`() {
        val snap = snapshot(
            entry("a", startPeriod = 1, startMin = 480, endMin = 525),
            entry("b", startPeriod = 2, startMin = 535, endMin = 580),
            entry("c", courseId = "c2", startPeriod = 3, startMin = 600, endMin = 645),
        )
        val page = paginateWidgetAgenda(snap, 0, 1, 1)
        assertEquals(2, page.totalRows)
        assertEquals(listOf("c"), page.rows.map { it.placed.block.id })
        val empty = paginateWidgetAgenda(snap, 700, 0, 99)
        assertTrue(empty.rows.isEmpty())
        assertEquals(1, empty.pageCount)
        assertEquals(0, empty.index)
    }

    @Test
    fun `分页布局为按钮预留空间且大字号自动减少行数`() {
        for (height in listOf(110, 180, 240, 320, 600)) {
            for (font in WidgetFontSize.entries) {
                val layout = widgetPageLayout(height, font)
                val used = layout.paddingDp * 2 + layout.headerHeightDp + 8 +
                    layout.rowHeightDp * layout.rowsPerPage +
                    if (layout.inlinePager) 0 else layout.controlsHeightDp + 8
                assertTrue(used <= height, "height=$height font=$font used=$used")
                assertTrue(layout.rowsPerPage in 1..10)
            }
        }
        assertTrue(widgetPageLayout(110, WidgetFontSize.STANDARD).inlinePager)
        assertTrue(!widgetPageLayout(240, WidgetFontSize.STANDARD).inlinePager)
        assertTrue(widgetPageLayout(320, WidgetFontSize.STANDARD).rowsPerPage >
            widgetPageLayout(320, WidgetFontSize.XLARGE).rowsPerPage)
        assertTrue(widgetPageLayout(320, WidgetFontSize.STANDARD, 1.5f).rowsPerPage <
            widgetPageLayout(320, WidgetFontSize.STANDARD).rowsPerPage)
    }

    private fun entry(
        id: String,
        courseId: String = "c1",
        name: String = "高数",
        startPeriod: Int,
        endPeriod: Int = startPeriod,
        startMin: Int,
        endMin: Int,
        location: String? = null,
    ) = TodaySnapshot.TodayEntry(
        placed = PlacedBlock(
            Course(id = courseId, termId = "t1", name = name),
            ScheduleBlock(
                id = id,
                courseId = courseId,
                startWeek = 1,
                endWeek = 20,
                dayOfWeek = 3,
                startPeriod = startPeriod,
                endPeriod = endPeriod,
                location = location,
            ),
        ),
        startMinuteOfDay = startMin,
        endMinuteOfDay = endMin,
        startTime = ScheduleFormat.minuteLabel(startMin),
        endTime = ScheduleFormat.minuteLabel(endMin),
        session = Session.MORNING,
    )

    private fun snapshot(vararg entries: TodaySnapshot.TodayEntry) = TodaySnapshot(
        termName = "2026-2027-1",
        weekNumber = 1,
        blocks = entries.toList(),
    )

    @Test
    fun `已上完的课不占行`() {
        val snap = snapshot(
            entry("b1", startPeriod = 1, startMin = 480, endMin = 580),
            entry("b2", startPeriod = 3, startMin = 840, endMin = 885),
        )
        val agenda = buildWidgetAgenda(snap, nowMinuteOfDay = 600, maxRows = 5)
        assertEquals(listOf("b2"), agenda.rows.map { it.placed.block.id })
        assertEquals(0, agenda.hiddenUpcoming)
    }

    @Test
    fun `全部上完 - 空列表`() {
        val snap = snapshot(entry("b1", startPeriod = 1, startMin = 480, endMin = 580))
        val agenda = buildWidgetAgenda(snap, nowMinuteOfDay = 600, maxRows = 5)
        assertTrue(agenda.rows.isEmpty())
        assertEquals(0, agenda.hiddenUpcoming)
    }

    @Test
    fun `同课连堂合并为一条，时间接到最后一节下课`() {
        val snap = snapshot(
            entry("p1", courseId = "pe", name = "体育", startPeriod = 1, endPeriod = 2, startMin = 480, endMin = 580, location = "操场"),
            entry("p2", courseId = "pe", name = "体育", startPeriod = 3, endPeriod = 4, startMin = 600, endMin = 700, location = "操场"),
        )
        val agenda = buildWidgetAgenda(snap, nowMinuteOfDay = 0, maxRows = 5)
        assertEquals(1, agenda.rows.size)
        val row = agenda.rows[0]
        assertEquals("p1", row.placed.block.id)
        assertEquals(1, row.placed.block.startPeriod)
        assertEquals(4, row.placed.block.endPeriod)
        assertEquals(480, row.startMinuteOfDay)
        assertEquals(700, row.endMinuteOfDay)
        assertEquals("8:00", row.startTime)
        assertEquals("11:40", row.endTime)
        assertEquals("操场", row.placed.block.location)
        assertEquals(0, agenda.hiddenUpcoming)
    }

    @Test
    fun `单节连续的同一门课也会并成一条`() {
        val pe = (1..4).map { i ->
            entry("p$i", courseId = "pe", name = "体育", startPeriod = i, startMin = 400 + i * 50, endMin = 440 + i * 50)
        }
        val merged = mergeConsecutiveSameCourse(pe)
        assertEquals(1, merged.size)
        assertEquals(1, merged[0].placed.block.startPeriod)
        assertEquals(4, merged[0].placed.block.endPeriod)
    }

    @Test
    fun `不同课不合并`() {
        val snap = snapshot(
            entry("b1", courseId = "c1", name = "高数", startPeriod = 1, endPeriod = 2, startMin = 480, endMin = 580),
            entry("b2", courseId = "c2", name = "英语", startPeriod = 3, endPeriod = 4, startMin = 600, endMin = 700),
        )
        val agenda = buildWidgetAgenda(snap, nowMinuteOfDay = 0, maxRows = 5)
        assertEquals(listOf("b1", "b2"), agenda.rows.map { it.placed.block.id })
    }

    @Test
    fun `同课但隔了一节不合并`() {
        val snap = snapshot(
            entry("b1", courseId = "c1", startPeriod = 1, endPeriod = 2, startMin = 480, endMin = 580),
            entry("b2", courseId = "c1", startPeriod = 5, endPeriod = 6, startMin = 800, endMin = 900),
        )
        val agenda = buildWidgetAgenda(snap, nowMinuteOfDay = 0, maxRows = 5)
        assertEquals(listOf("b1", "b2"), agenda.rows.map { it.placed.block.id })
    }

    @Test
    fun `中间夹了别的课不跨课合并`() {
        val snap = snapshot(
            entry("a1", courseId = "c1", startPeriod = 1, endPeriod = 2, startMin = 480, endMin = 580),
            entry("b1", courseId = "c2", name = "英语", startPeriod = 3, endPeriod = 4, startMin = 600, endMin = 700),
            entry("a2", courseId = "c1", startPeriod = 5, endPeriod = 6, startMin = 800, endMin = 900),
        )
        val agenda = buildWidgetAgenda(snap, nowMinuteOfDay = 0, maxRows = 5)
        assertEquals(listOf("a1", "b1", "a2"), agenda.rows.map { it.placed.block.id })
    }

    @Test
    fun `第一段已下课则不再并进还没上的后半段`() {
        val snap = snapshot(
            entry("p1", courseId = "pe", name = "体育", startPeriod = 1, endPeriod = 2, startMin = 480, endMin = 580),
            entry("p2", courseId = "pe", name = "体育", startPeriod = 3, endPeriod = 4, startMin = 600, endMin = 700),
        )
        val agenda = buildWidgetAgenda(snap, nowMinuteOfDay = 590, maxRows = 5)
        assertEquals(listOf("p2"), agenda.rows.map { it.placed.block.id })
        assertEquals(600, agenda.rows[0].startMinuteOfDay)
    }

    @Test
    fun `前半段上课中 - 合并后剩余算到连堂结束`() {
        val snap = snapshot(
            entry("p1", courseId = "pe", name = "体育", startPeriod = 1, endPeriod = 2, startMin = 480, endMin = 580),
            entry("p2", courseId = "pe", name = "体育", startPeriod = 3, endPeriod = 4, startMin = 600, endMin = 700),
        )
        val agenda = buildWidgetAgenda(snap, nowMinuteOfDay = 500, maxRows = 5)
        val row = agenda.rows.single()
        assertTrue(snap.inProgress(row, 500))
        assertEquals(200, snap.remainingMinutes(row, 500))
    }

    @Test
    fun `地点缺的那段用另一段补上`() {
        val merged = mergeConsecutiveSameCourse(
            listOf(
                entry("p1", courseId = "pe", name = "体育", startPeriod = 1, startMin = 480, endMin = 525),
                entry("p2", courseId = "pe", name = "体育", startPeriod = 2, startMin = 535, endMin = 580, location = "操场"),
            ),
        )
        assertEquals("操场", merged.single().placed.block.location)
    }

    @Test
    fun `超出配额截断并计 hidden`() {
        val snap = snapshot(
            entry("b1", courseId = "c1", startPeriod = 1, startMin = 480, endMin = 525),
            entry("b2", courseId = "c2", name = "英语", startPeriod = 3, startMin = 840, endMin = 885),
            entry("b3", courseId = "c3", name = "线代", startPeriod = 5, startMin = 900, endMin = 945),
        )
        val agenda = buildWidgetAgenda(snap, nowMinuteOfDay = 0, maxRows = 2)
        assertEquals(listOf("b1", "b2"), agenda.rows.map { it.placed.block.id })
        assertEquals(1, agenda.hiddenUpcoming)
    }

    @Test
    fun `合并后再截断 - hidden 按合并后的门数`() {
        val snap = snapshot(
            entry("p1", courseId = "pe", name = "体育", startPeriod = 1, startMin = 480, endMin = 525),
            entry("p2", courseId = "pe", name = "体育", startPeriod = 2, startMin = 535, endMin = 580),
            entry("b1", courseId = "c1", startPeriod = 3, startMin = 600, endMin = 645),
            entry("b2", courseId = "c2", name = "英语", startPeriod = 5, startMin = 840, endMin = 885),
        )
        val agenda = buildWidgetAgenda(snap, nowMinuteOfDay = 0, maxRows = 2)
        assertEquals(listOf("p1", "b1"), agenda.rows.map { it.placed.block.id })
        assertEquals(1, agenda.hiddenUpcoming)
    }

    @Test
    fun `maxRows 小于 1 仍至少留下一节`() {
        val snap = snapshot(
            entry("b1", startPeriod = 1, startMin = 480, endMin = 525),
            entry("b2", courseId = "c2", name = "英语", startPeriod = 3, startMin = 840, endMin = 885),
        )
        val agenda = buildWidgetAgenda(snap, nowMinuteOfDay = 0, maxRows = 0)
        assertEquals(listOf("b1"), agenda.rows.map { it.placed.block.id })
        assertEquals(1, agenda.hiddenUpcoming)
    }

    @Test
    fun `今天没课 - 空列表 hidden 为 0`() {
        val agenda = buildWidgetAgenda(TodaySnapshot.EMPTY, nowMinuteOfDay = 0, maxRows = 3)
        assertTrue(agenda.rows.isEmpty())
        assertEquals(0, agenda.hiddenUpcoming)
    }

    @Test
    fun `3x2 标准档 1 门，2x3 高 180dp 也只塞 2 门 - 行高按 sp 实际行盒计`() {
        assertEquals(1, widgetCourseRowBudget(110, WidgetFontSize.STANDARD))
        assertEquals(2, widgetCourseRowBudget(180, WidgetFontSize.STANDARD))
        assertEquals(1, widgetCourseRowBudget(110, WidgetFontSize.XLARGE))
        assertEquals(1, widgetCourseRowBudget(180, WidgetFontSize.XLARGE))
    }

    @Test
    fun `系统 fontScale 把行撑高，同样高度少排一门`() {
        assertEquals(2, widgetCourseRowBudget(180, WidgetFontSize.STANDARD, fontScale = 1f))
        assertEquals(1, widgetCourseRowBudget(180, WidgetFontSize.STANDARD, fontScale = 1.4f))
        assertEquals(3, widgetCourseRowBudget(220, WidgetFontSize.STANDARD, fontScale = 1f))
        assertEquals(2, widgetCourseRowBudget(220, WidgetFontSize.STANDARD, fontScale = 1.5f))
    }

    @Test
    fun `3x2 一行之后塞不下还剩n节，180dp 两行之后可以`() {
        assertTrue(!widgetFooterFits(110, WidgetFontSize.STANDARD, fontScale = 1f, rowCount = 1))
        assertTrue(widgetFooterFits(180, WidgetFontSize.STANDARD, fontScale = 1f, rowCount = 2))
    }
}
