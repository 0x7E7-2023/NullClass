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
     * @return dayOfWeek(1..7) → 按开始节次升序排列的课块列表；
     *         无课的天不出现在 Map 中
     */
    fun layoutForWeek(schedule: List<CourseWithBlocks>, week: Int): Map<Int, List<PlacedBlock>> {
        require(week >= 1) { "week must be >= 1, was $week" }
        val result = mutableMapOf<Int, MutableList<PlacedBlock>>()
        for (courseWithBlocks in schedule) {
            for (block in courseWithBlocks.blocks) {
                if (block.occursInWeek(week)) {
                    result.getOrPut(block.dayOfWeek) { mutableListOf() }
                        .add(PlacedBlock(courseWithBlocks.course, block))
                }
            }
        }
        result.values.forEach { blocks -> blocks.sortBy { it.block.startPeriod } }
        return result
    }

    /** 某周某天某节是否有课（用于点空格加课时空格判断等）。 */
    fun isOccupied(layout: Map<Int, List<PlacedBlock>>, dayOfWeek: Int, period: Int): Boolean =
        layout[dayOfWeek]?.any { it.block.startPeriod <= period && period <= it.block.endPeriod } == true
}
