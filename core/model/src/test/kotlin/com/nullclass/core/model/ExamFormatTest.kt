package com.nullclass.core.model

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExamFormatTest {

    private val day = LocalDate.of(2026, 12, 20).toEpochDay()

    @Test
    fun `时间段只在起止都有时成立`() {
        val exam = Exam(
            dateEpochDay = day,
            startMinuteOfDay = 9 * 60,
            endMinuteOfDay = 11 * 60,
        )

        assertEquals("9:00-11:00", ExamFormat.timeRange(exam))
        assertNull(ExamFormat.timeRange(exam.copy(endMinuteOfDay = null)))
        assertNull(ExamFormat.timeRange(exam.copy(startMinuteOfDay = null)))
    }

    @Test
    fun `相对日期分档`() {
        assertEquals(ExamRelativeDay.Today, ExamRelativeDay.of(day, day))
        assertEquals(ExamRelativeDay.Tomorrow, ExamRelativeDay.of(day + 1, day))
        assertEquals(ExamRelativeDay.Yesterday, ExamRelativeDay.of(day - 1, day))
        assertEquals(ExamRelativeDay.Past, ExamRelativeDay.of(day - 2, day))
        assertEquals(ExamRelativeDay.Past, ExamRelativeDay.of(day - 30, day))
        assertEquals(ExamRelativeDay.InDays(3), ExamRelativeDay.of(day + 3, day))
    }
}
