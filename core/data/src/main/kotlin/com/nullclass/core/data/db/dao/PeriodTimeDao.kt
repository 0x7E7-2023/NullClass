package com.nullclass.core.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.nullclass.core.data.db.entity.PeriodTimeEntity
import com.nullclass.core.data.db.entity.SyncStateEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PeriodTimeDao {

    @Query("SELECT * FROM period_times WHERE termId = :termId ORDER BY periodIndex")
    fun observeByTerm(termId: String): Flow<List<PeriodTimeEntity>>

    @Query("SELECT * FROM period_times WHERE termId = :termId ORDER BY periodIndex")
    suspend fun getByTerm(termId: String): List<PeriodTimeEntity>

    @Upsert
    suspend fun upsertAll(times: List<PeriodTimeEntity>)

    /** 编辑学期时整体重建节次表。 */
    @Query("DELETE FROM period_times WHERE termId = :termId")
    suspend fun deleteByTerm(termId: String)

    /** 同步应用合并快照时全量替换（节次表无墓碑，按学期整体取新）。 */
    @Query("DELETE FROM period_times")
    suspend fun deleteAll()

    // ---- 同步引擎 ----

    @Query("SELECT * FROM period_times")
    suspend fun getAll(): List<PeriodTimeEntity>
}

@Dao
interface SyncStateDao {

    @Query("SELECT * FROM sync_state WHERE `key` = :key")
    suspend fun get(key: String): SyncStateEntity?

    @Upsert
    suspend fun put(entry: SyncStateEntity)

    @Query("SELECT * FROM sync_state")
    suspend fun getAll(): List<SyncStateEntity>
}
