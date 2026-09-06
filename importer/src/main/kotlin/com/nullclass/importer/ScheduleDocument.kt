package com.nullclass.importer

import kotlinx.serialization.Serializable

/**
 * 空课课表分享文档（.nullclass 文件 / 二维码内容）。
 *
 * 设计原则：DTO 自带全部字段、不引用 core.model 的枚举——
 * 线上格式一旦发布就是契约，必须独立于应用内部模型演进。
 */
@Serializable
data class ScheduleDocument(
    val formatVersion: Int = FORMAT_VERSION,
    val app: String = "NullClass",
    val term: TermDto,
    val courses: List<CourseDto>,
) {
    companion object {
        const val FORMAT_VERSION = 1
    }
}

@Serializable
data class TermDto(
    /** 例："2026-2027-1" */
    val name: String,
    /** 开学第一周周一的 epoch day */
    val firstDayEpochDay: Long,
    val totalWeeks: Int,
    val periodTimes: List<PeriodTimeDto>,
)

@Serializable
data class CourseDto(
    val name: String,
    val teacher: String? = null,
    val colorIndex: Int = 0,
    val blocks: List<ScheduleBlockDto> = emptyList(),
)

@Serializable
data class ScheduleBlockDto(
    val startWeek: Int,
    val endWeek: Int,
    /** "ALL" | "ODD" | "EVEN" */
    val weekType: String,
    /** 1..7，1 = 周一 */
    val dayOfWeek: Int,
    /** 节次从 1 开始 */
    val startPeriod: Int,
    val endPeriod: Int,
    val location: String? = null,
)

@Serializable
data class PeriodTimeDto(
    val periodIndex: Int,
    val startMinuteOfDay: Int,
    val endMinuteOfDay: Int,
)
