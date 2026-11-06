package com.nullclass.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import com.nullclass.core.data.db.entity.ClassMoveEntity
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

    // 单次调课与串课同属「调课」，共用一个 DAO / 仓库
    @Query("SELECT * FROM class_moves ORDER BY fromEpochDay ASC, id ASC")
    fun observeMoves(): Flow<List<ClassMoveEntity>>

    @Query("SELECT * FROM class_moves ORDER BY fromEpochDay ASC, id ASC")
    suspend fun getMoves(): List<ClassMoveEntity>

    @Insert
    suspend fun insertMove(row: ClassMoveEntity)

    @Query("DELETE FROM class_moves WHERE id = :id")
    suspend fun deleteMove(id: Long)
}
