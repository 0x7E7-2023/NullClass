package com.nullclass.core.data.db.entity

import androidx.room.Embedded
import androidx.room.Relation

/** 考试 + 所属课程的 Room 关系载体。 */
data class ExamWithCourseEntity(
    @Embedded val exam: ExamEntity,
    @Relation(parentColumn = "courseId", entityColumn = "id")
    val course: CourseEntity,
)
