package com.nullclass.core.data.repository

import androidx.room.withTransaction
import com.nullclass.core.data.db.NullClassDatabase
import com.nullclass.core.data.db.dao.CourseDao
import com.nullclass.core.data.db.entity.ScheduleBlockEntity
import com.nullclass.core.model.Course
import com.nullclass.core.model.CourseWithBlocks
import com.nullclass.core.model.ScheduleBlock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** 课程仓库。供 feature 层与 :widget 使用。 */
interface CourseRepository {

    /** 观察某学期的全部课程（含时间安排，已过滤墓碑）。 */
    fun observeSchedule(termId: String): Flow<List<CourseWithBlocks>>

    /** 取一门课（编辑页回填用）。 */
    suspend fun getCourse(courseId: String): CourseWithBlocks?

    /** 一次性取某学期全量课表（保存前查重用）。 */
    suspend fun getSchedule(termId: String): List<CourseWithBlocks>

    /**
     * 新增或更新一门课及其全部时间安排，返回课程 id。
     * 事务内：保存课程 + 保存块 + 不在 blocks 里的旧块置墓碑。
     */
    suspend fun upsertCourseWithBlocks(course: Course, blocks: List<ScheduleBlock>): String

    /** 软删除一门课（级联墓碑其时间安排）。 */
    suspend fun deleteCourse(courseId: String)

    /** 把 [fromTermId] 的全部课程深拷贝到 [toTermId]（新 UUID），返回复制的课程数。 */
    suspend fun copyCoursesFromTerm(fromTermId: String, toTermId: String): Int
}

@Singleton
class CourseRepositoryImpl @Inject constructor(
    private val db: NullClassDatabase,
    private val courseDao: CourseDao,
) : CourseRepository {

    override fun observeSchedule(termId: String): Flow<List<CourseWithBlocks>> =
        courseDao.observeSchedule(termId).map { list -> list.map { it.toModel() } }

    override suspend fun getCourse(courseId: String): CourseWithBlocks? =
        courseDao.getCourseWithBlocks(courseId)?.toModel()

    override suspend fun getSchedule(termId: String): List<CourseWithBlocks> =
        courseDao.getSchedule(termId).map { it.toModel() }

    override suspend fun upsertCourseWithBlocks(course: Course, blocks: List<ScheduleBlock>): String {
        require(blocks.all { it.courseId == course.id }) { "block.courseId must match course.id" }
        val now = System.currentTimeMillis()
        val courseId = course.id.ifEmpty { UUID.randomUUID().toString() }
        val withId = course.copy(id = courseId)
        // 新块同样要生成 UUID：空主键会让多条安排互相覆盖（B1）
        val blocksWithId = blocks.map { block ->
            block.copy(
                id = block.id.ifEmpty { UUID.randomUUID().toString() },
                courseId = courseId,
            )
        }

        db.withTransaction {
            // 已有记录保留原 createdAt；新记录用 now
            val existing = courseDao.getById(courseId)
            courseDao.upsertCourse(withId.toEntity(createdAt = existing?.createdAt ?: now, updatedAt = now))
            courseDao.upsertBlocks(
                blocksWithId.map { block ->
                    val existingBlock = courseDao.getBlockById(block.id)
                    block.toEntity(
                        termId = withId.termId,
                        createdAt = existingBlock?.createdAt ?: now,
                        updatedAt = now,
                    )
                },
            )
            if (blocksWithId.isEmpty()) {
                courseDao.tombstoneBlocksOfCourse(courseId, now)
            } else {
                courseDao.tombstoneStaleBlocks(courseId, blocksWithId.map { it.id }, now)
            }
        }
        return courseId
    }

    override suspend fun deleteCourse(courseId: String) {
        val now = System.currentTimeMillis()
        db.withTransaction {
            courseDao.tombstoneCourse(courseId, now)
            courseDao.tombstoneBlocksOfCourse(courseId, now)
        }
    }

    override suspend fun copyCoursesFromTerm(fromTermId: String, toTermId: String): Int {
        val now = System.currentTimeMillis()
        val courses = courseDao.getCoursesOfTerm(fromTermId)
        if (courses.isEmpty()) return 0
        val blocksByCourse = courseDao.getBlocksOfTerm(fromTermId).groupBy { it.courseId }

        db.withTransaction {
            courses.forEach { source ->
                val newCourseId = UUID.randomUUID().toString()
                courseDao.upsertCourse(
                    source.copy(id = newCourseId, termId = toTermId, createdAt = now, updatedAt = now),
                )
                blocksByCourse[source.id]?.forEach { block ->
                    courseDao.upsertBlocks(
                        listOf(
                            block.copy(
                                id = UUID.randomUUID().toString(),
                                courseId = newCourseId,
                                termId = toTermId,
                                createdAt = now,
                                updatedAt = now,
                                deletedAt = null,
                            ),
                        ),
                    )
                }
            }
        }
        return courses.size
    }
}
