package com.nullclass.core.model

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DayOverridesTest {

    // 2026-09-07 周一开学，20 周
    private val term = Term(
        id = "t1",
        name = "2026-2027-1",
        firstDayEpochDay = LocalDate.of(2026, 9, 7).toEpochDay(),
        totalWeeks = 20,
    )

    private fun day(month: Int, dayOfMonth: Int) = LocalDate.of(2026, month, dayOfMonth).toEpochDay()

    @Test
    fun `index - 自指的行丢掉`() {
        val index = DayOverrides.index(
            listOf(
                DayOverride(day(10, 11), day(10, 9)),
                DayOverride(day(10, 12), day(10, 12)),
            ),
        )
        assertEquals(mapOf(day(10, 11) to day(10, 9)), index)
    }

    @Test
    fun `sourceOf - 没串课就是它自己，串了取来源日`() {
        val index = mapOf(day(10, 11) to day(10, 9))
        assertEquals(day(10, 10), DayOverrides.sourceOf(index, day(10, 10)))
        assertEquals(day(10, 9), DayOverrides.sourceOf(index, day(10, 11)))
    }

    @Test
    fun `sourceOf - 只跟一跳，不递归`() {
        // A→B、B→C：A 拿的是 B 原本的课，不是 C 的
        val index = mapOf(
            day(10, 11) to day(10, 9),
            day(10, 9) to day(10, 5),
        )
        assertEquals(day(10, 9), DayOverrides.sourceOf(index, day(10, 11)))
    }

    @Test
    fun `originOf - 换算成第几周星期几`() {
        // 2026-10-09 是周五；9/7 开学 → 第 5 周
        val origin = DayOverrides.originOf(term, mapOf(day(10, 11) to day(10, 9)), day(10, 11))
        assertEquals(DayOrigin(week = 5, dayOfWeek = 5), origin)
    }

    @Test
    fun `originOf - 没串课时就是这天自己`() {
        val origin = DayOverrides.originOf(term, emptyMap(), day(10, 11))
        // 2026-10-11 周日，第 5 周
        assertEquals(DayOrigin(week = 5, dayOfWeek = 7), origin)
    }

    @Test
    fun `originOf - 来源日在学期外则没有课`() {
        val beforeTerm = LocalDate.of(2026, 8, 1).toEpochDay()
        assertNull(DayOverrides.originOf(term, mapOf(day(10, 11) to beforeTerm), day(10, 11)))
    }

    @Test
    fun `originsForWeek - 只列出被串过的那几列`() {
        // 第 5 周：10/5(一) .. 10/11(日)
        val index = mapOf(
            day(10, 10) to day(10, 5), // 周六上周一的课
            day(10, 11) to LocalDate.of(2026, 8, 1).toEpochDay(), // 周日串到了学期外
        )
        val origins = DayOverrides.originsForWeek(term, week = 5, index = index)
        assertEquals(setOf(6, 7), origins.keys)
        assertEquals(DayOrigin(week = 5, dayOfWeek = 1), origins[6])
        assertTrue(origins.containsKey(7))
        assertNull(origins[7])
    }

    @Test
    fun `originsForWeek - 别的周的串课不影响本周`() {
        val index = mapOf(day(10, 17) to day(10, 16)) // 第 6 周
        assertEquals(emptyMap(), DayOverrides.originsForWeek(term, week = 5, index = index))
    }

    @Test
    fun `originOf - 目标日在学期外时串课不生效`() {
        // 学期外那天本来就没课，串课不能凭空造出课来（否则假期里照排提醒）
        val afterTerm = LocalDate.of(2027, 3, 1).toEpochDay()
        assertNull(DayOverrides.originOf(term, mapOf(afterTerm to day(10, 9)), afterTerm))
    }
}
