package com.nullclass.core.data.repository

import androidx.room.withTransaction
import com.nullclass.core.data.db.NullClassDatabase
import com.nullclass.core.data.db.dao.CourseDao
import com.nullclass.core.data.db.dao.ExamDao
import com.nullclass.core.model.Exam
import com.nullclass.core.model.ExamWithCourse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** 课程考试仓库。考试通过 courseId 归属课程，查询时自动带出课程信息。 */
interface ExamRepository {

    fun observeForTerm(termId: String): Flow<List<ExamWithCourse>>

    fun observeForCourse(courseId: String): Flow<List<ExamWithCourse>>

    suspend fun getForTerm(termId: String): List<ExamWithCourse>

    suspend fun getForCourse(courseId: String): List<ExamWithCourse>

    suspend fun getExam(examId: String): ExamWithCourse?

    /** 新增或更新考试，返回考试 id；courseId 必须指向一门活课程。 */
    suspend fun upsert(exam: Exam): String

    /** 软删除一场考试。 */
    suspend fun delete(examId: String)
}

@Singleton
class ExamRepositoryImpl @Inject constructor(
    private val db: NullClassDatabase,
    private val examDao: ExamDao,
    private val courseDao: CourseDao,
) : ExamRepository {

    override fun observeForTerm(termId: String): Flow<List<ExamWithCourse>> =
        examDao.observeByTerm(termId).map { list -> list.map { it.toModel() } }

    override fun observeForCourse(courseId: String): Flow<List<ExamWithCourse>> =
        examDao.observeByCourse(courseId).map { list -> list.map { it.toModel() } }

    override suspend fun getForTerm(termId: String): List<ExamWithCourse> =
        examDao.getByTerm(termId).map { it.toModel() }

    override suspend fun getForCourse(courseId: String): List<ExamWithCourse> =
        examDao.getByCourse(courseId).map { it.toModel() }

    override suspend fun getExam(examId: String): ExamWithCourse? =
        examDao.getWithCourse(examId)?.takeIf { it.course.deletedAt == null }?.toModel()

    override suspend fun upsert(exam: Exam): String {
        val course = courseDao.getById(exam.courseId)
        require(course != null && course.deletedAt == null) { "所属课程不存在" }
        val now = System.currentTimeMillis()
        val examId = exam.id.ifEmpty { UUID.randomUUID().toString() }
        val existing = examDao.getById(examId)
        db.withTransaction {
            examDao.upsert(
                exam.copy(id = examId).toEntity(
                    createdAt = existing?.createdAt ?: now,
                    updatedAt = now,
                ),
            )
        }
        return examId
    }

    override suspend fun delete(examId: String) {
        examDao.tombstone(examId, System.currentTimeMillis())
    }
}
