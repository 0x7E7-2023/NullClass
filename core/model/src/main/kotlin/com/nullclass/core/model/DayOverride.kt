package com.nullclass.core.model

import java.time.LocalDate

/**
 * 串课（调休调课）：[epochDay] 这天改上 [sourceEpochDay] 那天的课。
 *
 * 只表达「这天上哪天的课」，不表达「两天对调」——调休真实的样子是
 * 「10 月 11 日（周六）上 10 月 9 日（周五）的课，10 月 9 日放假」，后半句本来就是
 * 跳过日期（[SkipDate]）的活。真要对调两天，加两条记录即可，语义仍然是单向的。
 *
 * 存来源**日期**而不是「第几周 星期几」：周次是学期的派生量，改了开学日期就会漂，
 * 而「上 10 月 9 日那天的课」这句话不会随学期设置变味；单双周课也因此自动落对。
 */
data class DayOverride(
    val epochDay: Long,
    val sourceEpochDay: Long,
)

/** 串课解析后的取课坐标：第 [week] 周的星期 [dayOfWeek]（1 = 周一）。 */
data class DayOrigin(
    val week: Int,
    val dayOfWeek: Int,
)

/**
 * 串课表的解析规则。所有取课口径（今日页、周视图、小组件、提醒）共用这里，
 * 四处永远算出同一个来源日。
 */
object DayOverrides {

    /**
     * 把记录压成 date → source date 的查表。
     * 自指的行（来源就是它自己）当作「没串」丢掉：它既是历史脏数据的样子，
     * 也是「一跳」规则下唯一可能的环。
     */
    fun index(overrides: List<DayOverride>): Map<Long, Long> =
        overrides.filter { it.epochDay != it.sourceEpochDay }
            .associate { it.epochDay to it.sourceEpochDay }

    /**
     * [epochDay] 这天实际取用哪一天的课；没串课就是它自己。
     *
     * **只跟一跳，不递归**：A 串到 B、B 又串到 C 时，A 拿的仍是 B**原本**的课。
     * 这既符合调休的说法（「周六上周五的课」指的是课表上周五那一格），也让成环不可能。
     */
    fun sourceOf(index: Map<Long, Long>, epochDay: Long): Long = index[epochDay] ?: epochDay

    /**
     * [epochDay] 这天按「第几周 星期几」取课。
     *
     * @return 以下两种情况为 null —— 这天没有课：
     *  - **目标日**不在 [term] 学期内：学期外本来就没课，串课不能凭空造出课来
     *    （否则提醒会在放假期间照排，而今日页顶栏还写着「今天不在学期内」）
     *  - **来源日**不在学期内：串到了寒暑假里，那一格本来就是空的
     */
    fun originOf(term: Term, index: Map<Long, Long>, epochDay: Long): DayOrigin? {
        if (term.weekOf(epochDay) == null) return null
        val source = sourceOf(index, epochDay)
        val week = term.weekOf(source) ?: return null
        return DayOrigin(week = week, dayOfWeek = LocalDate.ofEpochDay(source).dayOfWeek.value)
    }

    /**
     * 第 [week] 周里**被串过**的那几列。
     *
     * @return dayOfWeek(1..7) → 该列改取的坐标；值为 null 表示串到了学期外（该列没课）。
     *         没串过的天不出现在 Map 中 —— 使用方要用 `containsKey` 区分「没串」和「串空了」。
     */
    fun originsForWeek(term: Term, week: Int, index: Map<Long, Long>): Map<Int, DayOrigin?> {
        if (index.isEmpty()) return emptyMap()
        val result = mutableMapOf<Int, DayOrigin?>()
        for (day in 1..7) {
            val date = term.epochDayOf(week, day)
            if (date !in index) continue
            result[day] = originOf(term, index, date)
        }
        return result
    }
}
