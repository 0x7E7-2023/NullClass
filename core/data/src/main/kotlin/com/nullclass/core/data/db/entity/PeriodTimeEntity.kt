package com.nullclass.core.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/** 节次时间表，复合主键 (termId, periodIndex)。 */
@Entity(
    tableName = "period_times",
    primaryKeys = ["termId", "periodIndex"],
    foreignKeys = [
        ForeignKey(
            entity = TermEntity::class,
            parentColumns = ["id"],
            childColumns = ["termId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("termId")],
)
data class PeriodTimeEntity(
    val termId: Long,
    val periodIndex: Int,
    val startMinuteOfDay: Int,
    val endMinuteOfDay: Int,
)
