package com.nullclass.core.model

/** 一天里的一段时间：[startMinute] 起、[endMinute] 止（0..[MINUTES_PER_DAY]，止 > 起）。 */
data class MinuteSpan(val startMinute: Int, val endMinute: Int) {
    val lengthMinutes: Int get() = endMinute - startMinute
}

/**
 * 24 小时时间轴模式的几何：纵轴是一天的 0:00–24:00，课程卡片按实际上课时间定位。
 *
 * 纯函数，与 Compose 无关 —— 画的时候只需把分钟数乘上每分钟的高度。
 */
object TimelineGeometry {

    /** 卡片最短按 30 分钟画：节次时间填反（止 ≤ 起）时不画出零高或负高的卡片。 */
    const val MIN_SPAN_MINUTES = 30

    /** 进入时间轴时，第一节课上方留出的余量。 */
    const val SCROLL_LEAD_MINUTES = 30

    /** 没有节次时间时默认滚到的位置（8:00 前 30 分钟）。 */
    private const val FALLBACK_FIRST_START = 8 * 60

    /**
     * 上课安排在时间轴上占的时段：起 = 首节开始，止 = 末节结束。
     *
     * 按节次号取节次表的第 N 项（与节次模式的「第 N 行」同一口径），不假设节次表按时间单调 ——
     * 用户把第 3 节填得比第 2 节还早，时间轴就如实画在更早的位置，它本来就是「按实际时间画」。
     *
     * - 首节不在节次表里 → null（没有时间可依，不画）；
     * - 末节超出节次表 → 截到表内最后一节；
     * - 止 ≤ 起 → 补足 [MIN_SPAN_MINUTES]，贴着 24:00 时往前挪。
     */
    fun minuteSpan(block: ScheduleBlock, periodTimes: List<PeriodTime>): MinuteSpan? {
        val first = periodTimes.getOrNull(block.startPeriod - 1) ?: return null
        val lastIndex = minOf(block.endPeriod, periodTimes.size) - 1
        val last = periodTimes.getOrNull(lastIndex) ?: return null
        var start = first.startMinuteOfDay.coerceIn(0, MINUTES_PER_DAY)
        var end = last.endMinuteOfDay.coerceIn(0, MINUTES_PER_DAY)
        if (end <= start) {
            end = (start + MIN_SPAN_MINUTES).coerceAtMost(MINUTES_PER_DAY)
            start = (end - MIN_SPAN_MINUTES).coerceAtLeast(0)
        }
        return MinuteSpan(start, end)
    }

    /** 进入时间轴时纵向滚到的分钟数：最早一节开始前 [SCROLL_LEAD_MINUTES]，不早于 0:00。 */
    fun initialScrollMinute(periodTimes: List<PeriodTime>): Int {
        val firstStart = periodTimes.minOfOrNull { it.startMinuteOfDay } ?: FALLBACK_FIRST_START
        return (firstStart - SCROLL_LEAD_MINUTES).coerceAtLeast(0)
    }

    const val HOURS_PER_DAY = 24
}
