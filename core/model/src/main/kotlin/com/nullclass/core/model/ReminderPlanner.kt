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
