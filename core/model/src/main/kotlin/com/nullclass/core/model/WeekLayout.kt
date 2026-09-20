package com.nullclass.core.model

/** 周视图中一个已放置的课块：课程信息 + 命中该周的那条安排。 */
data class PlacedBlock(
    val course: Course,
    val block: ScheduleBlock,
)

/**
 * 周视图布局纯函数：给定整学期课表与周次，产出每天该周出现的课块。
 * UI（周视图）与未来的 widget 共用，保证两边渲染一致。
 */
object WeekLayout {

    /**
     * [term] + [overrides] 一起给出串课表（date → source date，见 [DayOverrides]）：
     * 被串过的那一列改画来源日的课。两者留空（默认）时与串课功能上线前逐字节同构。
     *
     * @return dayOfWeek(1..7) → 按开始节次升序排列的课块列表；
     *         无课的天不出现在 Map 中
     */
    fun layoutForWeek(
        schedule: List<CourseWithBlocks>,
        week: Int,
        term: Term? = null,
        overrides: Map<Long, Long> = emptyMap(),
    ): Map<Int, List<PlacedBlock>> {
        require(week >= 1) { "week must be >= 1, was $week" }
        val origins = originsForWeek(term, week, overrides)
        val result = mutableMapOf<Int, MutableList<PlacedBlock>>()
        for (courseWithBlocks in schedule) {
            for (block in courseWithBlocks.blocks) {
                // 没串课的列：课块画在自己那天（原路径）
                if (block.dayOfWeek !in origins && block.occursInWeek(week)) {
                    result.getOrPut(block.dayOfWeek) { mutableListOf() }
                        .add(PlacedBlock(courseWithBlocks.course, block))
                }
                // 串过的列：谁被借到这一列，就画在这一列（可以有多列借同一天）
                for ((day, origin) in origins) {
                    if (origin == null) continue // 串到了学期外 → 这列没课
                    if (block.dayOfWeek == origin.dayOfWeek && block.occursInWeek(origin.week)) {
                        result.getOrPut(day) { mutableListOf() }
                            .add(PlacedBlock(courseWithBlocks.course, block))
                    }
                }
            }
        }
        result.values.forEach { blocks -> blocks.sortBy { it.block.startPeriod } }
        return result
    }

    /** 串课表在第 [week] 周落到哪几列；[term] 为 null 或没串课时为空表。见 [DayOverrides.originsForWeek]。 */
    private fun originsForWeek(term: Term?, week: Int, overrides: Map<Long, Long>): Map<Int, DayOrigin?> =
        if (term == null || overrides.isEmpty()) emptyMap() else DayOverrides.originsForWeek(term, week, overrides)

    /**
     * 「非本周」的课块：整学期课表里第 [week] 周不上、但同一时段在别的周要上的安排
     * （单双周、上半学期课……周视图把它们藏起来后，空着的格子看不出「以后这里其实有课」）。
     *
     * 只挑**当周空着的时段**：与当周真课块占用的节次有任何重叠的一律不出 —— 课块在周视图里是
     * 绝对定位的，重叠就是互相盖住。灰块之间同样避让：同一时段在别的周可能有好几门课
     * （上下半学期各一门），按开始节次先到先得，全画出来会糊成一团。
     *
     * @return dayOfWeek(1..7) → 按开始节次升序排列的灰块；无灰块的天不出现在 Map 中
     */
    fun otherWeekLayout(
        schedule: List<CourseWithBlocks>,
        week: Int,
        term: Term? = null,
        overrides: Map<Long, Long> = emptyMap(),
    ): Map<Int, List<PlacedBlock>> {
        require(week >= 1) { "week must be >= 1, was $week" }
        val origins = originsForWeek(term, week, overrides)
        val thisWeek = layoutForWeek(schedule, week, term, overrides)
        // 先定序再挑，结果不随课表里的记录顺序变
        val candidates = schedule
            .flatMap { courseWithBlocks ->
                courseWithBlocks.blocks
                    .filterNot { it.occursInWeek(week) }
                    .map { PlacedBlock(courseWithBlocks.course, it) }
            }
            .sortedWith(
                // 末尾的 block.id 不是装饰：同名课（两门「体育」）压在同一天同一节、周次错开时
                // ——正是本功能主打的上下半学期/单双周场景——前三个键全并列，少了兜底键就会
                // 回落到课表记录顺序，同一份数据在不同设备上画出另一门课的灰块。
                // 课块 id 是随同步走的 UUID，跨设备稳定。
                compareBy(
                    { it.block.dayOfWeek },
                    { it.block.startPeriod },
                    { it.course.name },
                    { it.block.id },
                ),
            )

        val result = mutableMapOf<Int, MutableList<PlacedBlock>>()
        for (candidate in candidates) {
            val day = candidate.block.dayOfWeek
            // 被串过的列压根不属于这一天了，「这天别的周有课」这句话在那一列不成立
            if (day in origins) continue
            val taken = thisWeek[day].orEmpty() + result[day].orEmpty()
            if (taken.any { it.block.periodsOverlap(candidate.block) }) continue
            result.getOrPut(day) { mutableListOf() }.add(candidate)
        }
        return result
    }

    /** 某周某天某节是否有课（用于点空格加课时空格判断等）。 */
    fun isOccupied(layout: Map<Int, List<PlacedBlock>>, dayOfWeek: Int, period: Int): Boolean =
        layout[dayOfWeek]?.any { it.block.startPeriod <= period && period <= it.block.endPeriod } == true

    /**
     * 同一天的节次区间是否相交。
     *
     * 刻意不复用 [overlapsWith]：那个还要求「存在双方都上课的同一周」才算冲突，
     * 而这里问的是「画在同一格里会不会叠上」，与周次无关。
     */
    private fun ScheduleBlock.periodsOverlap(other: ScheduleBlock): Boolean =
        startPeriod <= other.endPeriod && other.startPeriod <= endPeriod
}
