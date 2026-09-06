package com.nullclass.core.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/** 节次时间表，复合主键 (termId, periodIndex)。随 term 快照整体同步，无需墓碑。 */
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
    val termId: String,
    val periodIndex: Int,
    val startMinuteOfDay: Int,
    val endMinuteOfDay: Int,
    /** 0=上午 1=下午 2=晚上，见 core.model.Session */
    val session: Int,
    val updatedAt: Long,
)
