package com.nullclass.sync

import androidx.room.withTransaction
import com.nullclass.core.data.db.NullClassDatabase
import com.nullclass.core.data.db.dao.CourseDao
import com.nullclass.core.data.db.dao.PeriodTimeDao
import com.nullclass.core.data.db.dao.TermDao
import com.nullclass.core.data.db.entity.CourseEntity
import com.nullclass.core.data.db.entity.PeriodTimeEntity
import com.nullclass.core.data.db.entity.ScheduleBlockEntity
import com.nullclass.core.data.db.entity.TermEntity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** 同步结果（设置页展示用）。 */
sealed interface SyncResult {
    data class Success(
        /** 合并后从远端采纳的记录数（新增+更新）。 */
        val adoptedFromRemote: Int,
        val rev: Long,
    ) : SyncResult

    data class NotConfigured(val message: String = "未配置 WebDAV") : SyncResult
    data class Error(val message: String) : SyncResult
}

/**
 * 同步管理器：Pull → Merge → Apply → Push（手动触发，互斥防重入）。
 * 流程详见 docs/impl 2.3。
 */
@Singleton
class SyncManager @Inject constructor(
    private val db: NullClassDatabase,
    private val termDao: TermDao,
    private val courseDao: CourseDao,
    private val periodTimeDao: PeriodTimeDao,
    private val settings: SyncSettingsRepository,
) {

    private val mutex = Mutex()

    suspend fun sync(): SyncResult = mutex.withLock {
        val config = settings.getConfig()
            ?: return SyncResult.NotConfigured()
        config.validate()?.let { return SyncResult.Error(it) }

        val client = WebDavClient(config)
        return try {
            val now = System.currentTimeMillis()
            val local = dump(settings.deviceId(), now)

            val downloaded = client.download()
            val merged: SnapshotDto
            val adopted: Int
            if (downloaded == null) {
                // 远端为空：首次同步，直接上传本地
                merged = local
                adopted = 0
            } else {
                merged = SyncEngine.merge(local, downloaded.snapshot, now)
                adopted = countAdopted(local, merged)
                apply(merged)
            }

            val previousRev = if (downloaded == null) settings.getLastRev() else downloaded.manifest.rev
            client.upload(merged, previousRev)
            settings.setLastSync(now, previousRev + 1)

            SyncResult.Success(adoptedFromRemote = adopted, rev = previousRev + 1)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 协程取消必须重抛，不能伪装成同步失败（B4）
            throw e
        } catch (e: Exception) {
            SyncResult.Error(e.message ?: e.javaClass.simpleName)
        }
    }

    suspend fun testConnection(): WebDavResult {
        val config = settings.getConfig() ?: return WebDavResult.Error("未配置 WebDAV")
        config.validate()?.let { return WebDavResult.Error(it) }
        return WebDavClient(config).testConnection()
    }

    /** 全量导出本地库（含墓碑）。 */
    private suspend fun dump(deviceId: String, now: Long): SnapshotDto = SnapshotDto(
        deviceId = deviceId,
        generatedAt = now,
        terms = termDao.getAllTerms().map { it.toDto() },
        courses = courseDao.getAllCourses().map { it.toDto() },
        blocks = courseDao.getAllBlocks().map { it.toDto() },
        periodTimes = periodTimeDao.getAll().map { it.toDto() },
    )

    /** 合并结果写回本地库（Upsert 全量；节次表整体替换；UI 的 Flow 自动刷新）。 */
    private suspend fun apply(snapshot: SnapshotDto) {
        db.withTransaction {
            termDao.upsertAll(snapshot.terms.map { it.toEntity() })
            courseDao.upsertAllCourses(snapshot.courses.map { it.toEntity() })
            courseDao.upsertAllBlocks(snapshot.blocks.map { it.toEntity() })
            // 节次表随学期整体取新：必须先清空，否则被删节次残留本地
            periodTimeDao.deleteAll()
            periodTimeDao.upsertAll(snapshot.periodTimes.map { it.toEntity() })
        }
    }

    /** 统计本地没有或本地更旧的记录数（不含 periodTimes，口径粗粒度够用）。 */
    private fun countAdopted(local: SnapshotDto, merged: SnapshotDto): Int {
        fun <T> count(localList: List<T>, mergedList: List<T>, id: (T) -> String, updatedAt: (T) -> Long): Int {
            val localMap = localList.associateBy(id)
            return mergedList.count { merged ->
                val existing = localMap[id(merged)]
                existing == null || updatedAt(merged) > updatedAt(existing)
            }
        }
        return count(local.terms, merged.terms, { it.id }, { it.updatedAt }) +
            count(local.courses, merged.courses, { it.id }, { it.updatedAt }) +
            count(local.blocks, merged.blocks, { it.id }, { it.updatedAt })
    }
}

// ---- Entity ↔ DTO 映射 ----

internal fun TermEntity.toDto() = TermDto(
    id = id, name = name, firstDayEpochDay = firstDayEpochDay, totalWeeks = totalWeeks,
    isCurrent = isCurrent, createdAt = createdAt, updatedAt = updatedAt, deletedAt = deletedAt,
)

internal fun TermDto.toEntity() = TermEntity(
    id = id, name = name, firstDayEpochDay = firstDayEpochDay, totalWeeks = totalWeeks,
    isCurrent = isCurrent, createdAt = createdAt, updatedAt = updatedAt, deletedAt = deletedAt,
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
