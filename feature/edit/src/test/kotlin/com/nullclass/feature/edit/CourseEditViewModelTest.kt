package com.nullclass.feature.edit

import androidx.lifecycle.SavedStateHandle
import com.nullclass.core.data.repository.CourseRepository
import com.nullclass.core.data.repository.TermRepository
import com.nullclass.core.model.Course
import com.nullclass.core.model.CourseWithBlocks
import com.nullclass.core.model.PeriodTime
import com.nullclass.core.model.ScheduleBlock
import com.nullclass.core.model.Term
import com.nullclass.core.model.WeekType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 添加课程时的查重：与同学期其他课程时段重叠必须拦下，不许落库。 */
@OptIn(ExperimentalCoroutinesApi::class)
class CourseEditViewModelTest {

    private val term = Term(id = "t1", name = "2026-2027-1", firstDayEpochDay = 0, totalWeeks = 20)

    private lateinit var termRepository: FakeTermRepository
    private lateinit var courseRepository: FakeCourseRepository

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        termRepository = FakeTermRepository(term)
        courseRepository = FakeCourseRepository()
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(courseId: String? = null): CourseEditViewModel = CourseEditViewModel(
        savedStateHandle = SavedStateHandle(courseId?.let { mapOf("courseId" to it) } ?: emptyMap()),
        termRepository = termRepository,
        courseRepository = courseRepository,
    )

    private fun block(
        courseId: String = "",
        dayOfWeek: Int = 1,
        startWeek: Int = 1,
        endWeek: Int = 16,
        weekType: WeekType = WeekType.ALL,
        startPeriod: Int = 3,
        endPeriod: Int = 4,
    ) = ScheduleBlock(
        id = "",
        courseId = courseId,
        startWeek = startWeek,
        endWeek = endWeek,
        weekType = weekType,
        dayOfWeek = dayOfWeek,
        startPeriod = startPeriod,
        endPeriod = endPeriod,
    )

    /** 已有课表：高数 周一 3-4 节，第 1-16 周。 */
    private fun existingMath() = CourseWithBlocks(
        course = Course(id = "math", termId = term.id, name = "高等数学"),
        blocks = listOf(block(courseId = "math")),
    )

    @Test
    fun `与已有课程时段冲突时拒绝保存`() {
        courseRepository.schedule = listOf(existingMath())
        val vm = viewModel()
        vm.setName("线性代数")
        vm.updateBlock(0, EditableBlock(dayOfWeek = 1, startPeriod = 3, endPeriod = 4))

        var saved = false
        vm.save { saved = true }

        assertFalse(saved, "冲突时不应保存成功并离开页面")
        assertTrue(courseRepository.upserts.isEmpty(), "冲突时不应写入数据库")
        assertFalse(vm.state.value.saving, "冲突后应允许用户修改并重试")
        val error = assertNotNull(vm.state.value.error)
        assertTrue(error.contains("高等数学"), "提示应说明和哪门课冲突，实际：$error")
    }

    @Test
    fun `时段不冲突时正常保存`() {
        courseRepository.schedule = listOf(existingMath())
        val vm = viewModel()
        vm.setName("线性代数")
        vm.updateBlock(0, EditableBlock(dayOfWeek = 2, startPeriod = 1, endPeriod = 2))

        var saved = false
        vm.save { saved = true }

        assertTrue(saved)
        assertEquals(1, courseRepository.upserts.size)
        assertNull(vm.state.value.error)
    }

    @Test
    fun `单双周错开不算冲突`() {
        val vm = viewModel()
        vm.setName("线性代数")
        vm.updateBlock(
            0,
            EditableBlock(weekType = WeekType.ODD, dayOfWeek = 1, startPeriod = 3, endPeriod = 4),
        )
        courseRepository.schedule = listOf(
            CourseWithBlocks(
                course = Course(id = "math", termId = term.id, name = "高等数学"),
                blocks = listOf(block(courseId = "math", weekType = WeekType.EVEN)),
            ),
        )

        var saved = false
        vm.save { saved = true }

        assertTrue(saved, "单周与双周不撞车，应允许保存")
    }

    @Test
    fun `编辑已有课程时不与自身冲突`() {
        courseRepository.schedule = listOf(existingMath())
        courseRepository.courses["math"] = existingMath()
        val vm = viewModel(courseId = "math")
        vm.updateBlock(0, EditableBlock(dayOfWeek = 1, startPeriod = 3, endPeriod = 4))

        var saved = false
        vm.save { saved = true }

        assertTrue(saved, "改自己不该被判成和自己的旧安排冲突")
        assertEquals(1, courseRepository.upserts.size)
    }

    @Test
    fun `冲突提示后调整时间可以再次保存`() {
        courseRepository.schedule = listOf(existingMath())
        val vm = viewModel()
        vm.setName("线性代数")
        vm.updateBlock(0, EditableBlock(dayOfWeek = 1, startPeriod = 3, endPeriod = 4))

        vm.save { }
        assertNotNull(vm.state.value.error)

        vm.updateBlock(0, EditableBlock(dayOfWeek = 3, startPeriod = 3, endPeriod = 4))
        var saved = false
        vm.save { saved = true }

        assertTrue(saved)
        assertEquals(1, courseRepository.upserts.size)
    }
}

private class FakeTermRepository(private val term: Term) : TermRepository {
    override fun observeAll(): Flow<List<Term>> = flowOf(listOf(term))

    override fun observeCurrent(): Flow<Term?> = flowOf(term)

    override suspend fun getCurrent(): Term? = term

    override suspend fun getById(termId: String): Term? = term.takeIf { it.id == termId }

    override suspend fun getPreviousTerm(): Term? = null

    override suspend fun upsert(term: Term, periodTimes: List<PeriodTime>): String = term.id

    override suspend fun setCurrent(termId: String) = Unit

    override fun observePeriodTimes(termId: String): Flow<List<PeriodTime>> = flowOf(emptyList())

    override suspend fun getPeriodTimes(termId: String): List<PeriodTime> = emptyList()

    override suspend fun deleteTerm(termId: String) = Unit
}

private class FakeCourseRepository : CourseRepository {
    var schedule: List<CourseWithBlocks> = emptyList()
    val courses = mutableMapOf<String, CourseWithBlocks>()
    val upserts = mutableListOf<CourseWithBlocks>()

    override fun observeSchedule(termId: String): Flow<List<CourseWithBlocks>> = flowOf(schedule)

    override suspend fun getCourse(courseId: String): CourseWithBlocks? = courses[courseId]

    override suspend fun getSchedule(termId: String): List<CourseWithBlocks> = schedule

    override suspend fun upsertCourseWithBlocks(course: Course, blocks: List<ScheduleBlock>): String {
        upserts += CourseWithBlocks(course, blocks)
        return course.id
    }

    override suspend fun deleteCourse(courseId: String) = Unit

    override suspend fun copyCoursesFromTerm(fromTermId: String, toTermId: String): Int = 0
}
