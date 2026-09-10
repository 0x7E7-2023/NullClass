package com.nullclass.core.model

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TodayScheduleTest {

    // 2026-09-07 周一开学，20 周；2026-09-09 是第 1 周周三
    private val term = Term(
        id = "t1",
        name = "2026-2027-1",
        firstDayEpochDay = LocalDate.of(2026, 9, 7).toEpochDay(),
        totalWeeks = 20,
    )
    private val times = listOf(
        PeriodTime("t1", 1, 480, 525, Session.MORNING),
        PeriodTime("t1", 2, 535, 580, Session.MORNING),
        PeriodTime("t1", 3, 840, 885, Session.AFTERNOON),
    )

    private fun schedule(vararg blocks: ScheduleBlock) = listOf(
        CourseWithBlocks(Course(id = "c1", termId = "t1", name = "高数"), blocks.toList()),
    )

    @Test
    fun `当天有课 - 只含当天且本周出现的课，按开始时间排序`() {
        val today = LocalDate.of(2026, 9, 9) // 第 1 周周三
        val schedule = schedule(
            ScheduleBlock(id = "b1", courseId = "c1", startWeek = 1, endWeek = 20, dayOfWeek = 3, startPeriod = 3, endPeriod = 3),
            ScheduleBlock(id = "b2", courseId = "c1", startWeek = 1, endWeek = 20, dayOfWeek = 3, startPeriod = 1, endPeriod = 2),
            ScheduleBlock(id = "b3", courseId = "c1", startWeek = 2, endWeek = 20, dayOfWeek = 3, startPeriod = 1, endPeriod = 1), // 本周不上
            ScheduleBlock(id = "b4", courseId = "c1", startWeek = 1, endWeek = 20, dayOfWeek = 4, startPeriod = 1, endPeriod = 1), // 周四
        )
        val snapshot = assembleTodaySnapshot(term, schedule, times, today)
        assertEquals("2026-2027-1", snapshot.termName)
        assertEquals(1, snapshot.weekNumber)
        assertEquals(listOf("b2", "b1"), snapshot.blocks.map { it.placed.block.id })
        assertEquals("8:00", snapshot.blocks[0].startTime)
        assertEquals("9:40", snapshot.blocks[0].endTime)
        assertEquals(Session.AFTERNOON, snapshot.blocks[1].session)
    }

    @Test
    fun `nextUp - 当前时刻起未结束的第一节`() {
        val today = LocalDate.of(2026, 9, 9)
        val schedule = schedule(
            ScheduleBlock(id = "b1", courseId = "c1", startWeek = 1, endWeek = 20, dayOfWeek = 3, startPeriod = 1, endPeriod = 2), // 8:00-9:40
            ScheduleBlock(id = "b2", courseId = "c1", startWeek = 1, endWeek = 20, dayOfWeek = 3, startPeriod = 3, endPeriod = 3), // 14:00-14:45
        )
        val snapshot = assembleTodaySnapshot(term, schedule, times, today)
        // 7:00 → 第一节还没开始
        assertEquals("b1", snapshot.nextUp(7 * 60)?.placed?.block?.id)
        // 9:00 → 第一节进行中
        assertEquals("b1", snapshot.nextUp(9 * 60)?.placed?.block?.id)
        assertTrue(snapshot.inProgress(snapshot.blocks[0], 9 * 60))
        // 9:41 → 第一节结束，下一节 b2
        assertEquals("b2", snapshot.nextUp(9 * 60 + 41)?.placed?.block?.id)
        // 15:00 → 全部结束
        assertNull(snapshot.nextUp(15 * 60))
    }

    @Test
    fun `inProgress - 返回正在上的课，课间与跨度外为 null`() {
        val today = LocalDate.of(2026, 9, 9)
        val schedule = schedule(
            ScheduleBlock(id = "b1", courseId = "c1", startWeek = 1, endWeek = 20, dayOfWeek = 3, startPeriod = 1, endPeriod = 2), // 8:00-9:40
            ScheduleBlock(id = "b2", courseId = "c1", startWeek = 1, endWeek = 20, dayOfWeek = 3, startPeriod = 3, endPeriod = 3), // 14:00-14:45
        )
        val snapshot = assembleTodaySnapshot(term, schedule, times, today)
        // 7:59 还没上、9:41 已下课 → 课间 null
        assertNull(snapshot.inProgress(7 * 60 + 59))
        assertNull(snapshot.inProgress(9 * 60 + 41))
        // 8:00 起算上课中，9:40 整分钟已算下课
        assertEquals("b1", snapshot.inProgress(8 * 60)?.placed?.block?.id)
        assertEquals("b1", snapshot.inProgress(9 * 60 + 39)?.placed?.block?.id)
        assertNull(snapshot.inProgress(9 * 60 + 40))
        assertEquals("b2", snapshot.inProgress(14 * 60 + 30)?.placed?.block?.id)
    }

    @Test
    fun `remainingMinutes - 距下课的分钟数，跨度外夹在课长内`() {
        val today = LocalDate.of(2026, 9, 9)
        val schedule = schedule(
            ScheduleBlock(id = "b1", courseId = "c1", startWeek = 1, endWeek = 20, dayOfWeek = 3, startPeriod = 1, endPeriod = 2), // 8:00-9:40，100 分钟
        )
        val snapshot = assembleTodaySnapshot(term, schedule, times, today)
        val b1 = snapshot.blocks[0]
        assertEquals(100, snapshot.remainingMinutes(b1, 8 * 60))
        assertEquals(1, snapshot.remainingMinutes(b1, 9 * 60 + 39))
        // 课前超量夹回课长、课后负值夹回 0（该值仅上课中被使用，此处只验证不越界）
        assertEquals(100, snapshot.remainingMinutes(b1, 6 * 60))
        assertEquals(0, snapshot.remainingMinutes(b1, 15 * 60))
    }

    @Test
    fun `学期外日期 - 空快照带学期名`() {
        val snapshot = assembleTodaySnapshot(
            term, schedule(), times,
            LocalDate.of(2027, 9, 9), // 超出 20 周
        )
        assertEquals("2026-2027-1", snapshot.termName)
        assertNull(snapshot.weekNumber)
        assertTrue(snapshot.blocks.isEmpty())
    }

    @Test
    fun `节次表残缺 - 对应 block 跳过不崩`() {
        val today = LocalDate.of(2026, 9, 9)
        val schedule = schedule(
            ScheduleBlock(id = "b1", courseId = "c1", startWeek = 1, endWeek = 20, dayOfWeek = 3, startPeriod = 9, endPeriod = 10), // 节次表只有 1..3
        )
        val snapshot = assembleTodaySnapshot(term, schedule, times, today)
        assertTrue(snapshot.blocks.isEmpty())
        assertEquals(1, snapshot.weekNumber)
    }

    @Test
    fun `今天没课 - 空列表但保留周次`() {
        val snapshot = assembleTodaySnapshot(
            term,
            schedule(),
            times,
            LocalDate.of(2026, 9, 12), // 第 1 周周六
        )
        assertEquals(1, snapshot.weekNumber)
        assertTrue(snapshot.blocks.isEmpty())
        assertNull(snapshot.nextUp(0))
    }
}
