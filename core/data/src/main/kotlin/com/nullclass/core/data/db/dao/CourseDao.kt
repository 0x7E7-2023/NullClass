package com.nullclass.core.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.nullclass.core.data.db.entity.CourseEntity
import com.nullclass.core.data.db.entity.CourseWithBlocksEntity
import com.nullclass.core.data.db.entity.ScheduleBlockEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CourseDao {

    // ---- UI 查询（一律过滤墓碑） ----

    @Transaction
    @Query("SELECT * FROM courses WHERE termId = :termId AND deletedAt IS NULL ORDER BY name")
    fun observeSchedule(termId: String): Flow<List<CourseWithBlocksEntity>>

    /** 一次性取某学期全量课表（保存前查重用，不做 Flow 观察）。 */
    @Transaction
    @Query("SELECT * FROM courses WHERE termId = :termId AND deletedAt IS NULL ORDER BY name")
    suspend fun getSchedule(termId: String): List<CourseWithBlocksEntity>

    @Transaction
    @Query(
        "SELECT * FROM courses WHERE id = :courseId AND deletedAt IS NULL",
    )
    suspend fun getCourseWithBlocks(courseId: String): CourseWithBlocksEntity?

    @Query("SELECT * FROM courses WHERE id = :courseId")
    suspend fun getById(courseId: String): CourseEntity?

    @Query("SELECT * FROM schedule_blocks WHERE id = :blockId")
    suspend fun getBlockById(blockId: String): ScheduleBlockEntity?

    // ---- 写入 ----

    @Upsert
    suspend fun upsertCourse(course: CourseEntity)

    @Upsert
    suspend fun upsertBlocks(blocks: List<ScheduleBlockEntity>)

    /** 软删除（墓碑），同步时传播到对端。 */
    @Query(
        "UPDATE courses SET deletedAt = :now, updatedAt = :now " +
            "WHERE id = :courseId AND deletedAt IS NULL",
    )
    suspend fun tombstoneCourse(courseId: String, now: Long)

    @Query(
        "UPDATE schedule_blocks SET deletedAt = :now, updatedAt = :now " +
            "WHERE courseId = :courseId AND deletedAt IS NULL",
    )
    suspend fun tombstoneBlocksOfCourse(courseId: String, now: Long)

    /** 编辑后清理不再存在的安排（同样走墓碑，保同步）。keepIds 为空时会退化为整课置墓碑。 */
    @Query(
        "UPDATE schedule_blocks SET deletedAt = :now, updatedAt = :now " +
            "WHERE courseId = :courseId AND deletedAt IS NULL AND id NOT IN (:keepIds)",
    )
    suspend fun tombstoneStaleBlocks(courseId: String, keepIds: List<String>, now: Long)

    // ---- 跨学期复制 ----

    @Query("SELECT * FROM courses WHERE termId = :fromTermId AND deletedAt IS NULL")
    suspend fun getCoursesOfTerm(fromTermId: String): List<CourseEntity>

    @Query(
        "SELECT * FROM schedule_blocks WHERE termId = :fromTermId AND deletedAt IS NULL",
    )
    suspend fun getBlocksOfTerm(fromTermId: String): List<ScheduleBlockEntity>

    // ---- 同步引擎（含墓碑，全量读写） ----

    @Query("SELECT * FROM courses")
    suspend fun getAllCourses(): List<CourseEntity>

    @Query("SELECT * FROM schedule_blocks")
    suspend fun getAllBlocks(): List<ScheduleBlockEntity>

    @Upsert
    suspend fun upsertAllCourses(courses: List<CourseEntity>)

    @Upsert
    suspend fun upsertAllBlocks(blocks: List<ScheduleBlockEntity>)
}
