package com.nullclass.sync

import androidx.room.withTransaction
import com.nullclass.core.data.db.NullClassDatabase
import com.nullclass.core.data.db.dao.CourseDao
import com.nullclass.core.data.db.dao.PeriodTimeDao
import com.nullclass.core.data.db.dao.TermDao
import com.nullclass.core.data.db.dao.TimetableDao
import com.nullclass.core.data.db.entity.CourseEntity
import com.nullclass.core.data.db.entity.PeriodTimeEntity
import com.nullclass.core.data.db.entity.ScheduleBlockEntity
import com.nullclass.core.data.db.entity.TermEntity
import com.nullclass.core.data.db.entity.TimetableEntity
import com.nullclass.importer.BlockDto
import com.nullclass.importer.CourseDto
import com.nullclass.importer.PeriodTimeDto
import com.nullclass.importer.ScheduleDocument
import com.nullclass.importer.TermDto
import com.nullclass.importer.TimetableDto
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 本地库 ↔ ScheduleDocument 的整体编解码（同步与导入导出共用）。
 * apply 的语义与同步合并写回完全一致：Upsert 全量 + 节次表整体替换。
 * 学期归属（timetableId）的解析与兜底在 [SyncEngine]（apply 只做无脑写入）。
 */
@Singleton
class SnapshotCodec @Inject constructor(
    private val db: NullClassDatabase,
    private val termDao: TermDao,
    private val timetableDao: TimetableDao,
    private val courseDao: CourseDao,
    private val periodTimeDao: PeriodTimeDao,
    private val settings: SyncSettingsRepository,
) {

    /** 全量导出本地库（含墓碑）。deviceId 传 null 时取本机设备 ID。 */
    suspend fun dump(deviceId: String? = null, nowMillis: Long = System.currentTimeMillis()): ScheduleDocument =
        ScheduleDocument(
            deviceId = deviceId ?: settings.deviceId(),
            generatedAt = nowMillis,
            timetables = timetableDao.getAllTimetables().map { it.toDto() },
            terms = termDao.getAllTerms().map { it.toDto() },
            courses = courseDao.getAllCourses().map { it.toDto() },
            blocks = courseDao.getAllBlocks().map { it.toDto() },
            periodTimes = periodTimeDao.getAll().map { it.toDto() },
        )

    /** 合并结果写回本地库（Upsert 全量；节次表整体替换；UI 的 Flow 自动刷新）。 */
    suspend fun apply(document: ScheduleDocument) {
        db.withTransaction {
            timetableDao.upsertAll(document.timetables.map { it.toEntity() })
            termDao.upsertAll(document.terms.map { it.toEntity() })
            courseDao.upsertAllCourses(document.courses.map { it.toEntity() })
            courseDao.upsertAllBlocks(document.blocks.map { it.toEntity() })
            // 节次表随学期整体取新：必须先清空，否则被删节次残留本地
            periodTimeDao.deleteAll()
            periodTimeDao.upsertAll(document.periodTimes.map { it.toEntity() })
        }
    }

    /** 统计 [local] 没有或本地更旧的记录数（不含 periodTimes，口径粗粒度够用）。 */
    fun countAdopted(local: ScheduleDocument, merged: ScheduleDocument): Int =
        countAdoptedRecords(local, merged)
}

/** 采纳数（纯函数，可单测）：合并结果里本地没有或更旧的记录数，课表/学期/课程/课块四类合计。 */
internal fun countAdoptedRecords(local: ScheduleDocument, merged: ScheduleDocument): Int {
    fun <T> count(localList: List<T>, mergedList: List<T>, id: (T) -> String, updatedAt: (T) -> Long): Int {
        val localMap = localList.associateBy(id)
        return mergedList.count { merged ->
            val existing = localMap[id(merged)]
            existing == null || updatedAt(merged) > updatedAt(existing)
        }
    }
    return count(local.timetables, merged.timetables, { it.id }, { it.updatedAt }) +
        count(local.terms, merged.terms, { it.id }, { it.updatedAt }) +
        count(local.courses, merged.courses, { it.id }, { it.updatedAt }) +
        count(local.blocks, merged.blocks, { it.id }, { it.updatedAt })
}

// ---- Entity ↔ DTO 映射 ----

internal fun TimetableEntity.toDto() = TimetableDto(
    id = id, name = name, createdAt = createdAt, updatedAt = updatedAt, deletedAt = deletedAt,
)

internal fun TimetableDto.toEntity() = TimetableEntity(
    id = id, name = name, createdAt = createdAt, updatedAt = updatedAt, deletedAt = deletedAt,
)

internal fun TermEntity.toDto() = TermDto(
    id = id, timetableId = timetableId, name = name, firstDayEpochDay = firstDayEpochDay,
    totalWeeks = totalWeeks, isCurrent = isCurrent, createdAt = createdAt, updatedAt = updatedAt,
    deletedAt = deletedAt,
)

internal fun TermDto.toEntity() = TermEntity(
    id = id, timetableId = timetableId, name = name, firstDayEpochDay = firstDayEpochDay,
    totalWeeks = totalWeeks, isCurrent = isCurrent, createdAt = createdAt, updatedAt = updatedAt,
    deletedAt = deletedAt,
)

internal fun CourseEntity.toDto() = CourseDto(
    id = id, termId = termId, name = name, teacher = teacher, note = note, colorIndex = colorIndex,
    createdAt = createdAt, updatedAt = updatedAt, deletedAt = deletedAt,
)

internal fun CourseDto.toEntity() = CourseEntity(
    id = id, termId = termId, name = name, teacher = teacher, note = note, colorIndex = colorIndex,
    createdAt = createdAt, updatedAt = updatedAt, deletedAt = deletedAt,
)

internal fun ScheduleBlockEntity.toDto() = BlockDto(
    id = id, courseId = courseId, termId = termId, startWeek = startWeek, endWeek = endWeek,
    weekType = weekType, dayOfWeek = dayOfWeek, startPeriod = startPeriod, endPeriod = endPeriod,
    location = location, createdAt = createdAt, updatedAt = updatedAt, deletedAt = deletedAt,
)

internal fun BlockDto.toEntity() = ScheduleBlockEntity(
    id = id, courseId = courseId, termId = termId, startWeek = startWeek, endWeek = endWeek,
    weekType = weekType, dayOfWeek = dayOfWeek, startPeriod = startPeriod, endPeriod = endPeriod,
    location = location, createdAt = createdAt, updatedAt = updatedAt, deletedAt = deletedAt,
)

internal fun PeriodTimeEntity.toDto() = PeriodTimeDto(
    termId = termId, periodIndex = periodIndex, startMinuteOfDay = startMinuteOfDay,
    endMinuteOfDay = endMinuteOfDay, session = session, updatedAt = updatedAt,
)

internal fun PeriodTimeDto.toEntity() = PeriodTimeEntity(
    termId = termId, periodIndex = periodIndex, startMinuteOfDay = startMinuteOfDay,
    endMinuteOfDay = endMinuteOfDay, session = session, updatedAt = updatedAt,
)
