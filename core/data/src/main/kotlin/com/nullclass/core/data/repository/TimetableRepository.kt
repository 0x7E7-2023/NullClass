package com.nullclass.core.data.repository

import androidx.room.withTransaction
import com.nullclass.core.data.db.NullClassDatabase
import com.nullclass.core.data.db.dao.PeriodTimeDao
import com.nullclass.core.data.db.dao.TermDao
import com.nullclass.core.data.db.dao.TimetableDao
import com.nullclass.core.data.db.entity.TimetableEntity
import com.nullclass.core.data.prefs.UserPreferencesRepository
import com.nullclass.core.model.PeriodTime
import com.nullclass.core.model.Term
import com.nullclass.core.model.Timetable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** 课表管理列表的一行。 */
data class TimetableOverview(
    val timetable: Timetable,
    /** 这张课表里未删的学期数。 */
    val termCount: Int,
)

/** 课表仓库：课表本身与「当前课表」的落点。 */
interface TimetableRepository {

    fun observeOverviews(): Flow<List<TimetableOverview>>

    /**
     * 当前课表（自愈）：偏好里的 id 指向已删课表时回落到创建最早的一张——
     * 同步删掉当前课表后 UI 不会停在一张不存在的课表上。库里没有课表时为 null（首次启动引导）。
     */
    fun observeActive(): Flow<Timetable?>

    /** 一次性解析当前课表 id；偏好失效时回落并写回。没有课表（全新安装）时为 null。 */
    suspend fun getActiveId(): String?

    suspend fun getById(id: String): Timetable?

    /** 切为当前课表（仅写本地偏好，不同步）。 */
    suspend fun setActive(id: String)

    /**
     * 新建课表并**同事务**连带建它的第一个学期（默认节次表），全部落库后才切为当前，返回课表 id。
     *
     * 为什么不拆成 create + upsert 两步：create 一提交，首启闸门就放行、引导页随之卸载——
     * 第二步再失败就成了「报错弹给一块不存在的屏幕 + 库里留下一张没有学期的课表」，
     * 而且重试会把 create 再跑一遍，多出一张同名课表。同事务要么都有、要么都没有。
     */
    suspend fun createWithFirstTerm(timetableName: String, term: Term, periodTimes: List<PeriodTime>): String

    /** 重命名（同步语义的普通更新）。 */
    suspend fun rename(id: String, name: String)

    /**
     * 软删除课表（级联墓碑其学期/课程/课块，墓碑随同步传播）。
     * **最后一张不允许删**（返回 false）——删光了「当前课表」就没了落点。
     * 删的是当前课表时切到剩下最早的一张。
     */
    suspend fun delete(id: String): Boolean
}

@Singleton
class TimetableRepositoryImpl @Inject constructor(
    private val db: NullClassDatabase,
    private val timetableDao: TimetableDao,
    private val termDao: TermDao,
    private val periodTimeDao: PeriodTimeDao,
    private val userPreferences: UserPreferencesRepository,
) : TimetableRepository {

    override fun observeOverviews(): Flow<List<TimetableOverview>> =
        combine(timetableDao.observeAll(), timetableDao.observeTermCounts()) { all, counts ->
            val countByTimetable = counts.associate { it.timetableId to it.termCount }
            all.map { it.toModel() }.map { timetable ->
                TimetableOverview(timetable = timetable, termCount = countByTimetable[timetable.id] ?: 0)
            }
        }

    override fun observeActive(): Flow<Timetable?> =
        combine(userPreferences.activeTimetableId, timetableDao.observeAll()) { prefId, all ->
            (all.firstOrNull { it.id == prefId } ?: all.firstOrNull())?.toModel()
        }.distinctUntilChanged()

    override suspend fun getActiveId(): String? {
        val prefId = userPreferences.activeTimetableId.first()
        prefId?.let { id -> timetableDao.getById(id)?.let { return id } }
        val fallback = timetableDao.getEarliestLive() ?: return null
        userPreferences.setActiveTimetableId(fallback.id)
        return fallback.id
    }

    override suspend fun getById(id: String): Timetable? = timetableDao.getById(id)?.toModel()

    override suspend fun setActive(id: String) {
        if (timetableDao.getById(id) == null) return
        userPreferences.setActiveTimetableId(id)
    }

    override suspend fun createWithFirstTerm(
        timetableName: String,
        term: Term,
        periodTimes: List<PeriodTime>,
    ): String {
        val trimmed = timetableName.trim()
        require(trimmed.isNotEmpty()) { "课表名不能为空" }
        val timetableId = UUID.randomUUID().toString()
        val termId = term.id.ifEmpty { UUID.randomUUID().toString() }
        val now = System.currentTimeMillis()
        db.withTransaction {
            timetableDao.upsert(
                TimetableEntity(id = timetableId, name = trimmed, createdAt = now, updatedAt = now, deletedAt = null),
            )
            termDao.upsert(
                term.copy(id = termId).toEntity(
                    timetableId = timetableId,
                    createdAt = now,
                    updatedAt = now,
                    isCurrent = true,
                ),
            )
            periodTimeDao.deleteByTerm(termId)
            periodTimeDao.upsertAll(periodTimes.map { it.copy(termId = termId).toEntity(updatedAt = now) })
        }
        // 切当前课表放在事务外（DataStore 与 Room 无相互阻塞）；此时课表必然已存在
        userPreferences.setActiveTimetableId(timetableId)
        return timetableId
    }

    override suspend fun rename(id: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        val existing = timetableDao.getById(id) ?: return
        timetableDao.upsert(
            existing.copy(name = trimmed, updatedAt = System.currentTimeMillis()),
        )
    }

    override suspend fun delete(id: String): Boolean {
        val now = System.currentTimeMillis()
        return db.withTransaction {
            // 最后一张不删：删光后「当前课表」没了落点，引导页也不该在升级用户面前复活
            if (timetableDao.liveCount() <= 1) return@withTransaction false
            val activeId = getActiveId()
            timetableDao.tombstone(id, now)
            termDao.tombstoneTermsOfTimetable(id, now)
            termDao.tombstoneCoursesOfTimetable(id, now)
            termDao.tombstoneBlocksOfTimetable(id, now)
            if (activeId == id) {
                timetableDao.getEarliestLive()?.let { next ->
                    userPreferences.setActiveTimetableId(next.id)
                }
            }
            true
        }
    }
}
