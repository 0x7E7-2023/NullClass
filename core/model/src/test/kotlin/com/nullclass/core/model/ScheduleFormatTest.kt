package com.nullclass.core.model

import kotlin.test.Test
import kotlin.test.assertEquals

class ScheduleFormatTest {

    @Test
    fun `分钟数补零到两位`() {
        assertEquals("8:00", ScheduleFormat.minuteLabel(8 * 60))
        assertEquals("14:05", ScheduleFormat.minuteLabel(14 * 60 + 5))
        assertEquals("0:00", ScheduleFormat.minuteLabel(0))
        assertEquals("23:59", ScheduleFormat.minuteLabel(23 * 60 + 59))
    }

    // 周次、星期、节次等带文字的格式化已移到 :core:ui 的 ScheduleText（从字符串资源取），
    // 不再在本模块测试。
}
