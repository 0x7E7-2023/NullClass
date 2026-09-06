package com.nullclass.core.data.db.entity

import androidx.room.Embedded
import androidx.room.Relation

/** 课程 + 其时间安排的 Room 关系载体（DAO 查询用）。 */
data class CourseWithBlocksEntity(
    @Embedded val course: CourseEntity,
    @Relation(parentColumn = "id", entityColumn = "courseId")
    val blocks: List<ScheduleBlockEntity>,
)
