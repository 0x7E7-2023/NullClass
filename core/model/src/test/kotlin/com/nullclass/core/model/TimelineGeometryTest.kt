package com.nullclass.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TimelineGeometryTest {

    private val periods = DefaultPeriodTimes.create("t")

    private fun block(start: Int, end: Int) =
        ScheduleBlock(startWeek = 1, endWeek = 16, dayOfWeek = 1, startPeriod = start, endPeriod = end)

    @Test
    fun `起止取首节开始与末节结束，课间空档保留`() {
        // 默认模板：第 1 节 8:00-8:45，第 2 节 8:55-9:40
        assertEquals(MinuteSpan(8 * 60, 9 * 60 + 40), TimelineGeometry.minuteSpan(block(1, 2), periods))
        assertEquals(MinuteSpan(8 * 60 + 55, 9 * 60 + 40), TimelineGeometry.minuteSpan(block(2, 2), periods))
    }

    @Test
    fun `首节不在节次表里时不画`() {
        assertNull(TimelineGeometry.minuteSpan(block(13, 14), periods))
        assertNull(TimelineGeometry.minuteSpan(block(1, 2), emptyList()))
    }

    @Test
    fun `末节超出节次表时截到最后一节`() {
        // 第 11 节 20:20 开始，表里最后一节（第 12 节）22:00 结束
        val span = TimelineGeometry.minuteSpan(block(11, 14), periods)
        assertEquals(MinuteSpan(20 * 60 + 20, 20 * 60 + 120), span)
    }

    @Test
    fun `节次时间填反时补足最短时长，不画出零高或负高`() {
        val reversed = listOf(
            PeriodTime(periodIndex = 1, startMinuteOfDay = 600, endMinuteOfDay = 645),
            PeriodTime(periodIndex = 2, startMinuteOfDay = 480, endMinuteOfDay = 525),
        )
        val span = TimelineGeometry.minuteSpan(block(1, 2), reversed)!!
        assertEquals(600, span.startMinute)
        assertEquals(TimelineGeometry.MIN_SPAN_MINUTES, span.lengthMinutes)
    }

    @Test
    fun `贴着午夜的填反时段往前挪，不越过 24 点`() {
        val late = listOf(
            PeriodTime(periodIndex = 1, startMinuteOfDay = 1430, endMinuteOfDay = 1440),
            PeriodTime(periodIndex = 2, startMinuteOfDay = 60, endMinuteOfDay = 90),
        )
        val span = TimelineGeometry.minuteSpan(block(1, 2), late)!!
        assertEquals(MINUTES_PER_DAY, span.endMinute)
        assertEquals(TimelineGeometry.MIN_SPAN_MINUTES, span.lengthMinutes)
    }

    @Test
    fun `初始滚动位置在最早一节前 30 分钟`() {
        assertEquals(7 * 60 + 30, TimelineGeometry.initialScrollMinute(periods))
        assertEquals(0, TimelineGeometry.initialScrollMinute(listOf(PeriodTime(periodIndex = 1, startMinuteOfDay = 10, endMinuteOfDay = 50))))
        assertEquals(7 * 60 + 30, TimelineGeometry.initialScrollMinute(emptyList()))
    }
}
