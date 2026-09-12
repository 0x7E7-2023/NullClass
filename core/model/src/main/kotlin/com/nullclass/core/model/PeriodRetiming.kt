package com.nullclass.core.model

/** [retimeSections] 的结果。 */
sealed interface RetimeResult {

    /** 排好的节次时间：节次号与会话分组原样保留，只换时间。 */
    data class Ok(val periods: List<PeriodTime>) : RetimeResult

    /** 第 [section] 大节按这个时长排会到 [endMinuteOfDay]，超过下一个大节的开课时刻 [nextStartMinuteOfDay]。 */
    data class Overflow(
        val section: Int,
        val endMinuteOfDay: Int,
        val nextStartMinuteOfDay: Int,
    ) : RetimeResult

    /**
     * 第 [section] 大节排到 [endMinuteOfDay]，已经越过当天 24:00（[MINUTES_PER_DAY]）。
     *
     * 最后一个大节没有「下一个锚点」可比，不放它过去就会算出 `endMinuteOfDay > 1440`，
     * 撞在 [PeriodTime] 的 init 上抛异常。
     */
    data class OutOfDay(val section: Int, val endMinuteOfDay: Int) : RetimeResult
}

/**
 * 快速设定作息：**每个大节的开课时刻（第 1、3、5…节的开始时间）原地不动**，只重算大节内部 ——
 * 第一节 [lessonMinutes] 分钟，接着课间 [breakMinutes] 分钟，第二节再 [lessonMinutes] 分钟。
 *
 * 课间休息指的是**大节内部两节之间**的休息（默认模板里是 10 分钟，即 8:45→8:55）。
 * 大节与大节之间的休息（上午大课间之类，各校差得多，默认模板里是 20 分钟）不归这里管：
 * 它就等于相邻两个大节开课时刻之差，锚点不动它就原样保留。所以对默认模板套用
 * 「45 分钟 + 10 分钟」是恒等变换 —— 这也正是这个函数该有的手感。
 *
 * @param periods 现有节次时间，按节次号升序；只用到奇数下标（各大节第一节）的开始时间与会话分组
 * @return 装不下时返回 [RetimeResult.Overflow]（顶到下一个大节）或 [RetimeResult.OutOfDay]
 *   （排到第二天），都不返回半截结果
 */
fun retimeSections(
    periods: List<PeriodTime>,
    lessonMinutes: Int,
    breakMinutes: Int,
): RetimeResult {
    require(lessonMinutes >= 1) { "lessonMinutes must be >= 1, was $lessonMinutes" }
    require(breakMinutes >= 0) { "breakMinutes must be >= 0, was $breakMinutes" }
    if (periods.isEmpty()) return RetimeResult.Ok(emptyList())

    val result = ArrayList<PeriodTime>(periods.size)
    for (section in 1..SectionMath.sectionCount(periods.size)) {
        val first = 2 * (section - 1)
        val anchor = periods[first].startMinuteOfDay
        val firstEnd = anchor + lessonMinutes
        if (firstEnd > MINUTES_PER_DAY) return RetimeResult.OutOfDay(section, firstEnd)
        result.add(
            periods[first].copy(startMinuteOfDay = anchor, endMinuteOfDay = firstEnd),
        )

        val second = first + 1
        if (second >= periods.size) break // 奇数节次：最后一个大节只有一节
        val secondStart = firstEnd + breakMinutes
        val secondEnd = secondStart + lessonMinutes
        // 先看当天上界再看下一个大节：越过 24:00 时「顶到下一个大节」这句话没有意义
        if (secondEnd > MINUTES_PER_DAY) return RetimeResult.OutOfDay(section, secondEnd)
        // 下一个大节的第一节就是下一行；顶到它就算排不下（各校大节间距比大节内课间短的情况）
        val nextAnchor = periods.getOrNull(second + 1)?.startMinuteOfDay
        if (nextAnchor != null && secondEnd > nextAnchor) {
            return RetimeResult.Overflow(section, secondEnd, nextAnchor)
        }
        result.add(
            periods[second].copy(startMinuteOfDay = secondStart, endMinuteOfDay = secondEnd),
        )
    }
    return RetimeResult.Ok(result)
}
