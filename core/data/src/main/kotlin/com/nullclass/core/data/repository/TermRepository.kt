package com.nullclass.core.data.repository

import androidx.room.withTransaction
import com.nullclass.core.data.db.NullClassDatabase
import com.nullclass.core.data.db.dao.PeriodTimeDao
import com.nullclass.core.data.db.dao.TermDao
import com.nullclass.core.data.db.dao.TimetableDao
import com.nullclass.core.data.prefs.UserPreferencesRepository
import com.nullclass.core.model.PeriodTime
import com.nullclass.core.model.Term
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 学期仓库。**所有查询按「当前课表」作用域化**：今日页、周视图、小组件、提醒、
 * 我的页卡片全经由这里取数，作用域收敛在仓库内部之后，上层调用点一行不用改。
 * 学期归属哪张课表（timetableId）对上层不可见——领域模型 [Term] 不携带它。
 */
interface TermRepository {

    fun observeAll(): Flow<List<Term>>

    fun observeCurrent(): Flow<Term?>

    suspend fun getCurrent(): Term?

    suspend fun getById(termId: String): Term?

    /** 比当前学期开学日更早的最近一个学期（跨学期复制入口用）。 */
    suspend fun getPreviousTerm(): Term?

    /**
     * 新建或更新学期。
     * - 新建（id 为空）：生成 UUID、落到当前课表、写默认节次模板、自动设为当前学期，返回 id
     * - 更新：periodTimes 整体重建，归属课表沿用原值
     */
    suspend fun upsert(term: Term, periodTimes: List<PeriodTime>): String

    suspend fun setCurrent(termId: String)

    fun observePeriodTimes(termId: String): Flow<List<PeriodTime>>

    suspend fun getPeriodTimes(termId: String): List<PeriodTime>

    /**
     * 软删除学期（级联墓碑其课程与时间安排）。
     * 若删的是当前学期，激活开学日最晚的剩余学期；删光则没有当前学期。
     */
    suspend fun deleteTerm(termId: String)
}

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class TermRepositoryImpl @Inject constructor(
    private val db: NullClassDatabase,
    private val termDao: TermDao,
    private val periodTimeDao: PeriodTimeDao,
    private val timetableDao: TimetableDao,
    private val userPreferences: UserPreferencesRepository,
) : TermRepository {

    /** 当前课表 id 的可观察版（与 TimetableRepository.observeActive 同一套自愈规则）。 */
    private fun observeActiveId(): Flow<String?> =
        combine(userPreferences.activeTimetableId, timetableDao.observeAll()) { prefId, all ->
            (all.firstOrNull { it.id == prefId } ?: all.firstOrNull())?.id
        }.distinctUntilChanged()

    private suspend fun activeTimetableId(): String? {
        val prefId = userPreferences.activeTimetableId.first()
        prefId?.let { id -> timetableDao.getById(id)?.let { return id } }
        return timetableDao.getEarliestLive()?.also { userPreferences.setActiveTimetableId(it.id) }?.id
    }

    override fun observeAll(): Flow<List<Term>> =
        observeActiveId().flatMapLatest { id ->
            if (id == null) flowOf(emptyList())
            else termDao.observeAllOf(id).map { list -> list.map { it.toModel() } }
        }

    override fun observeCurrent(): Flow<Term?> =
        observeActiveId().flatMapLatest { id ->
            if (id == null) flowOf(null)
            else termDao.observeCurrentOf(id).map { it?.toModel() }
        }

    override suspend fun getCurrent(): Term? {
        val id = activeTimetableId() ?: return null
        return termDao.getCurrentOf(id)?.toModel()
    }

    override suspend fun getById(termId: String): Term? = termDao.getById(termId)?.toModel()

    override suspend fun getPreviousTerm(): Term? {
        val activeId = activeTimetableId() ?: return null
        val current = termDao.getCurrentOf(activeId) ?: return null
        return termDao.getPreviousTerm(activeId, current.firstDayEpochDay)?.toModel()
    }

    override suspend fun upsert(term: Term, periodTimes: List<PeriodTime>): String {
        val now = System.currentTimeMillis()
        val termId = term.id.ifEmpty { UUID.randomUUID().toString() }
        val isNew = term.id.isEmpty()

        db.withTransaction {
            if (isNew) {
                val timetableId = activeTimetableId()
                    ?: error("没有课表可放学期——新装应用应先创建课表")
                termDao.upsert(
                    term.copy(id = termId).toEntity(
                        timetableId = timetableId,
                        createdAt = now,
                        updatedAt = now,
                        isCurrent = true,
                    ),
                )
                termDao.setCurrent(timetableId, termId, now)
            } else {
                val existing = termDao.getById(termId)
                // 归属沿用原值：编辑「当前课表」里的学期不会把它搬到别的课表
                val timetableId = existing?.timetableId ?: activeTimetableId().orEmpty()
                termDao.upsert(
                    term.toEntity(
                        timetableId = timetableId,
                        createdAt = existing?.createdAt ?: now,
                        updatedAt = now,
                        isCurrent = existing?.isCurrent ?: false,
                    ),
                )
            }
            periodTimeDao.deleteByTerm(termId)
            periodTimeDao.upsertAll(
                periodTimes.map { it.copy(termId = termId).toEntity(updatedAt = now) },
            )
        }
        return termId
    }

    override suspend fun setCurrent(termId: String) {
        val term = termDao.getById(termId) ?: return
        termDao.setCurrent(term.timetableId, termId, System.currentTimeMillis())
    }

    override fun observePeriodTimes(termId: String): Flow<List<PeriodTime>> =
        periodTimeDao.observeByTerm(termId).map { list -> list.map { it.toModel() } }

    override suspend fun getPeriodTimes(termId: String): List<PeriodTime> =
        periodTimeDao.getByTerm(termId).map { it.toModel() }

    override suspend fun deleteTerm(termId: String) {
        val now = System.currentTimeMillis()
        db.withTransaction {
            val timetableId = termDao.getById(termId)?.timetableId
                ?: activeTimetableId()
                ?: return@withTransaction
            termDao.tombstoneTerm(termId, now)
            termDao.tombstoneCoursesOfTerm(termId, now)
            termDao.tombstoneBlocksOfTerm(termId, now)
            if (termDao.getCurrentOf(timetableId) == null) {
                termDao.getLatestByFirstDay(timetableId)?.let { next ->
                    termDao.setCurrent(timetableId, next.id, now)
                }
            }
        }
    }
}
