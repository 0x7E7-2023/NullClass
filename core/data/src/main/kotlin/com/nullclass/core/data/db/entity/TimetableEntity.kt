package com.nullclass.core.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 课表（v3 新增）。审计三列约定与全业务表一致：
 *  - updatedAt：同步 LWW 冲突判定
 *  - deletedAt：软删除墓碑，同步时传播删除；业务查询一律过滤 IS NULL
 */
@Entity(
    tableName = "timetables",
    indices = [Index("deletedAt")],
)
data class TimetableEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
)
