package com.nullclass.core.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 课程的一次考试。courseId 是唯一归属来源；审计列与其他业务表保持一致，便于同步。
 */
@Entity(
    tableName = "exams",
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
        Index("deletedAt"),
        Index("courseId", "dateEpochDay", "deletedAt"),
    ],
)
data class ExamEntity(
    @PrimaryKey val id: String,
    val courseId: String,
    val title: String,
    val dateEpochDay: Long,
    val startMinuteOfDay: Int? = null,
    val endMinuteOfDay: Int? = null,
    val location: String? = null,
    val seat: String? = null,
    val note: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
)
