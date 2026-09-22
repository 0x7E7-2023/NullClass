package com.nullclass.core.model

import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EventReminderPlannerTest {

    private val zone = ZoneOffset.ofHours(8)
    private val today = LocalDate.of(2026, 9, 22)
    private fun millis(day: LocalDate, minute: Int) =
        day.atStartOfDay(zone).toInstant().toEpochMilli() + minute * 60_000L

    private fun event(id: String, day: LocalDate, start: Int?, lead: Int?) = CalendarEvent(
        id = id, title = id, dateEpochDay = day.toEpochDay(), startMinuteOfDay = start, remindLeadMinutes = lead,
    )

    @Test
    fun `排算提醒时刻、全天基准与过滤`() {
        val now = millis(today, 10 * 60)
        val planned = EventReminderPlanner.upcoming(
            listOf(
                event("timed", today, 14 * 60, 30),
                event("allDay", today.plusDays(1), null, 0),
                event("noRemind", today, 15 * 60, null),
                event("started", today, 9 * 60, 10),
                event("past", today.minusDays(1), 12 * 60, 10),
                event("far", today.plusDays(61), 12 * 60, 10),
            ),
            fromMillis = now,
            zone = zone,
        )
        assertEquals(listOf("timed", "allDay"), planned.map { it.event.id })
        assertEquals(millis(today, 14 * 60 - 30), planned[0].remindAtMillis)
        assertEquals(millis(today.plusDays(1), 8 * 60), planned[1].remindAtMillis)
    }

    @Test
    fun `提醒时刻已过但事件未开始仍保留（立即补发）`() {
        val now = millis(today, 13 * 60 + 50)
        val planned = EventReminderPlanner.upcoming(listOf(event("e", today, 14 * 60, 30)), now, zone = zone)
        assertEquals(1, planned.size)
        assertTrue(planned[0].remindAtMillis < now)
        assertTrue(EventReminderPlanner.reminderTag(planned[0]).endsWith(":${planned[0].remindAtMillis}"))
    }
}
