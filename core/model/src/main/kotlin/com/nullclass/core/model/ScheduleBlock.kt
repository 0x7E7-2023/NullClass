package com.nullclass.core.model

/** 单双周模式。 */
enum class WeekType {
    /** 每周 */
    ALL,

    /** 单周 */
    ODD,

    /** 双周 */
    EVEN,
}

/**
 * 课程的一个时间安排：第 [startWeek]..[endWeek] 周内（按 [weekType] 过滤）、
 * 周第 [dayOfWeek] 天的第 [startPeriod]..[endPeriod] 节，在 [location] 上课。
 *
 * 连堂大课 = 跨多节的安排（如 3..4 节），由 [SectionMath] 提供大节换算。
 */
data class ScheduleBlock(
    val id: String = "",
    val courseId: String = "",
    val startWeek: Int,
    val endWeek: Int,
    val weekType: WeekType = WeekType.ALL,
    /** 1..7，1 = 周一 */
    val dayOfWeek: Int,
    /** 节次从 1 开始 */
    val startPeriod: Int,
    val endPeriod: Int,
    val location: String? = null,
) {
    init {
        require(startWeek in 1..25 && endWeek in 1..25 && startWeek <= endWeek) {
            "invalid week range: $startWeek..$endWeek"
        }
        require(dayOfWeek in 1..7) { "dayOfWeek must be in 1..7, was $dayOfWeek" }
        require(startPeriod >= 1 && startPeriod <= endPeriod) {
            "invalid period range: $startPeriod..$endPeriod"
        }
    }

    /** 第 [week] 周是否有这节课。 */
    fun occursInWeek(week: Int): Boolean = week in startWeek..endWeek &&
        when (weekType) {
            WeekType.ALL -> true
            WeekType.ODD -> week % 2 == 1
            WeekType.EVEN -> week % 2 == 0
        }

    /** 跨越的节数（含首尾）。大课连堂通常为 2。 */
    val periodCount: Int get() = endPeriod - startPeriod + 1
}
