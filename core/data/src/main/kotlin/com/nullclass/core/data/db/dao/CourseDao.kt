package com.nullclass.core.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.nullclass.core.data.db.entity.CourseEntity
import com.nullclass.core.data.db.entity.ScheduleBlockEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CourseDao {

    @Query("SELECT * FROM courses WHERE termId = :termId ORDER BY name")
    fun observeCourses(termId: Long): Flow<List<CourseEntity>>

    @Query("SELECT * FROM schedule_blocks WHERE courseId IN (:courseIds)")
    fun observeBlocks(courseIds: List<Long>): Flow<List<ScheduleBlockEntity>>

    @Upsert
    suspend fun upsertCourse(course: CourseEntity): Long

    @Upsert
    suspend fun upsertBlocks(blocks: List<ScheduleBlockEntity>)

    @Query("DELETE FROM schedule_blocks WHERE courseId = :courseId AND id NOT IN (:keepIds)")
    suspend fun deleteStaleBlocks(courseId: Long, keepIds: List<Long>)

    @Transaction
    suspend fun deleteCourse(courseId: Long) {
        deleteBlocksOf(courseId)
        deleteCourseRow(courseId)
    }

    @Query("DELETE FROM schedule_blocks WHERE courseId = :courseId")
    suspend fun deleteBlocksOf(courseId: Long)

    @Query("DELETE FROM courses WHERE id = :courseId")
    suspend fun deleteCourseRow(courseId: Long)
}
