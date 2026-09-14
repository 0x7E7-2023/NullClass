package com.nullclass.core.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 一场考试的一次通知排程。日期不带具体时刻的考试以当天 08:00 作为计算基准。 */
data class PlannedExamReminder(
    val exam: ExamWithCourse,
    /** 有具体时间时是考试开始时间；只有日期时是当天 08:00。 */
    val examAtMillis: Long,
    val remindAtMillis: Long,
) {
    val isDateOnly: Boolean
        get() = exam.exam.startMinuteOfDay == null || exam.exam.endMinuteOfDay == null
}

/** 考试通知排算纯函数，和 Android/WorkManager 解耦，便于测试时间边界。 */
object ExamReminderPlanner {

    /** 没填考试时间时，用本地考试日 08:00 作为提醒基准。 */
    const val DATE_ONLY_BASE_MINUTE = 8 * 60

    /** 每次重排向未来展开的天数；每日维护任务会把窗口持续向前推进。 */
    const val DEFAULT_HORIZON_DAYS = 60

    fun upcoming(
        exams: List<ExamWithCourse>,
        fromMillis: Long,
        leadMinutes: Int,
        horizonDays: Int = DEFAULT_HORIZON_DAYS,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<PlannedExamReminder> {
        require(leadMinutes >= 0) { "leadMinutes must be >= 0" }
        require(horizonDays >= 0) { "horizonDays must be >= 0" }
        if (leadMinutes == 0 || exams.isEmpty()) return emptyList()

        val fromDay = Instant.ofEpochMilli(fromMillis).atZone(zone).toLocalDate()
        val examsByDay = exams.groupBy { it.exam.dateEpochDay }
        val result = mutableListOf<PlannedExamReminder>()

        for (dayOffset in 0..horizonDays) {
            val day = fromDay.plusDays(dayOffset.toLong())
            val dayExams = examsByDay[day.toEpochDay()] ?: continue
            val dayStartMillis = day.atStartOfDay(zone).toInstant().toEpochMilli()

            dayExams.forEach { examWithCourse ->
                val exam = examWithCourse.exam
                val startMinute = exam.startMinuteOfDay
                    ?.takeIf {
                        it in 0 until 24 * 60 &&
                            exam.endMinuteOfDay?.let { end -> end in 0 until 24 * 60 } == true
                    }
                    ?: DATE_ONLY_BASE_MINUTE
                val examAtMillis = dayStartMillis + startMinute * 60_000L
                // 考试已经开始（或日期提醒基准已过）时不再补一条通知。
                if (examAtMillis <= fromMillis) return@forEach

                result += PlannedExamReminder(
                    exam = examWithCourse,
                    examAtMillis = examAtMillis,
                    remindAtMillis = examAtMillis - leadMinutes * 60_000L,
                )
            }
        }
        return result.sortedWith(compareBy<PlannedExamReminder> { it.remindAtMillis }.thenBy { it.exam.exam.id })
    }

    /** 通知键同时用于 WorkManager 唯一任务和已发送记录，最后一段始终是时间戳。 */
    fun reminderTag(examId: String, remindAtMillis: Long): String =
        "exam:$examId:$remindAtMillis"

    fun reminderTag(planned: PlannedExamReminder): String =
        reminderTag(planned.exam.exam.id, planned.remindAtMillis)

    fun uniqueWorkName(examId: String, remindAtMillis: Long): String =
        "exam_reminder_${examId}_$remindAtMillis"
}
