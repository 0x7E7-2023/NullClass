package com.nullclass.feature.settings.transfer

import com.nullclass.core.model.Course
import com.nullclass.core.model.CourseWithBlocks
import com.nullclass.core.model.Exam
import com.nullclass.core.model.ExamWithCourse
import com.nullclass.core.model.PeriodTime
import com.nullclass.core.model.ScheduleBlock
import com.nullclass.core.model.Term
import com.nullclass.core.model.WeekType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 文案取真实 strings.xml（经 Robolectric），模板写坏会在这里暴露。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "zh-rCN")
class IcsCalendarTest {

    private val term = Term(
        id = "term-1",
        name = "2026-2027-1",
        firstDayEpochDay = LocalDate.of(2026, 9, 7).toEpochDay(),
        totalWeeks = 4,
    )

    private val periodTimes = listOf(
        PeriodTime(
            termId = term.id,
            periodIndex = 1,
            startMinuteOfDay = 8 * 60,
            endMinuteOfDay = 8 * 60 + 45,
        ),
        PeriodTime(
            termId = term.id,
            periodIndex = 2,
            startMinuteOfDay = 8 * 60 + 55,
            endMinuteOfDay = 9 * 60 + 40,
        ),
    )

    @Test
    fun `普通每周安排导出为重复日历事件并转义文本`() {
        val course = Course(
            id = "course-1",
            termId = term.id,
            name = "高等数学, 线性;代数",
            teacher = "张老师",
        )
        val block = ScheduleBlock(
            id = "block-1",
            courseId = course.id,
            startWeek = 1,
            endWeek = 2,
            dayOfWeek = 1,
            startPeriod = 1,
            endPeriod = 2,
            location = "A;101",
        )

        val result = IcsCalendar.build(
            text = IcsText.from(RuntimeEnvironment.getApplication()),
            term = term,
            schedule = listOf(CourseWithBlocks(course, listOf(block))),
            periodTimes = periodTimes,
            zoneId = ZoneId.of("Asia/Shanghai"),
            stamp = Instant.parse("2026-09-01T00:00:00Z"),
        )

        assertEquals(1, result.eventCount)
        assertEquals(0, result.skippedBlockCount)
        assertContains(result.content, "BEGIN:VCALENDAR\r\n")
        assertContains(result.content, "X-WR-CALNAME:空课 · 2026-2027-1\r\n")
        assertContains(result.content, "SUMMARY:高等数学\\, 线性\\;代数\r\n")
        assertContains(result.content, "LOCATION:A\\;101\r\n")
        assertContains(result.content, "DTSTART:20260907T000000Z\r\n")
        assertContains(result.content, "DTEND:20260907T014000Z\r\n")
        assertContains(result.content, "RRULE:FREQ=WEEKLY;COUNT=2\r\n")
        assertTrue(result.content.endsWith("END:VCALENDAR\r\n"))
    }

    @Test
    fun `单双周从第一个匹配周开始并每两周重复`() {
        val course = Course(id = "course-1", termId = term.id, name = "英语")
        val odd = ScheduleBlock(
            id = "odd",
            courseId = course.id,
            startWeek = 1,
            endWeek = 4,
            dayOfWeek = 1,
            startPeriod = 1,
            endPeriod = 1,
            weekType = WeekType.ODD,
        )
        val even = ScheduleBlock(
            id = "even",
            courseId = course.id,
            startWeek = 1,
            endWeek = 4,
            dayOfWeek = 1,
            startPeriod = 1,
            endPeriod = 1,
            weekType = WeekType.EVEN,
        )

        val result = IcsCalendar.build(
            text = IcsText.from(RuntimeEnvironment.getApplication()),
            term = term,
            schedule = listOf(CourseWithBlocks(course, listOf(odd, even))),
            periodTimes = periodTimes,
            zoneId = ZoneId.of("Asia/Shanghai"),
            stamp = Instant.parse("2026-09-01T00:00:00Z"),
        )

        assertEquals(2, result.eventCount)
        assertContains(result.content, "DTSTART:20260907T000000Z\r\n")
        assertContains(result.content, "DTSTART:20260914T000000Z\r\n")
        assertEquals(2, result.content.split("RRULE:FREQ=WEEKLY;INTERVAL=2;COUNT=2").size - 1)
    }

    @Test
    fun `缺少节次时间的安排不生成午夜事件`() {
        val course = Course(id = "course-1", termId = term.id, name = "实验课")
        val block = ScheduleBlock(
            id = "block-1",
            courseId = course.id,
            startWeek = 1,
            endWeek = 1,
            dayOfWeek = 1,
            startPeriod = 2,
            endPeriod = 2,
        )

        val result = IcsCalendar.build(
            text = IcsText.from(RuntimeEnvironment.getApplication()),
            term = term,
            schedule = listOf(CourseWithBlocks(course, listOf(block))),
            periodTimes = periodTimes.take(1),
            zoneId = ZoneId.of("Asia/Shanghai"),
            stamp = Instant.parse("2026-09-01T00:00:00Z"),
        )

        assertEquals(0, result.eventCount)
        assertEquals(1, result.skippedBlockCount)
        assertTrue(!result.content.contains("BEGIN:VEVENT"))
    }

    @Test
    fun `考试挂在课程下并导出为一次性事件`() {
        val course = Course(id = "course-1", termId = term.id, name = "高等数学")
        val exam = Exam(
            id = "exam-1",
            courseId = course.id,
            title = "期末考试",
            dateEpochDay = LocalDate.of(2026, 9, 28).toEpochDay(),
            startMinuteOfDay = 9 * 60,
            endMinuteOfDay = 11 * 60,
            location = "A101",
            seat = "12",
        )

        val result = IcsCalendar.build(
            text = IcsText.from(RuntimeEnvironment.getApplication()),
            term = term,
            schedule = emptyList(),
            periodTimes = emptyList(),
            exams = listOf(ExamWithCourse(exam, course)),
            zoneId = ZoneId.of("Asia/Shanghai"),
            stamp = Instant.parse("2026-09-01T00:00:00Z"),
        )

        assertEquals(0, result.courseEventCount)
        assertEquals(1, result.examEventCount)
        assertEquals(1, result.eventCount)
        assertContains(result.content, "SUMMARY:高等数学 · 期末考试\r\n")
        assertContains(result.content, "DTSTART:20260928T010000Z\r\n")
        assertContains(result.content, "DTEND:20260928T030000Z\r\n")
        assertContains(result.content, "LOCATION:A101\r\n")
        assertContains(result.content, "座位：12")
    }

    @Test
    fun `考试没有具体时间时导出为全天事件`() {
        val course = Course(id = "course-1", termId = term.id, name = "英语")
        val exam = Exam(
            id = "exam-1",
            courseId = course.id,
            title = "考试日期待定时间",
            dateEpochDay = LocalDate.of(2026, 9, 28).toEpochDay(),
        )

        val result = IcsCalendar.build(
            text = IcsText.from(RuntimeEnvironment.getApplication()),
            term = term,
            schedule = emptyList(),
            periodTimes = emptyList(),
            exams = listOf(ExamWithCourse(exam, course)),
        )

        assertContains(result.content, "DTSTART;VALUE=DATE:20260928\r\n")
        assertContains(result.content, "DTEND;VALUE=DATE:20260929\r\n")
    }
}
