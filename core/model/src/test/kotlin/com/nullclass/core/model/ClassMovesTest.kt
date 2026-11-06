package com.nullclass.core.model

import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClassMovesTest {

    // 2026-09-07 周一开学，20 周
    private val term = Term(
        id = "t1",
        name = "2026-2027-1",
        firstDayEpochDay = LocalDate.of(2026, 9, 7).toEpochDay(),
        totalWeeks = 20,
    )

    private fun day(month: Int, dayOfMonth: Int) = LocalDate.of(2026, month, dayOfMonth).toEpochDay()

    // 高数：每周五 3-4 节
    private val math = CourseWithBlocks(
        Course(id = "c1", termId = "t1", name = "高等数学"),
        listOf(ScheduleBlock(id = "b1", courseId = "c1", startWeek = 1, endWeek = 20, dayOfWeek = 5, startPeriod = 3, endPeriod = 4)),
    )
    private val schedule = listOf(math)

    // 10-09（第 5 周周五）那次挪到 10-11（第 5 周周日）5-6 节
    private val move = ClassMove(blockId = "b1", fromEpochDay = day(10, 9), toEpochDay = day(10, 11), toStartPeriod = 5, toEndPeriod = 6)

    private val times = (1..8).map { PeriodTime(periodIndex = it, startMinuteOfDay = 420 + it * 60, endMinuteOfDay = 465 + it * 60) }

    @Test
    fun `周视图 - 原位置这一次空出，目标日按新节次出现，别的周不动`() {
        val week5 = WeekLayout.layoutForWeek(schedule, 5, term, classMoves = listOf(move))
        assertTrue(5 !in week5)
        val moved = week5.getValue(7).single().block
        assertEquals(5 to 6, moved.startPeriod to moved.endPeriod)
        assertEquals("b1", moved.id)

        val week6 = WeekLayout.layoutForWeek(schedule, 6, term, classMoves = listOf(move))
        assertEquals(setOf(5), week6.keys)
    }

    @Test
    fun `今日页 - 原日期不列，目标日列出`() {
        val from = assembleTodaySnapshot(term, schedule, times, LocalDate.ofEpochDay(day(10, 9)), classMoves = listOf(move))
        assertTrue(from.blocks.isEmpty())
        val to = assembleTodaySnapshot(term, schedule, times, LocalDate.ofEpochDay(day(10, 11)), classMoves = listOf(move))
        assertEquals(5, to.blocks.single().placed.block.startPeriod)
    }

    @Test
    fun `今日页 - 串课到学期外的那天，挪进来的课照样列出`() {
        val outOfTerm = mapOf(day(10, 11) to day(8, 1))
        val to = assembleTodaySnapshot(
            term, schedule, times, LocalDate.ofEpochDay(day(10, 11)),
            dayOverrides = outOfTerm, classMoves = listOf(move),
        )
        assertEquals("高等数学", to.blocks.single().placed.course.name)
    }

    @Test
    fun `提醒 - 原日期不排，目标日按新节次排`() {
        val from = LocalDate.of(2026, 10, 9).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val planned = ReminderPlanner.upcoming(
            term, schedule, times, from, horizonDays = 2, zone = ZoneOffset.UTC, classMoves = listOf(move),
        )
        val c = planned.single()
        assertEquals(LocalDate.of(2026, 10, 11), java.time.Instant.ofEpochMilli(c.startAtMillis).atZone(ZoneOffset.UTC).toLocalDate())
        assertEquals(5, c.block.startPeriod)
        // 原来那次已提醒过，挪到 24h 内也不能被当成「已发过」
        val sentOriginal = setOf("b1:" + (c.startAtMillis - 20L * 3600 * 1000))
        assertTrue(!ReminderPlanner.isAlreadySent(c, sentOriginal))
    }

    @Test
    fun `目标日在学期外或原课块已删时不凭空造课`() {
        val outside = move.copy(toEpochDay = day(8, 1))
        assertTrue(ClassMoves.movedIn(term, schedule, listOf(outside), day(8, 1)).isEmpty())
        assertTrue(ClassMoves.movedIn(term, emptyList(), listOf(move), day(10, 11)).isEmpty())
    }
}
