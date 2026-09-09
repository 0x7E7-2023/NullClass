package com.nullclass.importer

import kotlinx.serialization.Serializable

/**
 * 空课课表文档（formatVersion 2）。
 *
 * **线上契约**：`.nullclass` 文件与 WebDAV 同步的 snapshot.json 完全同构
 * （.nullclass 文件 = snapshot.json 的内容），每条记录携带完整审计字段，
 * 支持逐记录 LWW 合并与删除传播——导入 = 一次单向同步。
 *
 * 线上格式一旦发布就是契约，必须独立于应用内部模型演进；
 * v2 起新增字段必须给默认值（codec 配置 encodeDefaults，旧版本可读）。
 */
@Serializable
data class ScheduleDocument(
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

/**
 * 导入来源标记（[ScheduleDocument.deviceId] 的取值约定）。
 *
 * `jw-*`（教务适配器）与 [WAKEUP_IMPORT]（WakeUp 迁移）这类来源**每次导入都重新生成
 * 记录 UUID**，纯按 ID 合并会让重复导入变成复制一份——:sync 的 ImportAligner 靠这个标记
 * 决定要不要先做「同名学期对齐」。其余来源（`.nullclass` 备份 / 扫码，deviceId = 导出
 * 设备）沿用记录原有 ID，走纯 LWW 即可。
 */
object ImportProvenance {
    /** 教务适配器：`jw-<schoolKey>`。 */
    const val JW_PREFIX = "jw-"

    /** WakeUp 迁移。 */
    const val WAKEUP_IMPORT = "wakeup-import"

    fun isFreshIdImport(deviceId: String): Boolean =
        deviceId.startsWith(JW_PREFIX) || deviceId == WAKEUP_IMPORT
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
    /** "ALL" | "ODD" | "EVEN" */
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

/** manifest.json：WebDAV 远端版本指针（同步专用，不进 .nullclass 文件）。 */
@Serializable
data class ManifestDto(
    val formatVersion: Int = ScheduleDocument.FORMAT_VERSION,
    val deviceId: String,
    val generatedAt: Long,
    val rev: Long,
)
