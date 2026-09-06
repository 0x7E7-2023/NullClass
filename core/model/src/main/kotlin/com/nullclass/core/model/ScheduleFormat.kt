package com.nullclass.core.model

/** 课表展示用文本格式化（周视图、详情、编辑器、widget 共用口径）。 */
object ScheduleFormat {

    /** 0..1439 分钟数 → "8:00" / "14:05"。 */
    fun minuteLabel(minuteOfDay: Int): String {
        val hour = minuteOfDay / 60
        val minute = minuteOfDay % 60
        return "$hour:${minute.toString().padStart(2, '0')}"
    }

    /** "第1-16周"；恰好一周时 "第3周"。 */
    fun weekRange(block: ScheduleBlock): String =
        if (block.startWeek == block.endWeek) {
            "第${block.startWeek}周"
        } else {
            "第${block.startWeek}-${block.endWeek}周"
        }

    /** "" / "单周" / "双周"。 */
    fun weekTypeLabel(weekType: WeekType): String = when (weekType) {
        WeekType.ALL -> ""
        WeekType.ODD -> "单周"
        WeekType.EVEN -> "双周"
    }

    fun dayOfWeekLabel(dayOfWeek: Int): String =
        listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
            .getOrElse(dayOfWeek - 1) { "?" }

    /** "3-4节"；恰好一节时 "3节"。 */
    fun periodRange(block: ScheduleBlock): String =
        if (block.startPeriod == block.endPeriod) {
            "${block.startPeriod}节"
        } else {
            "${block.startPeriod}-${block.endPeriod}节"
        }

    /** 完整一行："第1-16周 · 单周 · 周二 · 3-4节 · A101"。 */
    fun blockSummary(block: ScheduleBlock): String = buildList {
        add(weekRange(block))
        weekTypeLabel(block.weekType).takeIf { it.isNotEmpty() }?.let { add(it) }
        add(dayOfWeekLabel(block.dayOfWeek))
        add(periodRange(block))
        block.location?.takeIf { it.isNotBlank() }?.let { add(it) }
    }.joinToString(" · ")
}
