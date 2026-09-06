package com.nullclass.sync

import kotlinx.serialization.Serializable

/**
 * 空课同步快照（snapshot.json，formatVersion 2）。
 * 与导入导出格式同源（见 docs/impl 1.5），每条记录携带完整审计字段，
 * 支持逐记录 LWW 合并与删除传播。
 */
@Serializable
data class SnapshotDto(
    val formatVersion: Int = FORMAT_VERSION,
    val deviceId: String,
    val generatedAt: Long,
    val terms: List<TermDto>,
    val courses: List<CourseDto>,
    val blocks: List<BlockDto>,
    val periodTimes: List<PeriodTimeDto>,
) {
    companion object {
        const val FORMAT_VERSION = 2
    }
}

@Serializable
data class TermDto(
    val id: String,
    val name: String,
    val firstDayEpochDay: Long,
    val totalWeeks: Int,
    val isCurrent: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
)

@Serializable
data class CourseDto(
    val id: String,
    val termId: String,
    val name: String,
    val teacher: String? = null,
    val note: String? = null,
    val colorIndex: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
)

@Serializable
data class BlockDto(
    val id: String,
    val courseId: String,
    val termId: String,
    val startWeek: Int,
    val endWeek: Int,
    val weekType: String,
    val dayOfWeek: Int,
    val startPeriod: Int,
    val endPeriod: Int,
    val location: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
)

/** 随所属 term 整体合并，无墓碑。 */
@Serializable
data class PeriodTimeDto(
    val termId: String,
    val periodIndex: Int,
    val startMinuteOfDay: Int,
    val endMinuteOfDay: Int,
    val session: Int,
    val updatedAt: Long,
)

/** manifest.json：远端版本指针。 */
@Serializable
data class ManifestDto(
    val formatVersion: Int = SnapshotDto.FORMAT_VERSION,
    val deviceId: String,
    val generatedAt: Long,
    val rev: Long,
)
