package com.nullclass.core.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 日程安排（个人事件）。与 day_overrides 一样是**纯本地表**：不挂学期、
 * 不进 WebDAV 快照和导出（SnapshotCodec 按表枚举，不包含本表）。
 */
@Entity(tableName = "calendar_events", indices = [Index("dateEpochDay")])
data class CalendarEventEntity(
    @PrimaryKey val id: String,
    val title: String,
    val dateEpochDay: Long,
    val startMinuteOfDay: Int? = null,
    val note: String? = null,
    val remindLeadMinutes: Int? = null,
    val updatedAt: Long,
)
