package com.nullclass.core.model

/** 一门课及其全部时间安排。Repository 与 UI 的主要交换类型。 */
data class CourseWithBlocks(
    val course: Course,
    val blocks: List<ScheduleBlock>,
)
