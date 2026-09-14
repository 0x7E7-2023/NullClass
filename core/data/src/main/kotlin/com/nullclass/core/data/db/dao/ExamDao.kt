package com.nullclass.core.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.nullclass.core.data.db.entity.ExamEntity
import com.nullclass.core.data.db.entity.ExamWithCourseEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ExamDao {

    // ---- UI 查询（只看活着的考试和活着的课程） ----

    @Transaction
    @Query(
        "SELECT exams.* FROM exams " +
            "INNER JOIN courses ON courses.id = exams.courseId " +
            "WHERE courses.termId = :termId AND courses.deletedAt IS NULL " +
            "AND exams.deletedAt IS NULL " +
            "ORDER BY exams.dateEpochDay ASC, " +
            "CASE WHEN exams.startMinuteOfDay IS NULL THEN 1 ELSE 0 END ASC, " +
            "exams.startMinuteOfDay ASC, exams.id ASC",
    )
    fun observeByTerm(termId: String): Flow<List<ExamWithCourseEntity>>

    @Transaction
    @Query(
        "SELECT exams.* FROM exams " +
            "INNER JOIN courses ON courses.id = exams.courseId " +
            "WHERE courses.termId = :termId AND courses.deletedAt IS NULL " +
            "AND exams.deletedAt IS NULL " +
            "ORDER BY exams.dateEpochDay ASC, " +
            "CASE WHEN exams.startMinuteOfDay IS NULL THEN 1 ELSE 0 END ASC, " +
            "exams.startMinuteOfDay ASC, exams.id ASC",
    )
    suspend fun getByTerm(termId: String): List<ExamWithCourseEntity>

    @Transaction
    @Query(
        "SELECT exams.* FROM exams " +
            "INNER JOIN courses ON courses.id = exams.courseId " +
            "WHERE exams.courseId = :courseId AND courses.deletedAt IS NULL " +
            "AND exams.deletedAt IS NULL " +
            "ORDER BY exams.dateEpochDay ASC, " +
            "CASE WHEN exams.startMinuteOfDay IS NULL THEN 1 ELSE 0 END ASC, " +
            "exams.startMinuteOfDay ASC, exams.id ASC",
    )
    fun observeByCourse(courseId: String): Flow<List<ExamWithCourseEntity>>

    @Transaction
    @Query(
        "SELECT exams.* FROM exams " +
            "INNER JOIN courses ON courses.id = exams.courseId " +
            "WHERE exams.courseId = :courseId AND courses.deletedAt IS NULL " +
            "AND exams.deletedAt IS NULL " +
            "ORDER BY exams.dateEpochDay ASC, " +
            "CASE WHEN exams.startMinuteOfDay IS NULL THEN 1 ELSE 0 END ASC, " +
            "exams.startMinuteOfDay ASC, exams.id ASC",
    )
    suspend fun getByCourse(courseId: String): List<ExamWithCourseEntity>

    @Transaction
    @Query("SELECT * FROM exams WHERE id = :examId AND deletedAt IS NULL")
    suspend fun getWithCourse(examId: String): ExamWithCourseEntity?

    @Query("SELECT * FROM exams WHERE id = :examId")
    suspend fun getById(examId: String): ExamEntity?

    // ---- 写入 ----

    @Upsert
    suspend fun upsert(exam: ExamEntity)

    @Query(
        "UPDATE exams SET deletedAt = :now, updatedAt = :now " +
            "WHERE id = :examId AND deletedAt IS NULL",
    )
    suspend fun tombstone(examId: String, now: Long)

    @Query(
        "UPDATE exams SET deletedAt = :now, updatedAt = :now " +
            "WHERE courseId = :courseId AND deletedAt IS NULL",
    )
    suspend fun tombstoneOfCourse(courseId: String, now: Long)

    @Query(
        "UPDATE exams SET deletedAt = :now, updatedAt = :now " +
            "WHERE deletedAt IS NULL AND courseId IN (" +
            "SELECT id FROM courses WHERE termId = :termId)",
    )
    suspend fun tombstoneOfTerm(termId: String, now: Long)

    @Query(
        "UPDATE exams SET deletedAt = :now, updatedAt = :now " +
            "WHERE deletedAt IS NULL AND courseId IN (" +
            "SELECT id FROM courses WHERE termId IN (" +
            "SELECT id FROM terms WHERE timetableId = :timetableId))",
    )
    suspend fun tombstoneOfTimetable(timetableId: String, now: Long)

    // ---- 同步引擎（含墓碑） ----

    @Query("SELECT * FROM exams")
    suspend fun getAll(): List<ExamEntity>

    @Upsert
    suspend fun upsertAll(exams: List<ExamEntity>)
}
