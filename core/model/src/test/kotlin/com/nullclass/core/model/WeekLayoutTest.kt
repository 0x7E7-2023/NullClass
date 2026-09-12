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

    @Test
    fun `非本周课块出现在当周空着的时段`() {
        val firstHalf = CourseWithBlocks(course("a"), listOf(block("a", startWeek = 1, endWeek = 8)))

        // 第 3 周它自己就是当周真课块，不当灰块再画一遍
        assertTrue(WeekLayout.otherWeekLayout(listOf(firstHalf), week = 3).isEmpty())
        // 第 10 周它不上，同一天同一节空着 → 灰块
        assertEquals(
            listOf("a"),
            WeekLayout.otherWeekLayout(listOf(firstHalf), week = 10).getValue(1).map { it.course.id },
        )
    }

    @Test
    fun `撞上当周真课块的灰块整块不出现`() {
        // 连堂课：上半学期 1-4 节、下半学期 3-4 节
        val upper = CourseWithBlocks(
            course("u"),
            listOf(block("u", startWeek = 1, endWeek = 8, startPeriod = 1, endPeriod = 4)),
        )
        val lower = CourseWithBlocks(
            course("l"),
            listOf(block("l", startWeek = 10, endWeek = 16, startPeriod = 3, endPeriod = 4)),
        )
        val clear = CourseWithBlocks(
            course("c"),
            listOf(block("c", startWeek = 1, endWeek = 8, startPeriod = 5, endPeriod = 6)),
        )

        // 第 12 周：3-4 节被真课块占着，1-4 的灰块整块不出 —— 裁成 1-2 会谎报它只有两节；
        // 5-6 节的灰块没撞上，照画
        val week12 = WeekLayout.otherWeekLayout(listOf(upper, lower, clear), week = 12)
        assertEquals(listOf("c"), week12.getValue(1).map { it.course.id })
    }

    @Test
    fun `同一时段的多个灰块取开始节次靠前的`() {
        val wide = CourseWithBlocks(
            course("w"),
            listOf(block("w", startWeek = 10, endWeek = 16, startPeriod = 1, endPeriod = 4)),
        )
        val narrow = CourseWithBlocks(
            course("n"),
            listOf(block("n", startWeek = 1, endWeek = 8, startPeriod = 2, endPeriod = 3)),
        )

        // 第 9 周两门都不上，又都压在周一同一片节次上：画两个只会糊成一团，留开始节次靠前的那个
        // （入参顺序反着给，结果不随记录顺序变）
        val week9 = WeekLayout.otherWeekLayout(listOf(narrow, wide), week = 9)
        assertEquals(listOf("w"), week9.getValue(1).map { it.course.id })
        assertEquals(
            week9.getValue(1).map { it.block.id },
            WeekLayout.otherWeekLayout(listOf(wide, narrow), week = 9).getValue(1).map { it.block.id },
        )
    }

    @Test
    fun `同名的两个灰块也有定序，不随记录顺序变`() {
        // 两门同名课压在同一天同一节、周次错开 —— 正是本功能主打的上下半学期场景。
        // 名字/天/起始节全并列，只剩课块 id 能定序；少了它就会回落到课表记录顺序
        val firstHalf = CourseWithBlocks(
            Course(id = "c1", termId = "t1", name = "体育"),
            listOf(block("c1", startWeek = 1, endWeek = 8, startPeriod = 1, endPeriod = 2)),
        )
        val secondHalf = CourseWithBlocks(
            Course(id = "c2", termId = "t1", name = "体育"),
            listOf(block("c2", startWeek = 10, endWeek = 16, startPeriod = 1, endPeriod = 2)),
        )

        // 第 9 周两门都不上：留 id 靠前的那个（b-c1-1），正反两种记录顺序结果一致
        val forward = WeekLayout.otherWeekLayout(listOf(firstHalf, secondHalf), week = 9)
        val backward = WeekLayout.otherWeekLayout(listOf(secondHalf, firstHalf), week = 9)
        assertEquals(listOf("b-c1-1"), forward.getValue(1).map { it.block.id })
        assertEquals(forward.getValue(1).map { it.block.id }, backward.getValue(1).map { it.block.id })
    }

    @Test
    fun `灰块按天分组并按开始节次排序`() {
        val many = CourseWithBlocks(
            course("m"),
            listOf(
                block("m", dayOfWeek = 3, startWeek = 1, endWeek = 8, startPeriod = 5, endPeriod = 6),
                block("m", dayOfWeek = 3, startWeek = 1, endWeek = 8, startPeriod = 1, endPeriod = 2),
                block("m", dayOfWeek = 5, startWeek = 1, endWeek = 8),
            ),
        )

        val layout = WeekLayout.otherWeekLayout(listOf(many), week = 10)

        assertEquals(setOf(3, 5), layout.keys)
        assertEquals(listOf(1, 5), layout.getValue(3).map { it.block.startPeriod })
    }

    @Test
    fun `单双周的另一半是灰块`() {
        val odd = CourseWithBlocks(course("o"), listOf(block("o", dayOfWeek = 1, weekType = WeekType.ODD)))
        val even = CourseWithBlocks(course("e"), listOf(block("e", dayOfWeek = 2, weekType = WeekType.EVEN)))

        // 第 4 周上的是双周那门；单周那门（周一）这一周没有，就成了灰块
        val week4 = WeekLayout.otherWeekLayout(listOf(odd, even), week = 4)
        assertEquals(setOf(1), week4.keys)
        assertEquals(listOf("o"), week4.getValue(1).map { it.course.id })
    }
}
