package com.nullclass.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScheduleConflictTest {

    private fun course(id: String, name: String = "课$id") = Course(id = id, termId = "t1", name = name)

    private fun block(
        courseId: String = "",
        dayOfWeek: Int = 1,
        startWeek: Int = 1,
        endWeek: Int = 16,
        weekType: WeekType = WeekType.ALL,
        startPeriod: Int = 1,
        endPeriod: Int = 2,
    ) = ScheduleBlock(
        id = "",
        courseId = courseId,
        startWeek = startWeek,
        endWeek = endWeek,
        weekType = weekType,
        dayOfWeek = dayOfWeek,
        startPeriod = startPeriod,
        endPeriod = endPeriod,
    )

    // ---- overlapsWith ----

    @Test
    fun `同天同节次且周次相交算冲突`() {
        assertTrue(block(startPeriod = 3, endPeriod = 4).overlapsWith(block(startPeriod = 3, endPeriod = 4)))
    }

    @Test
    fun `节次端点相接算冲突`() {
        assertTrue(block(startPeriod = 1, endPeriod = 2).overlapsWith(block(startPeriod = 2, endPeriod = 3)))
    }

    @Test
    fun `节次不相交不算冲突`() {
        assertFalse(block(startPeriod = 1, endPeriod = 2).overlapsWith(block(startPeriod = 3, endPeriod = 4)))
    }

    @Test
    fun `不同天不算冲突`() {
        assertFalse(block(dayOfWeek = 1).overlapsWith(block(dayOfWeek = 2)))
    }

    @Test
    fun `周次区间不相交不算冲突`() {
        val firstHalf = block(startWeek = 1, endWeek = 8)
        val secondHalf = block(startWeek = 9, endWeek = 16)
        assertFalse(firstHalf.overlapsWith(secondHalf))
    }

    @Test
    fun `单双周错开不算冲突`() {
        val odd = block(startWeek = 1, endWeek = 15, weekType = WeekType.ODD)
        val even = block(startWeek = 2, endWeek = 16, weekType = WeekType.EVEN)
        assertFalse(odd.overlapsWith(even))
    }

    @Test
    fun `每周与单周在同一奇偶周相交算冲突`() {
        val all = block(startWeek = 2, endWeek = 16)
        val odd = block(startWeek = 1, endWeek = 15, weekType = WeekType.ODD)
        assertTrue(all.overlapsWith(odd))
    }

    // ---- ScheduleConflicts.find ----

    @Test
    fun `撞上同学期其他课程会被找出`() {
        val existing = listOf(
            CourseWithBlocks(course("a", "高等数学"), listOf(block(courseId = "a", startPeriod = 3, endPeriod = 4))),
        )
        val conflicts = ScheduleConflicts.find(
            newBlocks = listOf(block(startPeriod = 3, endPeriod = 4)),
            existing = existing,
        )

        assertEquals(1, conflicts.size)
        assertEquals("高等数学", conflicts.single().course.name)
        assertEquals(3, conflicts.single().existingBlock.startPeriod)
    }

    @Test
    fun `编辑自身时忽略自己的旧安排`() {
        val existing = listOf(
            CourseWithBlocks(course("a"), listOf(block(courseId = "a", startPeriod = 1, endPeriod = 2))),
        )
        val conflicts = ScheduleConflicts.find(
            newBlocks = listOf(block(startPeriod = 1, endPeriod = 2)),
            existing = existing,
            ignoreCourseId = "a",
        )

        assertTrue(conflicts.isEmpty())
    }

    @Test
    fun `多门课冲突全部返回`() {
        val existing = listOf(
            CourseWithBlocks(course("a"), listOf(block(courseId = "a", startPeriod = 1, endPeriod = 2))),
            CourseWithBlocks(course("b"), listOf(block(courseId = "b", startPeriod = 2, endPeriod = 3))),
        )
        val conflicts = ScheduleConflicts.find(
            newBlocks = listOf(block(startPeriod = 2, endPeriod = 2)),
            existing = existing,
        )

        assertEquals(listOf("a", "b"), conflicts.map { it.course.id })
    }

    @Test
    fun `时间错开时无冲突`() {
        val existing = listOf(
            CourseWithBlocks(course("a"), listOf(block(courseId = "a", dayOfWeek = 1, startPeriod = 1, endPeriod = 2))),
        )
        val conflicts = ScheduleConflicts.find(
            newBlocks = listOf(block(dayOfWeek = 2, startPeriod = 1, endPeriod = 2)),
            existing = existing,
        )

        assertTrue(conflicts.isEmpty())
    }
}
