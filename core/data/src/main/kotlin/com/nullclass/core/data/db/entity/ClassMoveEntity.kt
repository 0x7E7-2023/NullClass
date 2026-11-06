package com.nullclass.core.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 单次调课：课块 [blockId] 在 [fromEpochDay] 那一次挪到 [toEpochDay] 的第 [toStartPeriod]..[toEndPeriod] 节。
 * 与 day_overrides 一样是纯本地表，不进同步快照与导出（理由见 [DayOverrideEntity]）。
 */
@Entity(tableName = "class_moves")
data class ClassMoveEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val blockId: String,
    val fromEpochDay: Long,
    val toEpochDay: Long,
    val toStartPeriod: Int,
    val toEndPeriod: Int,
    val updatedAt: Long,
)
