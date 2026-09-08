package com.nullclass.core.model

/**
 * 两条时间安排是否在同一时段撞车：同一天、节次区间相交，
 * 且存在双方都上课的同一周（单双周错开不算冲突）。
 */
fun ScheduleBlock.overlapsWith(other: ScheduleBlock): Boolean {
    if (dayOfWeek != other.dayOfWeek) return false
    if (startPeriod > other.endPeriod || other.startPeriod > endPeriod) return false
    val from = maxOf(startWeek, other.startWeek)
    val to = minOf(endWeek, other.endWeek)
    if (from > to) return false
    return (from..to).any { week -> occursInWeek(week) && other.occursInWeek(week) }
}

/** 一次跨课程时段冲突：待保存的安排 [newBlock] 撞上了已有课程 [course] 的 [existingBlock]。 */
data class CourseConflict(
    val course: Course,
    val existingBlock: ScheduleBlock,
    val newBlock: ScheduleBlock,
)

/** 课程查重：待保存课程的安排与同学期其他课程的时段冲突检测。 */
object ScheduleConflicts {

    /**
     * 找出 [newBlocks] 与 [existing]（同学期已有课表）之间的全部时段冲突。
     *
     * @param ignoreCourseId 编辑已有课程时传自身 id，避免和自己比出冲突。
     */
    fun find(
        newBlocks: List<ScheduleBlock>,
        existing: List<CourseWithBlocks>,
        ignoreCourseId: String? = null,
    ): List<CourseConflict> = buildList {
        for (other in existing) {
            if (ignoreCourseId != null && other.course.id == ignoreCourseId) continue
            for (existingBlock in other.blocks) {
                val hit = newBlocks.firstOrNull { it.overlapsWith(existingBlock) } ?: continue
                add(CourseConflict(other.course, existingBlock, hit))
            }
        }
    }
}
