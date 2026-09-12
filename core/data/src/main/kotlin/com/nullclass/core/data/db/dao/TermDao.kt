package com.nullclass.core.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.nullclass.core.data.db.entity.TermEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TermDao {

    // ---- UI 查询（一律按课表作用域：UI 永远只见「当前课表」里的学期）----

    @Query(
        "SELECT * FROM terms WHERE timetableId = :timetableId AND deletedAt IS NULL " +
            "ORDER BY firstDayEpochDay DESC",
    )
    fun observeAllOf(timetableId: String): Flow<List<TermEntity>>

    @Query(
        "SELECT * FROM terms WHERE timetableId = :timetableId AND isCurrent = 1 AND deletedAt IS NULL " +
            "LIMIT 1",
    )
    fun observeCurrentOf(timetableId: String): Flow<TermEntity?>

    @Query(
        "SELECT * FROM terms WHERE timetableId = :timetableId AND isCurrent = 1 AND deletedAt IS NULL " +
            "LIMIT 1",
    )
    suspend fun getCurrentOf(timetableId: String): TermEntity?

    @Query("SELECT * FROM terms WHERE id = :termId AND deletedAt IS NULL")
    suspend fun getById(termId: String): TermEntity?

    /** 开学日早于 [beforeEpochDay] 的、**同课表内**最近一个学期（跨学期复制用）。 */
    @Query(
        "SELECT * FROM terms WHERE deletedAt IS NULL AND timetableId = :timetableId " +
            "AND firstDayEpochDay < :beforeEpochDay ORDER BY firstDayEpochDay DESC LIMIT 1",
    )
    suspend fun getPreviousTerm(timetableId: String, beforeEpochDay: Long): TermEntity?

    /** 开学日最晚的未删学期（删除当前后回退用，与同步合并归一化一致）。 */
    @Query(
        "SELECT * FROM terms WHERE deletedAt IS NULL AND timetableId = :timetableId " +
            "ORDER BY firstDayEpochDay DESC LIMIT 1",
    )
    suspend fun getLatestByFirstDay(timetableId: String): TermEntity?

    // ---- 写入 ----

    @Upsert
    suspend fun upsert(term: TermEntity)

    /** 只清这张课表内的当前标记：别的课表各自的「当前学期」互不相干。 */
    @Transaction
    suspend fun setCurrent(timetableId: String, termId: String, now: Long) {
        clearCurrent(timetableId)
        markCurrent(termId, now)
    }

    @Query("UPDATE terms SET isCurrent = 0 WHERE isCurrent = 1 AND timetableId = :timetableId")
    suspend fun clearCurrent(timetableId: String)

    @Query("UPDATE terms SET isCurrent = 1, updatedAt = :now WHERE id = :termId")
    suspend fun markCurrent(termId: String, now: Long)

    @Query(
        "UPDATE terms SET deletedAt = :now, updatedAt = :now " +
            "WHERE id = :termId AND deletedAt IS NULL",
    )
    suspend fun tombstoneTerm(termId: String, now: Long)

    @Query(
        "UPDATE terms SET deletedAt = :now, updatedAt = :now " +
            "WHERE timetableId = :timetableId AND deletedAt IS NULL",
    )
    suspend fun tombstoneTermsOfTimetable(timetableId: String, now: Long)

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

    // ---- 删除课表的级联墓碑（按课表一揽子打墓碑，含已删学期的残留课程）----

    @Query(
        "UPDATE courses SET deletedAt = :now, updatedAt = :now WHERE deletedAt IS NULL " +
            "AND termId IN (SELECT id FROM terms WHERE timetableId = :timetableId)",
    )
    suspend fun tombstoneCoursesOfTimetable(timetableId: String, now: Long)

    @Query(
        "UPDATE schedule_blocks SET deletedAt = :now, updatedAt = :now WHERE deletedAt IS NULL " +
            "AND termId IN (SELECT id FROM terms WHERE timetableId = :timetableId)",
    )
    suspend fun tombstoneBlocksOfTimetable(timetableId: String, now: Long)

    // ---- 同步引擎 ----

    @Query("SELECT * FROM terms")
    suspend fun getAllTerms(): List<TermEntity>

    @Upsert
    suspend fun upsertAll(terms: List<TermEntity>)
}
