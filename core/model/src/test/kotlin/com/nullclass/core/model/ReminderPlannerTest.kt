package com.nullclass.core.model

import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReminderPlannerTest {

    // 固定时区与学期：2026-09-07（周一）开学，20 周
    private val zone = ZoneId.of("Asia/Shanghai")
    private val term = Term(
        id = "t1",
        name = "2026-2027-1",
        firstDayEpochDay = LocalDate.of(2026, 9, 7).toEpochDay(),
        totalWeeks = 20,
    )
    private val times = listOf(
        PeriodTime("t1", 1, 8 * 60, 8 * 60 + 45),
        PeriodTime("t1", 2, 8 * 60 + 55, 8 * 60 + 100),
        PeriodTime("t1", 3, 10 * 60, 10 * 60 + 45),
        PeriodTime("t1", 4, 10 * 60 + 55, 10 * 60 + 100),
    )

    private fun course(vararg blocks: ScheduleBlock) =
        CourseWithBlocks(Course(id = "c1", termId = "t1", name = "高数"), blocks.toList())

    private fun mondayMillis(week: Int, hour: Int = 0, minute: Int = 0): Long =
        LocalDate.of(2026, 9, 7).plusWeeks((week - 1).toLong())
            .atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `单双周过滤 - ODD 周的课在 EVEN 周不出现`() {
        val schedule = listOf(
            course(ScheduleBlock(id = "b1", courseId = "c1", startWeek = 1, endWeek = 20, weekType = WeekType.ODD, dayOfWeek = 1, startPeriod = 1, endPeriod = 2)),
        )
        // 第 1 周（单）周一起点排 14 天：覆盖第 1、2、3 周的周一
        val out = ReminderPlanner.upcoming(term, schedule, times, mondayMillis(1), horizonDays = 14, zone = zone)
        val days = out.map { java.time.Instant.ofEpochMilli(it.startAtMillis).atZone(zone).toLocalDate() }
        assertEquals(
            listOf(
                LocalDate.of(2026, 9, 7),   // 第 1 周（单）
                LocalDate.of(2026, 9, 21),  // 第 3 周（单）
            ),
            days,
        )
    }

    @Test
    fun `fromMillis 当天 - 已结束的不出现，进行中与未出现的要出现`() {
        val schedule = listOf(
            course(
                ScheduleBlock(id = "b1", courseId = "c1", startWeek = 1, endWeek = 20, dayOfWeek = 1, startPeriod = 1, endPeriod = 2), // 8:00-9:40
                ScheduleBlock(id = "b2", courseId = "c1", startWeek = 1, endWeek = 20, dayOfWeek = 1, startPeriod = 3, endPeriod = 4), // 10:00-11:40
            ),
        )
        // 第 1 周周一 9:00：第一门已开始未结束（进行中），第二门未开始
        val from = mondayMillis(1, 9, 0)
        val out = ReminderPlanner.upcoming(term, schedule, times, from, horizonDays = 0, zone = zone)
        assertEquals(2, out.size)
        // 同一时刻再排一次，9:40 结束之后：第一门已结束
        val from2 = mondayMillis(1, 9, 41)
        val out2 = ReminderPlanner.upcoming(term, schedule, times, from2, horizonDays = 0, zone = zone)
        assertEquals(1, out2.size)
    }

    @Test
    fun `节次表缺失对应行 - 该 block 静默跳过不崩`() {
        val schedule = listOf(
            course(
                ScheduleBlock(id = "b1", courseId = "c1", startWeek = 1, endWeek = 20, dayOfWeek = 1, startPeriod = 5, endPeriod = 6), // 节次表只有 1..4
            ),
        )
        val out = ReminderPlanner.upcoming(term, schedule, times, mondayMillis(1), horizonDays = 7, zone = zone)
        assertTrue(out.isEmpty())
    }

    @Test
    fun `学期边界 - 开学前与超 totalWeeks 的日期跳过`() {
        val schedule = listOf(
            course(ScheduleBlock(id = "b1", courseId = "c1", startWeek = 1, endWeek = 20, dayOfWeek = 1, startPeriod = 1, endPeriod = 2)),
        )
        // 开学前一天（第 0 天）：无输出
        val before = mondayMillis(1).let { it - 24L * 3600 * 1000 }
        assertTrue(ReminderPlanner.upcoming(term, schedule, times, before, horizonDays = 0, zone = zone).isEmpty())
        // 第 20 周周一（最后一天内的最后一周）：有输出；第 20 周末之后：无
        val week20 = mondayMillis(20)
        assertEquals(1, ReminderPlanner.upcoming(term, schedule, times, week20, horizonDays = 6, zone = zone).size)
        val after = mondayMillis(20) + 8L * 24 * 3600 * 1000
        assertTrue(ReminderPlanner.upcoming(term, schedule, times, after, horizonDays = 7, zone = zone).isEmpty())
    }

    @Test
    fun `跨日排序 - 多天多课按 startAt 升序`() {
        val schedule = listOf(
            course(
                ScheduleBlock(id = "b1", courseId = "c1", startWeek = 1, endWeek = 20, dayOfWeek = 2, startPeriod = 3, endPeriod = 3), // 周二 10:00
                ScheduleBlock(id = "b2", courseId = "c1", startWeek = 1, endWeek = 20, dayOfWeek = 1, startPeriod = 4, endPeriod = 4), // 周一 10:55
            ),
        )
        val out = ReminderPlanner.upcoming(term, schedule, times, mondayMillis(1), horizonDays = 6, zone = zone)
        assertEquals(listOf("b2", "b1"), out.map { it.block.id })
    }

    @Test
    fun `同时刻两门课都保留`() {
        val schedule = listOf(
            CourseWithBlocks(
                Course(id = "c1", termId = "t1", name = "高数"),
                listOf(ScheduleBlock(id = "b1", courseId = "c1", startWeek = 1, endWeek = 20, dayOfWeek = 1, startPeriod = 1, endPeriod = 2)),
            ),
            CourseWithBlocks(
                Course(id = "c2", termId = "t1", name = "英语"),
                listOf(ScheduleBlock(id = "b2", courseId = "c2", startWeek = 1, endWeek = 20, dayOfWeek = 1, startPeriod = 1, endPeriod = 2)),
            ),
        )
        val out = ReminderPlanner.upcoming(term, schedule, times, mondayMillis(1), horizonDays = 0, zone = zone)
        assertEquals(2, out.size)
        assertEquals(out[0].startAtMillis, out[1].startAtMillis)
    }

    @Test
    fun `horizonDays 截断 - 只看指定天数`() {
        val schedule = listOf(
            course(ScheduleBlock(id = "b1", courseId = "c1", startWeek = 1, endWeek = 20, dayOfWeek = 1, startPeriod = 1, endPeriod = 2)),
        )
        // horizon=0 只看今天；horizon=7 覆盖下周一
        assertEquals(1, ReminderPlanner.upcoming(term, schedule, times, mondayMillis(1), horizonDays = 0, zone = zone).size)
        assertEquals(2, ReminderPlanner.upcoming(term, schedule, times, mondayMillis(1), horizonDays = 7, zone = zone).size)
    }

    @Test
    fun `时刻换算 - startAt 等于当天零点加 startMinuteOfDay`() {
        val schedule = listOf(
            course(ScheduleBlock(id = "b1", courseId = "c1", startWeek = 1, endWeek = 20, dayOfWeek = 1, startPeriod = 3, endPeriod = 4)),
        )
        val out = ReminderPlanner.upcoming(term, schedule, times, mondayMillis(1), horizonDays = 0, zone = zone)
        assertEquals(mondayMillis(1, 10, 0), out[0].startAtMillis)
        assertEquals(mondayMillis(1, 11, 40), out[0].endAtMillis)
    }

    @Test
    fun `迟发判定 - 已开课未下课且未发过才补`() {
        val schedule = listOf(
            course(ScheduleBlock(id = "b1", courseId = "c1", startWeek = 1, endWeek = 20, dayOfWeek = 1, startPeriod = 1, endPeriod = 2)), // 8:00-9:40
        )
        val upcoming = ReminderPlanner.upcoming(term, schedule, times, mondayMillis(1, 8, 0), horizonDays = 0, zone = zone)[0]
        val tag = ReminderPlanner.reminderTag(upcoming)

        // 8:20 进行中、未发过 → 补
        assertTrue(ReminderPlanner.shouldSendLate(upcoming, mondayMillis(1, 8, 20), emptySet()))
        // 未开始（还在提前量窗口内，remindAt 已过但课没开）→ 不补
        assertTrue(!ReminderPlanner.shouldSendLate(upcoming, mondayMillis(1, 7, 50), emptySet()))
        // 已下课 → 不补
        assertTrue(!ReminderPlanner.shouldSendLate(upcoming, mondayMillis(1, 10, 0), emptySet()))
        // 开课超过 30 分钟 → 不补
        assertTrue(!ReminderPlanner.shouldSendLate(upcoming, mondayMillis(1, 8, 31), emptySet()))
        // 已发过（幂等）→ 不补
        assertTrue(!ReminderPlanner.shouldSendLate(upcoming, mondayMillis(1, 8, 20), setOf(tag)))
    }

    @Test
    fun `已发送键容差匹配 - 时区切换后同一节课不算漏发`() {
        val schedule = listOf(
            course(ScheduleBlock(id = "b1", courseId = "c1", startWeek = 1, endWeek = 20, dayOfWeek = 1, startPeriod = 1, endPeriod = 2)), // 8:00-9:40
        )
        val upcoming = ReminderPlanner.upcoming(term, schedule, times, mondayMillis(1, 8, 0), horizonDays = 0, zone = zone)[0]
        val tag = ReminderPlanner.reminderTag(upcoming)

        // 时区东行 8h：同一节课新算出的 startAt epoch 早 8 小时
        val shifted = upcoming.copy(
            startAtMillis = upcoming.startAtMillis - 8 * 3600_000L,
            endAtMillis = upcoming.endAtMillis - 8 * 3600_000L,
        )
        assertTrue(ReminderPlanner.isAlreadySent(shifted, setOf(tag)))
        // 换算后的「已开课 20 分钟」也不补（否则就是重复的「已开始」）
        assertTrue(
            !ReminderPlanner.shouldSendLate(shifted, mondayMillis(1, 8, 20) - 8 * 3600_000L, setOf(tag)),
        )

        // 上周的同一 block（相差整周）不算已发——每周才一次课，容差不该跨周
        val lastWeek = upcoming.copy(
            startAtMillis = upcoming.startAtMillis - 7L * 24 * 3600 * 1000,
            endAtMillis = upcoming.endAtMillis - 7L * 24 * 3600 * 1000,
        )
        assertTrue(!ReminderPlanner.isAlreadySent(lastWeek, setOf(tag)))

        // 同一时刻的别的 block 不算已发
        val otherBlock = upcoming.copy(block = upcoming.block.copy(id = "b2"))
        assertTrue(!ReminderPlanner.isAlreadySent(otherBlock, setOf(tag)))
    }
}
