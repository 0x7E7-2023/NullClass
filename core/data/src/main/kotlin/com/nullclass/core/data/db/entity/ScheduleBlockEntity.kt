package com.nullclass.core.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * termId 为反规范化冗余列（源自 course.termId）：
 * 周视图按 (termId, dayOfWeek) 直查课块，免 join。
 */
@Entity(
    tableName = "schedule_blocks",
    foreignKeys = [
        ForeignKey(
            entity = CourseEntity::class,
            parentColumns = ["id"],
            childColumns = ["courseId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("courseId"),
        Index("termId", "dayOfWeek", "deletedAt"),
        Index("deletedAt"),
    ],
)
data class ScheduleBlockEntity(
    @PrimaryKey val id: String,
    val courseId: String,
    val termId: String,
    val startWeek: Int,
    val endWeek: Int,
    /** WeekType.name() */
    val weekType: String,
    /** 1..7，1 = 周一 */
    val dayOfWeek: Int,
    val startPeriod: Int,
    val endPeriod: Int,
    val location: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
)
