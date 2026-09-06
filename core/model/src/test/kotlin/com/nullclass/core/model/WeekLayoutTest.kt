package com.nullclass.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WeekLayoutTest {

    private fun course(id: String) = Course(id = id, termId = "t1", name = "课$id")

    private fun block(
        courseId: String,
        dayOfWeek: Int = 1,
        startWeek: Int = 1,
        endWeek: Int = 16,
        weekType: WeekType = WeekType.ALL,
        startPeriod: Int = 1,
        endPeriod: Int = 2,
    ) = ScheduleBlock(
        id = "b-$courseId-$dayOfWeek",
        courseId = courseId,
        startWeek = startWeek,
        endWeek = endWeek,
        weekType = weekType,
        dayOfWeek = dayOfWeek,
        startPeriod = startPeriod,
        endPeriod = endPeriod,
    )

    @Test
    fun `按天分组且按开始节次排序`() {
        val schedule = listOf(
            CourseWithBlocks(course("a"), listOf(block("a", dayOfWeek = 2, startPeriod = 3, endPeriod = 4))),
            CourseWithBlocks(course("b"), listOf(block("b", dayOfWeek = 2, startPeriod = 1, endPeriod = 2))),
            CourseWithBlocks(course("c"), listOf(block("c", dayOfWeek = 5, startPeriod = 5, endPeriod = 6))),
        )

        val layout = WeekLayout.layoutForWeek(schedule, week = 3)

        assertEquals(setOf(2, 5), layout.keys)
        assertEquals(listOf("b", "a"), layout.getValue(2).map { it.course.id })
        assertEquals(listOf(5, 6), layout.getValue(5).first().let { it.block.startPeriod..it.block.endPeriod }.toList())
    }

    @Test
    fun `单双周过滤`() {
        val oddOnly = CourseWithBlocks(course("o"), listOf(block("o", weekType = WeekType.ODD)))
        val evenOnly = CourseWithBlocks(course("e"), listOf(block("e", weekType = WeekType.EVEN)))

        val week3 = WeekLayout.layoutForWeek(listOf(oddOnly, evenOnly), week = 3)
        val week4 = WeekLayout.layoutForWeek(listOf(oddOnly, evenOnly), week = 4)

        assertEquals(setOf("o"), week3.values.flatten().map { it.course.id }.toSet())
        assertEquals(setOf("e"), week4.values.flatten().map { it.course.id }.toSet())
    }

    @Test
    fun `周次范围过滤`() {
        val late = CourseWithBlocks(course("l"), listOf(block("l", startWeek = 10, endWeek = 16)))

        assertTrue(WeekLayout.layoutForWeek(listOf(late), week = 10).isNotEmpty())
        assertTrue(WeekLayout.layoutForWeek(listOf(late), week = 9).isEmpty())
        assertTrue(WeekLayout.layoutForWeek(listOf(late), week = 17).isEmpty())
    }

    @Test
    fun `isOccupied 判定占用`() {
        val schedule = listOf(
            CourseWithBlocks(course("a"), listOf(block("a", dayOfWeek = 1, startPeriod = 1, endPeriod = 2))),
        )
        val layout = WeekLayout.layoutForWeek(schedule, week = 1)

        assertTrue(WeekLayout.isOccupied(layout, dayOfWeek = 1, period = 2))
        assertFalse(WeekLayout.isOccupied(layout, dayOfWeek = 1, period = 3))
        assertFalse(WeekLayout.isOccupied(layout, dayOfWeek = 3, period = 1))
    }
}
