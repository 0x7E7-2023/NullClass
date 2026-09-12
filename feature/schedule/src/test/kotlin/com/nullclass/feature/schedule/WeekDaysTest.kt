package com.nullclass.feature.schedule

import com.nullclass.core.model.Term
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

/** 周视图要画哪几列、按什么顺序（表头与网格共用同一份，见 [visibleWeekDays]）。 */
class WeekDaysTest {

    private fun term(firstDay: String) = Term(
        id = "t",
        name = "2026-2027-1",
        firstDayEpochDay = LocalDate.parse(firstDay).toEpochDay(),
        totalWeeks = 20,
    )

    @Test
    fun `列序随每周起始日`() {
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7), term("2026-09-07").visibleWeekDays(true))
        assertEquals(listOf(7, 1, 2, 3, 4, 5, 6), term("2026-09-06").visibleWeekDays(true))
        assertEquals(listOf(3, 4, 5, 6, 7, 1, 2), term("2026-09-09").visibleWeekDays(true))
    }

    @Test
    fun `关掉周末是去掉周六周日，不是砍掉最后两列`() {
        // 周一开学：末尾两列正好是周末，去掉后还是一~五
        assertEquals(listOf(1, 2, 3, 4, 5), term("2026-09-07").visibleWeekDays(false))
        // 周日开学：列序是日一二三四五六，去掉的其实是首列（周日）与末列（周六），仍是一~五
        assertEquals(listOf(1, 2, 3, 4, 5), term("2026-09-06").visibleWeekDays(false))
        // 周三开学：列序是三四五六日一二，去掉六日 → 三四五一二（剩下的仍是学期自己的周序）
        assertEquals(listOf(3, 4, 5, 1, 2), term("2026-09-09").visibleWeekDays(false))
    }
}
