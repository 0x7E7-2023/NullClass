package com.nullclass.core.model

/**
 * 学期。课表以学期为单位组织，同一时刻只有一个「当前学期」。
 */
data class Term(
    val id: Long = 0L,
    /** 例："2026-2027-1" */
    val name: String,
    /** 开学第一周周一的日期（epoch day，LocalDate.toEpochDay()） */
    val firstDayEpochDay: Long,
    /** 学期总周数，1..25 */
    val totalWeeks: Int,
) {
    init {
        require(totalWeeks in 1..25) { "totalWeeks must be in 1..25, was $totalWeeks" }
    }

    /**
     * 计算某个日期属于第几周（以周一为每周第一天）。
     * @return 1-based 周次；不在学期内返回 null
     */
    fun weekOf(epochDay: Long): Int? {
        val week = ((epochDay - firstDayEpochDay) / 7 + 1).toInt()
        return if (week in 1..totalWeeks) week else null
    }
}
