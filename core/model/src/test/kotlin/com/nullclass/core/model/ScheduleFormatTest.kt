package com.nullclass.core.model

import kotlin.test.Test
import kotlin.test.assertEquals

class ScheduleFormatTest {

    private fun block(startWeek: Int, endWeek: Int, weekType: WeekType = WeekType.ALL) = ScheduleBlock(
        startWeek = startWeek,
        endWeek = endWeek,
        weekType = weekType,
        dayOfWeek = 1,
        startPeriod = 1,
        endPeriod = 2,
    )

    @Test
    fun `紧凑周次标出范围与单双周`() {
        // 周视图灰块那一格只有几十 dp 宽，不能带「第」（会被省略号吃掉尾巴）
        assertEquals("1-16周", ScheduleFormat.weekSpanLabel(block(1, 16)))
        assertEquals("3周", ScheduleFormat.weekSpanLabel(block(3, 3)))
        assertEquals("1-16周·单", ScheduleFormat.weekSpanLabel(block(1, 16, WeekType.ODD)))
        assertEquals("2-6周·双", ScheduleFormat.weekSpanLabel(block(2, 6, WeekType.EVEN)))
    }
}
