package com.nullclass.core.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.nullclass.core.data.db.entity.TermEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TermDao {

    @Query("SELECT * FROM terms ORDER BY firstDayEpochDay DESC")
    fun observeAll(): Flow<List<TermEntity>>

    @Query("SELECT * FROM terms WHERE isCurrent = 1 LIMIT 1")
    fun observeCurrent(): Flow<TermEntity?>

    @Upsert
    suspend fun upsert(term: TermEntity): Long

    @Query("UPDATE terms SET isCurrent = 0")
    suspend fun clearCurrent()

    @Query("UPDATE terms SET isCurrent = 1 WHERE id = :termId")
    suspend fun setCurrent(termId: Long)

    @Query("DELETE FROM terms WHERE id = :termId")
    suspend fun delete(termId: Long)
}
