package com.nullclass.core.model

/**
 * 一节课的起止时间（一天中的分钟数）。
 * 每个学期可以自定义节次数量与时间（各校差异大，这是基础设施）。
 */
data class PeriodTime(
    /** 所属学期 */
    val termId: Long,
    /** 第几节，从 1 开始，1-based 连续 */
    val periodIndex: Int,
    /** 0..1439，例 8:00 = 480 */
    val startMinuteOfDay: Int,
    val endMinuteOfDay: Int,
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
