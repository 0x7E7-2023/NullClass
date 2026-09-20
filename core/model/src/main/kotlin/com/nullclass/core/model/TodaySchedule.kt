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
    /**
     * 今天被串课时，课来自哪一天（见 [DayOverride]）；没串课为 null。
     * [weekNumber] 仍是**今天**的周次——顶栏说的是今天在第几周，与课从哪天借来无关。
     */
    val swappedFrom: LocalDate? = null,
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
 *
 * [dayOverrides] 是串课表（date → source date，见 [DayOverrides.index]）：今天被串课时，
 * 课取自来源日那一格，但节次时间仍是今天的墙钟时间（调课换的是「上什么课」，不是作息）。
 */
fun assembleTodaySnapshot(
    term: Term,
    schedule: List<CourseWithBlocks>,
    periodTimes: List<PeriodTime>,
    today: LocalDate,
    dayOverrides: Map<Long, Long> = emptyMap(),
): TodaySnapshot {
    val displayWeek = term.weekOf(today.toEpochDay())
    val sourceEpochDay = DayOverrides.sourceOf(dayOverrides, today.toEpochDay())
    // 今天不在学期内时串课不生效（见 [DayOverrides.originOf]），横幅也不能出现：
    // 否则顶栏说「今天不在学期内」，下面却挂着「今天调课 · 上 X 月 X 日的课」
    val swappedFrom = if (displayWeek == null || sourceEpochDay == today.toEpochDay()) {
        null
    } else {
        LocalDate.ofEpochDay(sourceEpochDay)
    }
    // 来源日不在学期内（串到了寒暑假里）→ 这天没课；周次仍报今天的，顶栏不受影响
    val origin = DayOverrides.originOf(term, dayOverrides, today.toEpochDay())
        ?: return TodaySnapshot(
            termName = term.name,
            weekNumber = displayWeek,
            blocks = emptyList(),
            swappedFrom = swappedFrom,
        )
    val week = origin.week

    val timesByIndex = periodTimes.associateBy { it.periodIndex }
    val entries = mutableListOf<TodaySnapshot.TodayEntry>()
    for (courseWithBlocks in schedule) {
        for (block in courseWithBlocks.blocks) {
            if (!block.occursInWeek(week)) continue
            if (block.dayOfWeek != origin.dayOfWeek) continue
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
    return TodaySnapshot(
        termName = term.name,
        weekNumber = displayWeek,
        blocks = entries,
        swappedFrom = swappedFrom,
    )
}
