package com.nullclass.core.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.nullclass.core.data.db.entity.CalendarEventEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CalendarEventDao {

    @Query(
        "SELECT * FROM calendar_events ORDER BY dateEpochDay ASC, " +
            "CASE WHEN startMinuteOfDay IS NULL THEN 0 ELSE 1 END ASC, startMinuteOfDay ASC, id ASC",
    )
    fun observeAll(): Flow<List<CalendarEventEntity>>

    @Query("SELECT * FROM calendar_events WHERE dateEpochDay >= :fromEpochDay")
    suspend fun getFrom(fromEpochDay: Long): List<CalendarEventEntity>

    @Upsert
    suspend fun upsert(row: CalendarEventEntity)

    @Query("DELETE FROM calendar_events WHERE id = :id")
    suspend fun delete(id: String)
}
