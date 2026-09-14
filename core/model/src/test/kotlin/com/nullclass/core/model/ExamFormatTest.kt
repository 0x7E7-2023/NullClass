package com.nullclass.core.model

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExamFormatTest {

    private val day = LocalDate.of(2026, 12, 20).toEpochDay()

    @Test
    fun `日期和时间文案`() {
        val exam = Exam(
            dateEpochDay = day,
            startMinuteOfDay = 9 * 60,
            endMinuteOfDay = 11 * 60,
        )

        assertEquals("2026年12月20日", ExamFormat.dateLabel(day))
        assertEquals("12月20日", ExamFormat.monthDayLabel(day))
        assertEquals("9:00-11:00", ExamFormat.timeRange(exam))
        assertNull(ExamFormat.timeRange(exam.copy(endMinuteOfDay = null)))
    }

    @Test
    fun `相对日期文案`() {
        assertEquals("今天", ExamFormat.relativeLabel(day, day))
        assertEquals("明天", ExamFormat.relativeLabel(day + 1, day))
        assertEquals("昨天", ExamFormat.relativeLabel(day - 1, day))
        assertEquals("已结束", ExamFormat.relativeLabel(day - 2, day))
        assertEquals("3 天后", ExamFormat.relativeLabel(day + 3, day))
    }
}
