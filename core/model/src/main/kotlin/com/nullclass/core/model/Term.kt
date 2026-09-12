package com.nullclass.core.model

/**
 * 学期。课表以学期为单位组织，同一时刻只有一个「当前学期」。
 *
 * id 为客户端生成的 UUID（同步就绪：多设备记录天然对齐）。
 */
data class Term(
    val id: String = "",
    /** 例："2026-2027-1" */
    val name: String,
    /** 第 1 周的第 1 天（epoch day，LocalDate.toEpochDay()） */
    val firstDayEpochDay: Long,
    /** 学期总周数，1..25 */
    val totalWeeks: Int,
) {
    init {
        require(totalWeeks in 1..25) { "totalWeeks must be in 1..25, was $totalWeeks" }
    }

    /**
     * 每周从周几算起（1 = 周一 .. 7 = 周日）。
     *
     * 不单独存字段：它就是 [firstDayEpochDay] 的星期几 —— 「一周从哪天算起」与
     * 「第 1 周从哪天开始」本来就是同一件事，存两份迟早对不上（存了就可能出现
     * 「日期是周一、却说一周从周日算起」这种自相矛盾的学期）。
     * 1970-01-01（epoch day 0）是周四，故 +3 取模。
     */
    val weekStartDay: Int get() = ((firstDayEpochDay + 3).mod(7L)).toInt() + 1

    /** 周视图的列顺序：从 [weekStartDay] 起连排 7 天，元素是 ISO 星期几（1 = 周一）。 */
    val daysInWeekOrder: List<Int> get() = (0 until 7).map { (weekStartDay - 1 + it) % 7 + 1 }

    /**
     * 计算某个日期属于第几周。第 1 周从 [firstDayEpochDay] 起算，每 7 天一周 ——
     * 翻周自然发生在 [weekStartDay] 那一天，无需再折算。
     * @return 1-based 周次；不在学期内返回 null
     */
    fun weekOf(epochDay: Long): Int? {
        // floorDiv 而不是 `/`：Kotlin 的整数除法向零取整，开学前 1~6 天会被算成第 1 周
        // （2026-09-07 开学时，9月6日这个周日此前被判成「第 1 周」，今日页与提醒都会当真）
        val week = ((epochDay - firstDayEpochDay).floorDiv(7) + 1).toInt()
        return if (week in 1..totalWeeks) week else null
    }

    /**
     * 第 [week] 周里 ISO 星期几为 [dayOfWeek] 的那天。
     *
     * 一个自然周里，ISO 星期几的次序是固定的，但学期的一周从 [weekStartDay] 起算，
     * 所以第 [dayOfWeek] 天落在周内第几天要按起始日折算（周一开学的一周里，周日是第 7 天；
     * 周日开学的一周里，周日是第 1 天）。
     */
    fun epochDayOf(week: Int, dayOfWeek: Int): Long =
        firstDayEpochDay + (week - 1) * 7L + ((dayOfWeek - weekStartDay + 7) % 7)
}

/**
 * 把 [epochDay] 挪到最近的、ISO 星期几为 [dayOfWeek] 的那天。
 *
 * 「每周起始日」是「第 1 周第 1 天」的另一种说法，改它的操作就是这一句。
 * 取**最近**（±3 天内，前后各只有一个候选）而不是「同 ISO 周内的那个」，
 * 是为了来回拨能回到原处：周一 → 周日 → 周一 必须还是原来那天，
 * 否则每切一次起始日，整个学期就往前漂一周。
 */
fun nearestWeekday(epochDay: Long, dayOfWeek: Int): Long {
    require(dayOfWeek in 1..7) { "dayOfWeek must be in 1..7, was $dayOfWeek" }
    val current = ((epochDay + 3).mod(7L)).toInt() + 1
    // 折到 -3..3：正负两侧各只有一个候选，且互为反操作
    val delta = ((dayOfWeek - current + 3 + 7) % 7) - 3
    return epochDay + delta
}
