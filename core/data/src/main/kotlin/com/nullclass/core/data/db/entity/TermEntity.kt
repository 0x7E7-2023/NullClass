package com.nullclass.core.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 学期表。isCurrent 保证同一时刻至多一条为 true（由 Repository 层维护）。 */
@Entity(tableName = "terms")
data class TermEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val firstDayEpochDay: Long,
    val totalWeeks: Int,
    val isCurrent: Boolean = false,
)
