package com.nullclass.core.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 日程安排：不挂课程、不挂学期的个人事件（类似日历事件），可单独设置提醒提前量。
 */
data class CalendarEvent(
    val id: String = "",
    val title: String = "",
    /** 日期（LocalDate.toEpochDay()）。 */
    val dateEpochDay: Long = 0L,
    /** null = 全天事件，提醒以当天 08:00 为基准（与考试口径一致）。 */
    val startMinuteOfDay: Int? = null,
    val note: String? = null,
    /** 提前多少分钟提醒；null = 不提醒，0 = 准时提醒。 */
    val remindLeadMinutes: Int? = null,
)

/** 一个日程的一次通知排程。 */
data class PlannedEventReminder(
    val event: CalendarEvent,
    val eventAtMillis: Long,
    val remindAtMillis: Long,
)

/** 日程提醒排算纯函数，与 Android/WorkManager 解耦。 */
object EventReminderPlanner {

    const val ALL_DAY_BASE_MINUTE = ExamReminderPlanner.DATE_ONLY_BASE_MINUTE

    const val DEFAULT_HORIZON_DAYS = ExamReminderPlanner.DEFAULT_HORIZON_DAYS

    fun upcoming(
        events: List<CalendarEvent>,
        fromMillis: Long,
        horizonDays: Int = DEFAULT_HORIZON_DAYS,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<PlannedEventReminder> {
        val fromDay = Instant.ofEpochMilli(fromMillis).atZone(zone).toLocalDate().toEpochDay()
        return events.mapNotNull { event ->
            val lead = event.remindLeadMinutes?.takeIf { it >= 0 } ?: return@mapNotNull null
            if (event.dateEpochDay - fromDay !in 0..horizonDays) return@mapNotNull null
            val minute = event.startMinuteOfDay?.takeIf { it in 0 until 24 * 60 } ?: ALL_DAY_BASE_MINUTE
            // atStartOfDay(zone) + 分钟偏移，与考试提醒同口径（夏令时当天可能差一小时，国内无夏令时）
            val eventAtMillis = LocalDate.ofEpochDay(event.dateEpochDay).atStartOfDay(zone)
                .toInstant().toEpochMilli() + minute * 60_000L
            // 事件已开始不再补发
            if (eventAtMillis <= fromMillis) return@mapNotNull null
            PlannedEventReminder(event, eventAtMillis, eventAtMillis - lead * 60_000L)
        }.sortedWith(compareBy<PlannedEventReminder> { it.remindAtMillis }.thenBy { it.event.id })
    }

    fun reminderTag(planned: PlannedEventReminder): String =
        "event:${planned.event.id}:${planned.remindAtMillis}"

    fun uniqueWorkName(planned: PlannedEventReminder): String =
        "event_reminder_${planned.event.id}_${planned.remindAtMillis}"

    /**
     * 定时日程的开始时刻（「9:00」）；全天日程返回 null，
     * 由界面层补上「全天」——那是文案，不该在这里写死。
     */
    fun timeLabel(event: CalendarEvent): String? =
        event.startMinuteOfDay?.let(ScheduleFormat::minuteLabel)
}
