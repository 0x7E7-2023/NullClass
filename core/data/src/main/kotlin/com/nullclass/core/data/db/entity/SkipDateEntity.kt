package com.nullclass.core.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 跳过日期（这天不上课）：手动添加 + 节假日同步两个来源写同一张表。
 * 纯本地表，不进 WebDAV 快照同步（SnapshotCodec 按表枚举，不包含本表）；
 * 节假日可重新同步，手动日期各设备自理。
 */
@Entity(tableName = "skip_dates", indices = [Index("type")])
data class SkipDateEntity(
    @PrimaryKey val epochDay: Long,
    /** [com.nullclass.core.model.SkipDateType.name] */
    val type: String,
    val label: String? = null,
    val updatedAt: Long,
)
