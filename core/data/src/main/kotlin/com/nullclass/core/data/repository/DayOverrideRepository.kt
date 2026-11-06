package com.nullclass.core.data.repository

import com.nullclass.core.data.db.dao.DayOverrideDao
import com.nullclass.core.data.db.entity.ClassMoveEntity
import com.nullclass.core.data.db.entity.DayOverrideEntity
import com.nullclass.core.model.ClassMove
import com.nullclass.core.model.DayOverride
import com.nullclass.core.model.DayOverrides
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 调课的唯一读写入口：串课（某天改上另一天的课）与单次调课（某一次课挪到别处）。
 *
 * 取数口径全部经由 [com.nullclass.core.model.DayOverrides]，今日页 / 周视图 /
 * 小组件 / 提醒四处共用同一份解析规则。
 */
@Singleton
class DayOverrideRepository @Inject constructor(
    private val dao: DayOverrideDao,
) {

    /** 全部串课记录，按日期升序（管理列表用）。 */
    val overrides: Flow<List<DayOverride>> =
        dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    /** 解析用查表：date → source date。 */
    val index: Flow<Map<Long, Long>> = overrides.map { DayOverrides.index(it) }

    /** 一次性读（小组件与提醒排算这类不 collect 的场合）。 */
    suspend fun indexNow(): Map<Long, Long> =
        DayOverrides.index(dao.getAll().map { it.toDomain() })

    /**
     * 设置串课：[epochDay] 这天上 [sourceEpochDay] 那天的课。
     * 来源就是它自己 = 取消串课（界面上「恢复原课表」与重复选中同一天是同一件事）。
     */
    suspend fun setOverride(epochDay: Long, sourceEpochDay: Long) {
        if (epochDay == sourceEpochDay) {
            clearOverride(epochDay)
            return
        }
        dao.upsert(DayOverrideEntity(epochDay, sourceEpochDay, System.currentTimeMillis()))
    }

    suspend fun clearOverride(epochDay: Long) {
        dao.delete(epochDay)
    }

    /** 全部单次调课，按原日期升序。 */
    val moves: Flow<List<ClassMove>> =
        dao.observeMoves().map { rows -> rows.map { it.toDomain() } }

    /** 串课查表 + 单次调课一起观察：取课的四处（今日页 / 周视图 / 小组件 / 提醒）都要两样。 */
    val adjustments: Flow<Pair<Map<Long, Long>, List<ClassMove>>> = combine(index, moves, ::Pair)

    suspend fun movesNow(): List<ClassMove> = dao.getMoves().map { it.toDomain() }

    suspend fun addMove(move: ClassMove) {
        dao.insertMove(
            ClassMoveEntity(
                blockId = move.blockId,
                fromEpochDay = move.fromEpochDay,
                toEpochDay = move.toEpochDay,
                toStartPeriod = move.toStartPeriod,
                toEndPeriod = move.toEndPeriod,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun deleteMove(id: Long) {
        dao.deleteMove(id)
    }

    private fun DayOverrideEntity.toDomain() =
        DayOverride(epochDay = epochDay, sourceEpochDay = sourceEpochDay)

    private fun ClassMoveEntity.toDomain() = ClassMove(
        id = id,
        blockId = blockId,
        fromEpochDay = fromEpochDay,
        toEpochDay = toEpochDay,
        toStartPeriod = toStartPeriod,
        toEndPeriod = toEndPeriod,
    )
}
