package com.nullclass.core.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.nullclass.core.data.db.entity.SkipDateEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SkipDateDao {

    @Query("SELECT * FROM skip_dates ORDER BY epochDay ASC")
    fun observeAll(): Flow<List<SkipDateEntity>>

    @Query("SELECT * FROM skip_dates ORDER BY epochDay ASC")
    suspend fun getAll(): List<SkipDateEntity>

    @Upsert
    suspend fun upsertAll(dates: List<SkipDateEntity>)

    @Query("DELETE FROM skip_dates WHERE epochDay = :epochDay")
    suspend fun delete(epochDay: Long)

    /** 同步刷新的替换范围：只动 HOLIDAY/WORKDAY 行，MANUAL 行永不被动。 */
    @Query(
        "DELETE FROM skip_dates WHERE type IN ('HOLIDAY', 'WORKDAY') " +
            "AND epochDay BETWEEN :fromDay AND :toDay",
    )
    suspend fun deleteSyncedInRange(fromDay: Long, toDay: Long)

    @Query(
        "SELECT epochDay FROM skip_dates WHERE type = 'MANUAL' " +
            "AND epochDay BETWEEN :fromDay AND :toDay",
    )
    suspend fun getManualDaysInRange(fromDay: Long, toDay: Long): List<Long>

    /**
     * 按年整体替换节假日来源的行（先删后插，同一事务）。
     * 与 MANUAL 行同日的同步行直接丢弃：主键都是 epochDay，@Upsert 冲突会把
     * MANUAL 行整行覆盖成 HOLIDAY，手动记录就丢了。
     */
    @Transaction
    suspend fun replaceSyncedRange(rows: List<SkipDateEntity>, fromDay: Long, toDay: Long) {
        val manualDays = getManualDaysInRange(fromDay, toDay).toHashSet()
        deleteSyncedInRange(fromDay, toDay)
        val insertable = rows.filter { it.epochDay !in manualDays }
        if (insertable.isNotEmpty()) upsertAll(insertable)
    }
}
