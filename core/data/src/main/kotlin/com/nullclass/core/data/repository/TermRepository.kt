package com.nullclass.core.data.repository

import androidx.room.withTransaction
import com.nullclass.core.data.DefaultPeriodTimes
import com.nullclass.core.data.db.NullClassDatabase
import com.nullclass.core.data.db.dao.PeriodTimeDao
import com.nullclass.core.data.db.dao.TermDao
import com.nullclass.core.model.PeriodTime
import com.nullclass.core.model.Term
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** 学期仓库。 */
interface TermRepository {

    fun observeAll(): Flow<List<Term>>

    fun observeCurrent(): Flow<Term?>

    suspend fun getCurrent(): Term?

    suspend fun getById(termId: String): Term?

    /** 比当前学期开学日更早的最近一个学期（跨学期复制入口用）。 */
    suspend fun getPreviousTerm(): Term?

    /**
     * 新建或更新学期。
     * - 新建（id 为空）：生成 UUID、写默认节次模板、自动设为当前学期，返回 id
     * - 更新：periodTimes 整体重建
     */
    suspend fun upsert(term: Term, periodTimes: List<PeriodTime>): String

    suspend fun setCurrent(termId: String)

    fun observePeriodTimes(termId: String): Flow<List<PeriodTime>>

    suspend fun getPeriodTimes(termId: String): List<PeriodTime>

    /** 软删除学期（级联墓碑其课程与时间安排）。 */
    suspend fun deleteTerm(termId: String)
}

@Singleton
class TermRepositoryImpl @Inject constructor(
    private val db: NullClassDatabase,
    private val termDao: TermDao,
    private val periodTimeDao: PeriodTimeDao,
) : TermRepository {

    override fun observeAll(): Flow<List<Term>> =
        termDao.observeAll().map { list -> list.map { it.toModel() } }

    override fun observeCurrent(): Flow<Term?> =
        termDao.observeCurrent().map { it?.toModel() }

    override suspend fun getCurrent(): Term? = termDao.getCurrent()?.toModel()

    override suspend fun getById(termId: String): Term? = termDao.getById(termId)?.toModel()

    override suspend fun getPreviousTerm(): Term? {
        val current = termDao.getCurrent() ?: return null
        return termDao.getPreviousTerm(current.firstDayEpochDay)?.toModel()
    }

    override suspend fun upsert(term: Term, periodTimes: List<PeriodTime>): String {
        val now = System.currentTimeMillis()
        val termId = term.id.ifEmpty { UUID.randomUUID().toString() }
        val isNew = term.id.isEmpty()

        db.withTransaction {
            if (isNew) {
                termDao.upsert(
                    term.copy(id = termId).toEntity(createdAt = now, updatedAt = now, isCurrent = true),
                )
                termDao.setCurrent(termId, now)
            } else {
                val existing = termDao.getById(termId)
                termDao.upsert(
                    term.toEntity(
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
        termDao.setCurrent(termId, System.currentTimeMillis())
    }

    override fun observePeriodTimes(termId: String): Flow<List<PeriodTime>> =
        periodTimeDao.observeByTerm(termId).map { list -> list.map { it.toModel() } }

    override suspend fun getPeriodTimes(termId: String): List<PeriodTime> =
        periodTimeDao.getByTerm(termId).map { it.toModel() }

    override suspend fun deleteTerm(termId: String) {
        val now = System.currentTimeMillis()
        db.withTransaction {
            termDao.tombstoneTerm(termId, now)
            termDao.tombstoneCoursesOfTerm(termId, now)
            termDao.tombstoneBlocksOfTerm(termId, now)
        }
    }
}
