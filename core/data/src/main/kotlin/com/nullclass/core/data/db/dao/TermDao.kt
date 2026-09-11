package com.nullclass.core.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.nullclass.core.data.db.entity.TermEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TermDao {

    // ---- UI 查询 ----

    @Query("SELECT * FROM terms WHERE deletedAt IS NULL ORDER BY firstDayEpochDay DESC")
    fun observeAll(): Flow<List<TermEntity>>

    @Query("SELECT * FROM terms WHERE isCurrent = 1 AND deletedAt IS NULL LIMIT 1")
    fun observeCurrent(): Flow<TermEntity?>

    @Query("SELECT * FROM terms WHERE isCurrent = 1 AND deletedAt IS NULL LIMIT 1")
    suspend fun getCurrent(): TermEntity?

    @Query("SELECT * FROM terms WHERE id = :termId AND deletedAt IS NULL")
    suspend fun getById(termId: String): TermEntity?

    /** 开学日早于 [beforeEpochDay] 的最近一个学期（跨学期复制用）。 */
    @Query(
        "SELECT * FROM terms WHERE deletedAt IS NULL AND firstDayEpochDay < :beforeEpochDay " +
            "ORDER BY firstDayEpochDay DESC LIMIT 1",
    )
    suspend fun getPreviousTerm(beforeEpochDay: Long): TermEntity?

    /** 开学日最晚的未删学期（删除当前后回退用，与同步合并归一化一致）。 */
    @Query(
        "SELECT * FROM terms WHERE deletedAt IS NULL " +
            "ORDER BY firstDayEpochDay DESC LIMIT 1",
    )
    suspend fun getLatestByFirstDay(): TermEntity?

    // ---- 写入 ----

    @Upsert
    suspend fun upsert(term: TermEntity)

    @Transaction
    suspend fun setCurrent(termId: String, now: Long) {
        clearCurrent()
        markCurrent(termId, now)
    }

    @Query("UPDATE terms SET isCurrent = 0 WHERE isCurrent = 1")
    suspend fun clearCurrent()

    @Query("UPDATE terms SET isCurrent = 1, updatedAt = :now WHERE id = :termId")
    suspend fun markCurrent(termId: String, now: Long)

    @Query(
        "UPDATE terms SET deletedAt = :now, updatedAt = :now " +
            "WHERE id = :termId AND deletedAt IS NULL",
    )
    suspend fun tombstoneTerm(termId: String, now: Long)

    @Query(
        "UPDATE courses SET deletedAt = :now, updatedAt = :now " +
            "WHERE termId = :termId AND deletedAt IS NULL",
    )
    suspend fun tombstoneCoursesOfTerm(termId: String, now: Long)

    @Query(
        "UPDATE schedule_blocks SET deletedAt = :now, updatedAt = :now " +
            "WHERE termId = :termId AND deletedAt IS NULL",
    )
    suspend fun tombstoneBlocksOfTerm(termId: String, now: Long)

    // ---- 同步引擎 ----

    @Query("SELECT * FROM terms")
    suspend fun getAllTerms(): List<TermEntity>

    @Upsert
    suspend fun upsertAll(terms: List<TermEntity>)
}
