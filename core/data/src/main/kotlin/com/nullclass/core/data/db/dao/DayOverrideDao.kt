package com.nullclass.core.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.nullclass.core.data.db.entity.DayOverrideEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DayOverrideDao {

    @Query("SELECT * FROM day_overrides ORDER BY epochDay ASC")
    fun observeAll(): Flow<List<DayOverrideEntity>>

    @Query("SELECT * FROM day_overrides ORDER BY epochDay ASC")
    suspend fun getAll(): List<DayOverrideEntity>

    @Upsert
    suspend fun upsert(row: DayOverrideEntity)

    @Query("DELETE FROM day_overrides WHERE epochDay = :epochDay")
    suspend fun delete(epochDay: Long)
}
