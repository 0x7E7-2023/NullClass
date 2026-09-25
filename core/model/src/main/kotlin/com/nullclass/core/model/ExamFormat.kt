package com.nullclass.core.model

/**
 * 考试展示用的**与语言无关**的格式化。
 *
 * 日期文案与「今天/明天/几天后」的措辞由 `:core:ui` 的 `ExamText` 从字符串资源取；
 * 本模块只负责判断相对位置（[ExamRelativeDay]）与拼接纯数字的时间段。
 */
object ExamFormat {

    /** 「9:00-11:00」；起止时间不全时返回 null。纯数字，不随语言变化。 */
    fun timeRange(exam: Exam): String? {
        val start = exam.startMinuteOfDay
        val end = exam.endMinuteOfDay
        if (start == null || end == null) return null
        return "${ScheduleFormat.minuteLabel(start)}-${ScheduleFormat.minuteLabel(end)}"
    }
}

/**
 * 考试日期相对今天的位置。对应文案见 `:core:ui` 的 `ExamText.relative`。
 *
 * 只到「昨天」为止逐日区分，更早一律归入 [Past]：考试一旦过去两天，
 * 具体过去了几天对用户不再有意义。
 */
sealed interface ExamRelativeDay {

    data object Today : ExamRelativeDay

    data object Tomorrow : ExamRelativeDay

    data object Yesterday : ExamRelativeDay

    /** 两天以前。 */
    data object Past : ExamRelativeDay

    /** 两天以后，[days] 为相差天数。 */
    data class InDays(val days: Long) : ExamRelativeDay

    companion object {
        fun of(examEpochDay: Long, todayEpochDay: Long): ExamRelativeDay =
            when (val diff = examEpochDay - todayEpochDay) {
                0L -> Today
                1L -> Tomorrow
                -1L -> Yesterday
                in Long.MIN_VALUE..-2L -> Past
                else -> InDays(diff)
            }
    }
}
