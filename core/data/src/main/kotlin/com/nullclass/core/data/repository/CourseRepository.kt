package com.nullclass.core.data.repository

import com.nullclass.core.data.db.dao.CourseDao
import com.nullclass.core.data.db.entity.CourseEntity
import com.nullclass.core.model.Course
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** 课程仓库。供 feature 层与 :widget 使用。 */
interface CourseRepository {

    /** 观察某学期的全部课程。 */
    fun observeCourses(termId: Long): Flow<List<Course>>

    /** 新增或更新一门课，返回课程 id。 */
    suspend fun upsert(course: Course): Long

    /** 删除一门课（其下所有时间安排级联删除）。 */
    suspend fun delete(courseId: Long)
}

@Singleton
class CourseRepositoryImpl @Inject constructor(
    private val courseDao: CourseDao,
) : CourseRepository {

    override fun observeCourses(termId: Long): Flow<List<Course>> =
        courseDao.observeCourses(termId).map { list -> list.map { it.toModel() } }

    override suspend fun upsert(course: Course): Long =
        courseDao.upsertCourse(course.toEntity())

    override suspend fun delete(courseId: Long) = courseDao.deleteCourse(courseId)
}

private fun CourseEntity.toModel() = Course(
    id = id,
    termId = termId,
    name = name,
    teacher = teacher,
    colorIndex = colorIndex,
)

private fun Course.toEntity() = CourseEntity(
    id = id,
    termId = termId,
    name = name,
    teacher = teacher,
    colorIndex = colorIndex,
)
