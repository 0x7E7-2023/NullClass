package com.nullclass.widget

import com.nullclass.core.model.Course
import com.nullclass.core.model.CourseWithBlocks
import com.nullclass.core.model.PeriodTime
import com.nullclass.core.model.ScheduleBlock
import com.nullclass.core.model.Session
import com.nullclass.core.model.Term
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TodaySnapshotTest {

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
