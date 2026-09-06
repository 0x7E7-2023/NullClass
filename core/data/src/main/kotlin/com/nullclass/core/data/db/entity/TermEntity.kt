package com.nullclass.core.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * v2 审计三列约定（全业务表一致，见 docs/impl 1.2）：
 *  - updatedAt：同步 LWW 冲突判定
 *  - deletedAt：软删除墓碑，同步时传播删除；业务查询一律过滤 IS NULL
 */
@Entity(
    tableName = "terms",
    indices = [Index("deletedAt")],
)
data class TermEntity(
    @PrimaryKey val id: String,
    val name: String,
    val firstDayEpochDay: Long,
    val totalWeeks: Int,
    val isCurrent: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
)
