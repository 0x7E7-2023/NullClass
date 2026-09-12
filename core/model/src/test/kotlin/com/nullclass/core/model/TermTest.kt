package com.nullclass.core.model

import java.time.LocalDate
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TermTest {

    private fun term(firstDay: String, totalWeeks: Int = 20) = Term(
        id = "t",
        name = "2026-2027-1",
        firstDayEpochDay = LocalDate.parse(firstDay).toEpochDay(),
        totalWeeks = totalWeeks,
    )

    private fun dateOf(epochDay: Long) = LocalDate.ofEpochDay(epochDay)

    @Test
    fun `每周起始日就是第 1 周的星期几`() {
        assertEquals(1, term("2026-09-07").weekStartDay) // 周一
        assertEquals(7, term("2026-09-06").weekStartDay) // 周日
        assertEquals(3, term("2026-09-09").weekStartDay) // 周三
    }

    @Test
    fun `每周起始日与 java_time 算出的一致`() {
        // 模型里用 epoch day 算术推星期几（不引 java.time），别和日历算出两样
        var day = LocalDate.parse("2025-12-28")
        repeat(400) {
            val epoch = day.toEpochDay()
            val t = Term(id = "t", name = "n", firstDayEpochDay = epoch, totalWeeks = 1)
            assertEquals(day.dayOfWeek.value, t.weekStartDay, "epochDay=$epoch")
            day = day.plusDays(1)
        }
    }

    @Test
    fun `周一开学时行为与从前一致`() {
        val t = term("2026-09-07")

        assertEquals(1, t.weekOf(LocalDate.parse("2026-09-07").toEpochDay()))
        assertEquals(1, t.weekOf(LocalDate.parse("2026-09-13").toEpochDay()))
        assertEquals(2, t.weekOf(LocalDate.parse("2026-09-14").toEpochDay()))
        assertNull(t.weekOf(LocalDate.parse("2026-09-06").toEpochDay()))
        assertNull(t.weekOf(LocalDate.parse("2026-09-13").plusWeeks(20).toEpochDay()))

        assertEquals(LocalDate.parse("2026-09-07").toEpochDay(), t.epochDayOf(1, 1))
        assertEquals(LocalDate.parse("2026-09-13").toEpochDay(), t.epochDayOf(1, 7))
        assertEquals(LocalDate.parse("2026-09-16").toEpochDay(), t.epochDayOf(2, 3))
    }

    @Test
    fun `开学前一天不算第 1 周`() {
        // 整数除法向零取整：开学前 1~6 天会被算成第 1 周（今日页与提醒都会当真）
        val t = term("2026-09-07")
        val firstDay = LocalDate.parse("2026-09-07").toEpochDay()
        for (offset in 1L..6L) {
            assertNull(t.weekOf(firstDay - offset), "开学前 $offset 天")
        }
        // 学期最后一天还在，再往后一天不在
        val lastDay = firstDay + 20 * 7 - 1
        assertEquals(20, t.weekOf(lastDay))
        assertNull(t.weekOf(lastDay + 1))
    }

    @Test
    fun `周日开学时翻周发生在周日`() {
        val t = term("2026-09-06")

        assertEquals(1, t.weekOf(LocalDate.parse("2026-09-06").toEpochDay()))
        assertEquals(1, t.weekOf(LocalDate.parse("2026-09-12").toEpochDay())) // 周六，仍第 1 周
        assertEquals(2, t.weekOf(LocalDate.parse("2026-09-13").toEpochDay()))

        assertEquals(LocalDate.parse("2026-09-06").toEpochDay(), t.epochDayOf(1, 7)) // 周日 = 第 1 天
        assertEquals(LocalDate.parse("2026-09-07").toEpochDay(), t.epochDayOf(1, 1)) // 周一 = 第 2 天
        assertEquals(LocalDate.parse("2026-09-12").toEpochDay(), t.epochDayOf(1, 6)) // 周六 = 最后一天
        assertEquals(LocalDate.parse("2026-09-13").toEpochDay(), t.epochDayOf(2, 7))
    }

    @Test
    fun `任意起始日下，第 N 周第 K 天就是那个星期几、且仍算在第 N 周`() {
        // 两个函数是同一件事的两面：日期 ↔ (周次, 星期几) 必须互相对得上
        for (startDayOfWeek in 1..7) {
            val firstDay = (0..6).map { LocalDate.parse("2026-09-01").plusDays(it.toLong()) }
                .first { it.dayOfWeek.value == startDayOfWeek }
            val t = Term(
                id = "t",
                name = "n",
                firstDayEpochDay = firstDay.toEpochDay(),
                totalWeeks = 20,
            )

            assertEquals(startDayOfWeek, t.weekStartDay)
            assertEquals(firstDay.toEpochDay(), t.epochDayOf(1, startDayOfWeek))

            for (week in 1..20) {
                for (dayOfWeek in 1..7) {
                    val date = dateOf(t.epochDayOf(week, dayOfWeek))
                    assertEquals(dayOfWeek, date.dayOfWeek.value, "start=$startDayOfWeek week=$week")
                    assertEquals(week, t.weekOf(date.toEpochDay()), "start=$startDayOfWeek week=$week")
                }
            }
        }
    }

    @Test
    fun `列顺序从每周起始日排起`() {
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7), term("2026-09-07").daysInWeekOrder)
        assertEquals(listOf(7, 1, 2, 3, 4, 5, 6), term("2026-09-06").daysInWeekOrder)
        assertEquals(listOf(3, 4, 5, 6, 7, 1, 2), term("2026-09-09").daysInWeekOrder)
    }

    @Test
    fun `最近的星期几：挪动不超过 3 天，来回拨回到原处`() {
        val monday = LocalDate.parse("2026-09-07")

        // 周一改成周日算起 → 退到前一天（9月6日），而不是同 ISO 周里的 9月13日
        assertEquals(LocalDate.parse("2026-09-06"), dateOf(nearestWeekday(monday.toEpochDay(), 7)))

        for (dayOfWeek in 1..7) {
            val moved = nearestWeekday(monday.toEpochDay(), dayOfWeek)
            assertEquals(dayOfWeek, dateOf(moved).dayOfWeek.value)
            assertTrue(abs(moved - monday.toEpochDay()) <= 3, "挪太远：$dayOfWeek")
            // 拨回来必须回到原处，否则每切一次起始日整个学期就漂一周
            assertEquals(monday.toEpochDay(), nearestWeekday(moved, 1), "拨不回来：$dayOfWeek")
        }
    }
}
