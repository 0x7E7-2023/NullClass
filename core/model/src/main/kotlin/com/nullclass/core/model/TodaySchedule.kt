package com.nullclass.core.model

import java.time.LocalDate

/**
 * 今日课程渲染快照：今天（按当前学期推算）的全部课程，按开始时间排序。
 *
 * 渲染层自行用 [nextUp] 分「进行中/下一节/已结束」；列表空 → 引导文案。
 * 周视图外的两个「今天」入口——今日 Tab 与桌面小组件——共用此判定，保证两边渲染一致。
 */
data class TodaySnapshot(
    val termName: String,
    /** 今天是本学期第几周；不在学期内为 null */
    val weekNumber: Int?,
    val blocks: List<TodayEntry>,
) {
    data class TodayEntry(
        val placed: PlacedBlock,
        val startMinuteOfDay: Int,
        val endMinuteOfDay: Int,
        val startTime: String,
        val endTime: String,
        /** 见 [Session]：0 上午 / 1 下午 / 2 晚上 */
        val session: Int,
    )

    /** 当前时刻起还没结束的第一节课；今天没课或全上完为 null。 */
    fun nextUp(nowMinuteOfDay: Int): TodayEntry? =
        blocks.firstOrNull { nowMinuteOfDay < it.endMinuteOfDay }

    /** [nowMinuteOfDay] 是否在上课中（用于「进行中」标记）。 */
    fun inProgress(entry: TodayEntry, nowMinuteOfDay: Int): Boolean =
        nowMinuteOfDay >= entry.startMinuteOfDay && nowMinuteOfDay < entry.endMinuteOfDay

    /** 当前正在上的那节课；课间、今天没课或时间跨度外为 null。重叠课取最先开始的一节。 */
    fun inProgress(nowMinuteOfDay: Int): TodayEntry? =
        blocks.firstOrNull { inProgress(it, nowMinuteOfDay) }

    /** [entry] 距下课还剩多少分钟；仅上课中有意义，其余场合夹在 [0, 课长] 内。 */
    fun remainingMinutes(entry: TodayEntry, nowMinuteOfDay: Int): Int =
        (entry.endMinuteOfDay - nowMinuteOfDay).coerceIn(0, entry.endMinuteOfDay - entry.startMinuteOfDay)

    companion object {
        val EMPTY = TodaySnapshot(termName = "", weekNumber = null, blocks = emptyList())
    }
}

/**
 * 组装今日快照（纯函数，时区/日期注入可测）。
 * 节次配置残缺（起/止节次查不到时间行）的 block 跳过——用户可能改过节次表。
 */
fun assembleTodaySnapshot(
    term: Term,
    schedule: List<CourseWithBlocks>,
    periodTimes: List<PeriodTime>,
    today: LocalDate,
): TodaySnapshot {
    val week = term.weekOf(today.toEpochDay())
        ?: return TodaySnapshot(termName = term.name, weekNumber = null, blocks = emptyList())

    val timesByIndex = periodTimes.associateBy { it.periodIndex }
    val entries = mutableListOf<TodaySnapshot.TodayEntry>()
    for (courseWithBlocks in schedule) {
        for (block in courseWithBlocks.blocks) {
            if (!block.occursInWeek(week)) continue
            if (block.dayOfWeek != today.dayOfWeek.value) continue
            val startTime = timesByIndex[block.startPeriod] ?: continue
            val endTime = timesByIndex[block.endPeriod] ?: continue
            entries.add(
                TodaySnapshot.TodayEntry(
                    placed = PlacedBlock(courseWithBlocks.course, block),
                    startMinuteOfDay = startTime.startMinuteOfDay,
                    endMinuteOfDay = endTime.endMinuteOfDay,
                    startTime = ScheduleFormat.minuteLabel(startTime.startMinuteOfDay),
                    endTime = ScheduleFormat.minuteLabel(endTime.endMinuteOfDay),
                    session = startTime.session,
                ),
            )
        }
    }
    entries.sortWith(compareBy({ it.startMinuteOfDay }, { it.placed.block.startPeriod }))
    return TodaySnapshot(termName = term.name, weekNumber = week, blocks = entries)
}
