package com.nullclass.core.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.nullclass.core.data.db.entity.TimetableEntity
import kotlinx.coroutines.flow.Flow

/** 每张课表的学期数（课表管理列表显示）。 */
data class TimetableTermCount(
    val timetableId: String,
    val termCount: Int,
)

@Dao
interface TimetableDao {

    // ---- UI 查询 ----

    /** 创建最早者优先（迁移产生的默认课表排最前），同刻并列按 id 定序保证稳定。 */
    @Query("SELECT * FROM timetables WHERE deletedAt IS NULL ORDER BY createdAt ASC, id ASC")
    fun observeAll(): Flow<List<TimetableEntity>>

    @Query("SELECT * FROM timetables WHERE deletedAt IS NULL ORDER BY createdAt ASC, id ASC")
    suspend fun getAllLive(): List<TimetableEntity>

    @Query("SELECT * FROM timetables WHERE deletedAt IS NULL ORDER BY createdAt ASC, id ASC LIMIT 1")
    suspend fun getEarliestLive(): TimetableEntity?

    @Query("SELECT * FROM timetables WHERE deletedAt IS NULL AND id = :id LIMIT 1")
    suspend fun getById(id: String): TimetableEntity?

    @Query("SELECT COUNT(*) FROM timetables WHERE deletedAt IS NULL")
    suspend fun liveCount(): Int

    @Query(
        "SELECT timetableId, COUNT(*) AS termCount FROM terms " +
            "WHERE deletedAt IS NULL GROUP BY timetableId",
    )
    fun observeTermCounts(): Flow<List<TimetableTermCount>>

    // ---- 写入 ----

    @Upsert
    suspend fun upsert(timetable: TimetableEntity)

    @Query(
        "UPDATE timetables SET deletedAt = :now, updatedAt = :now " +
            "WHERE id = :id AND deletedAt IS NULL",
    )
    suspend fun tombstone(id: String, now: Long)

    // ---- 同步引擎 ----

    /** 含墓碑。 */
    @Query("SELECT * FROM timetables")
    suspend fun getAllTimetables(): List<TimetableEntity>

    @Upsert
    suspend fun upsertAll(timetables: List<TimetableEntity>)
}
