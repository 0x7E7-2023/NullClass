package com.nullclass.core.model

/** 提醒排算的一个输出：一门课的一次具体上课时刻。 */
data class UpcomingClass(
    val course: Course,
    val block: ScheduleBlock,
    /** 开课时刻（epoch millis，含时区换算） */
    val startAtMillis: Long,
    val endAtMillis: Long,
)

/**
 * 提醒排算纯函数：给定学期课表与节次表，展开未来 [horizonDays] 天内的全部上课时刻。
 * 提醒调度（WorkManager）与「下一节课」展示共用，保证口径一致。
 */
object ReminderPlanner {

    /** 迟发补发的窗口：开课超过此时长的课不再补「已开始」通知（太晚没有意义）。 */
    const val LATE_CATCHUP_WINDOW_MS: Long = 30L * 60_000

    /**
     * 已发送键匹配容差。键是 blockId:startAt（墙钟 epoch），时区切换后同一节课
     * 重算出的 epoch 会漂移（最大约 26h），精确匹配失效 → TIMEZONE_CHANGED 触发
     * 重排时重复通知。容差取 24h 覆盖现实中的时区漂移；同一 block 的课每周最多
     * 一次（间隔 ≥7 天），不会误伤上周/下周的课。
     */
    const val SENT_KEY_TOLERANCE_MS: Long = 24L * 3600 * 1000

    /**
     * 「这一节课」的提醒是否已经发过：同 blockId 且 startAt 相差在
     * [SENT_KEY_TOLERANCE_MS] 内的已发送键都算（同时覆盖时钟回拨场景——epoch 不变
     * 的精确匹配是它的特例）。
     */
    fun isAlreadySent(upcoming: UpcomingClass, sentKeys: Set<String>): Boolean {
        val prefix = "${upcoming.block.id}:"
        val start = upcoming.startAtMillis
        return sentKeys.any { key ->
            if (!key.startsWith(prefix)) return@any false
            val epoch = key.removePrefix(prefix).toLongOrNull() ?: return@any false
            kotlin.math.abs(epoch - start) < SENT_KEY_TOLERANCE_MS
        }
    }

    /**
     * 迟发补发判定：提醒时刻已过（没赶上）的一节课要不要补发「已开始」通知。
     *
     * - 课还没开始（还在提前量窗口内）→ 不补：调度方会把任务重新入队立即发
     * - 已开课但下课了 → 不补（下课了才知道没意义）
     * - 开课超过 [LATE_CATCHUP_WINDOW_MS] → 不补（迟太久）
     * - 已发过 → 不补（[isAlreadySent] 容差匹配，防止每次重排都复活已划掉的通知）
     */
    fun shouldSendLate(
        upcoming: UpcomingClass,
        nowMillis: Long,
        sentKeys: Set<String>,
        windowMs: Long = LATE_CATCHUP_WINDOW_MS,
    ): Boolean {
        if (upcoming.startAtMillis > nowMillis) return false
        if (upcoming.endAtMillis <= nowMillis) return false
        if (nowMillis - upcoming.startAtMillis > windowMs) return false
        return !isAlreadySent(upcoming, sentKeys)
    }

    /** 通知 tag（也是已发送去重键）：blockId:startAt。 */
    fun reminderTag(upcoming: UpcomingClass): String =
        "${upcoming.block.id}:${upcoming.startAtMillis}"

    /**
     * 排算 [fromMillis, fromMillis + horizonDays 天] 内的全部上课时刻，按开始时间升序。
     *
     * - 学期外的日期跳过（weekOf == null）
     * - 节次表缺该 block 起止节次对应行 → 静默跳过（用户改过节次表的防御）
     * - 今天已结束（endAt <= fromMillis）的课不出现；进行中/未开始的保留
     */
    fun upcoming(
        term: Term,
        schedule: List<CourseWithBlocks>,
        periodTimes: List<PeriodTime>,
        fromMillis: Long,
        horizonDays: Int = 14,
        zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
    ): List<UpcomingClass> {
        require(horizonDays >= 0) { "horizonDays must be >= 0" }

        val fromDay = java.time.Instant.ofEpochMilli(fromMillis).atZone(zone).toLocalDate()
        val timesByIndex = periodTimes.associateBy { it.periodIndex }
        val result = mutableListOf<UpcomingClass>()

        for (dayOffset in 0..horizonDays) {
            val day = fromDay.plusDays(dayOffset.toLong())
            val week = term.weekOf(day.toEpochDay()) ?: continue
            val dayStart = day.atStartOfDay(zone).toInstant().toEpochMilli()

            for (courseWithBlocks in schedule) {
                for (block in courseWithBlocks.blocks) {
                    if (!block.occursInWeek(week)) continue
                    if (block.dayOfWeek != day.dayOfWeek.value) continue
                    val startTime = timesByIndex[block.startPeriod] ?: continue
                    val endTime = timesByIndex[block.endPeriod] ?: continue
                    val startAt = dayStart + startTime.startMinuteOfDay * 60_000L
                    val endAt = dayStart + endTime.endMinuteOfDay * 60_000L
                    if (endAt <= fromMillis) continue // 今天已结束的
                    result.add(UpcomingClass(courseWithBlocks.course, block, startAt, endAt))
                }
            }
        }
        return result.sortedBy { it.startAtMillis }
    }
}
