package com.nullclass.core.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "courses",
    foreignKeys = [
        ForeignKey(
            entity = TermEntity::class,
            parentColumns = ["id"],
            childColumns = ["termId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("termId"), Index("deletedAt"), Index("termId", "deletedAt")],
)
data class CourseEntity(
    @PrimaryKey val id: String,
    val termId: String,
    val name: String,
    val teacher: String? = null,
    val note: String? = null,
    val colorIndex: Int = 0,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
)
