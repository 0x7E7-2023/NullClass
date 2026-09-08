package com.nullclass.importer.jw

import com.nullclass.core.model.DefaultPeriodTimes
import com.nullclass.importer.BlockDto
import com.nullclass.importer.CourseDto
import com.nullclass.importer.PeriodTimeDto
import com.nullclass.importer.ScheduleDocument
import com.nullclass.importer.TermDto
import java.time.LocalDate
import java.util.UUID

/**
 * 课表载荷 → 线上 [ScheduleDocument]。
 *
 * 补齐作者不该关心的东西：UUID、时间戳、颜色下标、默认节次表。
 * 产出的文档走与文件/二维码导入同一条预览→合并管线。
 */
object JwScheduleNormalizer {

    /**
     * @param schoolKey 适配器 key，用于 `deviceId` 溯源
     * @param now 审计时间戳（测试注入；生产传 `System.currentTimeMillis()`）
     */
    fun normalize(
        payload: JwSchedulePayload,
        schoolKey: String,
        now: Long,
        colorCount: Int = DEFAULT_COLOR_COUNT,
    ): ScheduleDocument {
        JwPayloadCodec.validate(payload)
        if (payload.kind != JwSchedulePayload.KIND_SCHEDULE) {
            throw JwPackageException("载荷类型是「${payload.kind}」，不是课表数据，无法直接合并")
        }

        val terms = mutableListOf<TermDto>()
        val courses = mutableListOf<CourseDto>()
        val blocks = mutableListOf<BlockDto>()
        val periodTimes = mutableListOf<PeriodTimeDto>()
        var colorCursor = 0

        payload.terms.forEach { term ->
            val termId = UUID.randomUUID().toString()
            val firstDay = term.firstDayEpochDay ?: LocalDate.parse(term.firstDay!!).toEpochDay()

            terms += TermDto(
                id = termId,
                name = term.name,
                firstDayEpochDay = firstDay,
                totalWeeks = term.totalWeeks,
                isCurrent = false,
                createdAt = now,
                updatedAt = now,
            )

            if (term.periodTimes.isEmpty()) {
                periodTimes += DefaultPeriodTimes.create(termId).map { it.toDto(now) }
            } else {
                periodTimes += term.periodTimes
                    .sortedBy { it.periodIndex }
                    .map { period ->
                        val start = JwPayloadCodec.toMinutes(period.start)
                        val end = JwPayloadCodec.toMinutes(period.end)
                        PeriodTimeDto(
                            termId = termId,
                            periodIndex = period.periodIndex,
                            startMinuteOfDay = start,
                            endMinuteOfDay = end,
                            session = sessionOf(start),
                            updatedAt = now,
                        )
                    }
            }

            term.courses.forEach { course ->
                val courseId = UUID.randomUUID().toString()
                courses += CourseDto(
                    id = courseId,
                    termId = termId,
                    name = course.name.trim(),
                    teacher = course.teacher?.trim()?.takeIf { it.isNotEmpty() },
                    note = course.note?.trim()?.takeIf { it.isNotEmpty() },
                    colorIndex = if (colorCount > 0) colorCursor++ % colorCount else 0,
                    createdAt = now,
                    updatedAt = now,
                )
                course.blocks.forEach { block ->
                    blocks += BlockDto(
                        id = UUID.randomUUID().toString(),
                        courseId = courseId,
                        termId = termId,
                        startWeek = block.startWeek,
                        endWeek = block.endWeek,
                        weekType = block.weekType,
                        dayOfWeek = block.dayOfWeek,
                        startPeriod = block.startPeriod,
                        endPeriod = block.endPeriod,
                        location = block.location?.trim()?.takeIf { it.isNotEmpty() },
                        createdAt = now,
                        updatedAt = now,
                    )
                }
            }
        }

        return ScheduleDocument(
            deviceId = "jw-$schoolKey",
            generatedAt = now,
            terms = terms,
            courses = courses,
            blocks = blocks,
            periodTimes = periodTimes,
        )
    }

    /** 与 core:model Session 的口径一致：0 上午 / 1 下午 / 2 晚上。 */
    private fun sessionOf(startMinuteOfDay: Int): Int = when {
        startMinuteOfDay < 12 * 60 -> 0
        startMinuteOfDay < 18 * 60 -> 1
        else -> 2
    }

    private fun com.nullclass.core.model.PeriodTime.toDto(now: Long) = PeriodTimeDto(
        termId = termId,
        periodIndex = periodIndex,
        startMinuteOfDay = startMinuteOfDay,
        endMinuteOfDay = endMinuteOfDay,
        session = session,
        updatedAt = now,
    )

    /** 颜色池大小，与 :core:ui CoursePalette 对齐；取不到时由调用方传 0 表示不轮转。 */
    const val DEFAULT_COLOR_COUNT = 12
}
