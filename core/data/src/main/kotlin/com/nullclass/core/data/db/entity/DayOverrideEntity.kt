package com.nullclass.core.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 串课（调休调课）：[epochDay] 这天改上 [sourceEpochDay] 那天的课。
 *
 * 与 skip_dates 一样是**纯本地表**，不进 WebDAV 快照、也不进二维码/文件导出
 * （SnapshotCodec 按表枚举，不包含本表）：调休是「这台设备的这个学期」的临时安排，
 * 跨设备各自设置比被同步覆盖更符合预期。
 */
@Entity(tableName = "day_overrides")
data class DayOverrideEntity(
    @PrimaryKey val epochDay: Long,
    val sourceEpochDay: Long,
    val updatedAt: Long,
)
