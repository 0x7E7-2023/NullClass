package com.nullclass.core.model

/** 上午 / 下午 / 晚上会话分组，驱动周视图左列的分组分隔线。 */
object Session {
    const val MORNING = 0
    const val AFTERNOON = 1
    const val EVENING = 2
}

/**
 * 一节课的起止时间（一天中的分钟数）。
 * 每个学期可以自定义节次数量与时间（各校差异大，这是基础设施）。
 */
data class PeriodTime(
    val termId: String = "",
    /** 第几节，从 1 开始，1-based 连续 */
    val periodIndex: Int,
    /** 0..1439，例 8:00 = 480 */
    val startMinuteOfDay: Int,
    val endMinuteOfDay: Int,
    /** 见 [Session]；同会话的节次在周视图左列划为一组 */
    val session: Int = Session.MORNING,
) {
    init {
        require(periodIndex >= 1) { "periodIndex must be >= 1, was $periodIndex" }
        require(startMinuteOfDay in 0..1439 && endMinuteOfDay in 1..1440) {
            "invalid time: $startMinuteOfDay..$endMinuteOfDay"
        }
        require(startMinuteOfDay < endMinuteOfDay) {
            "start must be before end: $startMinuteOfDay..$endMinuteOfDay"
        }
    }
}
