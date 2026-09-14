package com.nullclass.core.model

import java.time.LocalDate

/** 考试在多个界面共用的日期、时间文案。 */
object ExamFormat {

    fun dateLabel(epochDay: Long): String {
        val date = LocalDate.ofEpochDay(epochDay)
        return "${date.year}年${date.monthValue}月${date.dayOfMonth}日"
    }

    fun monthDayLabel(epochDay: Long): String {
        val date = LocalDate.ofEpochDay(epochDay)
        return "${date.monthValue}月${date.dayOfMonth}日"
    }

    fun timeRange(exam: Exam): String? = when {
        exam.startMinuteOfDay != null && exam.endMinuteOfDay != null ->
            "${ScheduleFormat.minuteLabel(exam.startMinuteOfDay)}-${ScheduleFormat.minuteLabel(exam.endMinuteOfDay)}"
        else -> null
    }

    fun relativeLabel(examEpochDay: Long, todayEpochDay: Long): String = when (examEpochDay - todayEpochDay) {
        0L -> "今天"
        1L -> "明天"
        -1L -> "昨天"
        in Long.MIN_VALUE..-2L -> "已结束"
        else -> "${examEpochDay - todayEpochDay} 天后"
    }
}
