package com.nullclass.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nullclass.core.data.repository.CourseRepository
import com.nullclass.core.data.repository.DayOverrideRepository
import com.nullclass.core.data.repository.TermRepository
import com.nullclass.core.model.Course
import com.nullclass.core.model.CourseWithBlocks
import com.nullclass.core.model.DayOverrides
import com.nullclass.core.model.ScheduleBlock
import com.nullclass.core.model.Term
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/** 快速删课的筛选方式：看一整周，还是看某一天。 */
enum class CleanupFilter { WEEK, DATE }

/**
 * 筛选命中的一门课。
 *
 * 以**课**为单位而不是以时间安排为单位：一门课在同一周里常有两三条安排
 * （周二 1-2 节、周四 3-4 节），拆成两行会让人以为能只删掉其中一行，
 * 而删除的粒度本来就是整门课。
 */
data class CleanupRow(
    val course: Course,
    /** 命中筛选的时间安排，按周内次序 + 节次排好。 */
    val matched: List<ScheduleBlock>,
    /** 这门课一共有几条时间安排；> matched.size 时说明删掉会连带别的日子。 */
    val totalBlocks: Int,
)

data class CourseCleanupUiState(
    val loading: Boolean = true,
    val term: Term? = null,
    val filter: CleanupFilter = CleanupFilter.WEEK,
    /** 当前看的周次（1..totalWeeks）；没有学期时为 1。 */
    val week: Int = 1,
    /** 日期模式下当前看的那天。 */
    val date: Long = LocalDate.now().toEpochDay(),
    /** 今天所在周次；今天不在学期内为 null（用于给周次芯片标「本周」）。 */
    val todayWeek: Int? = null,
    /** 日期模式下这天被调课时，课取自哪一天；没调课为 null。 */
    val swappedFrom: Long? = null,
    /** 日期模式下这天落在学期外 —— 那天本来就没课，列表空得有理由。 */
    val dateOutOfTerm: Boolean = false,
    val rows: List<CleanupRow> = emptyList(),
)

/**
 * 快速删课：按周次或某一天筛出课，勾选后整门删除。
 *
 * 删除口径沿用课表页的长按删除（[CourseRepository.deleteCourse]，软删除 + 级联
 * 墓碑其时间安排与关联考试），这页只是换了个「先筛后批量」的找课方式。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CourseCleanupViewModel @Inject constructor(
    private val courseRepository: CourseRepository,
    termRepository: TermRepository,
    dayOverrideRepository: DayOverrideRepository,
) : ViewModel() {

    private val today = LocalDate.now().toEpochDay()

    private val filter = MutableStateFlow(CleanupFilter.WEEK)

    /** null = 还没选过，跟随今天所在周（今天不在学期内则第 1 周）。 */
    private val pickedWeek = MutableStateFlow<Int?>(null)

    /** null = 还没选过，跟随今天（今天不在学期内则开学那天）。 */
    private val pickedDate = MutableStateFlow<Long?>(null)

    val uiState: StateFlow<CourseCleanupUiState> = termRepository.observeCurrent()
        .flatMapLatest { term ->
            if (term == null) {
                flowOf(CourseCleanupUiState(loading = false))
            } else {
                combine(
                    courseRepository.observeSchedule(term.id),
                    dayOverrideRepository.index,
                    filter,
                    pickedWeek,
                    pickedDate,
                ) { schedule, overrides, mode, week, date ->
                    val todayWeek = term.weekOf(today)
                    // 学期换短了之后，上一个学期翻到的周次可能越界，夹回来而不是让它空着
                    val visibleWeek = (week ?: todayWeek ?: 1).coerceIn(1, term.totalWeeks)
                    val visibleDate = date ?: if (todayWeek != null) today else term.firstDayEpochDay
                    when (mode) {
                        CleanupFilter.WEEK -> CourseCleanupUiState(
                            loading = false,
                            term = term,
                            filter = mode,
                            week = visibleWeek,
                            date = visibleDate,
                            todayWeek = todayWeek,
                            rows = rowsOfWeek(term, schedule, visibleWeek),
                        )

                        CleanupFilter.DATE -> {
                            // 调课后这天上的是别天的课，按今日页同一套解析取（只跟一跳）
                            val source = DayOverrides.sourceOf(overrides, visibleDate)
                            val origin = DayOverrides.originOf(term, overrides, visibleDate)
                            CourseCleanupUiState(
                                loading = false,
                                term = term,
                                filter = mode,
                                week = visibleWeek,
                                date = visibleDate,
                                todayWeek = todayWeek,
                                swappedFrom = source.takeIf { it != visibleDate },
                                dateOutOfTerm = term.weekOf(visibleDate) == null,
                                rows = if (origin == null) {
                                    emptyList()
                                } else {
                                    rowsOfDay(term, schedule, origin.week, origin.dayOfWeek)
                                },
                            )
                        }
                    }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CourseCleanupUiState())

    fun setFilter(value: CleanupFilter) {
        filter.value = value
    }

    fun setWeek(value: Int) {
        pickedWeek.value = value
    }

    fun setDate(epochDay: Long) {
        pickedDate.value = epochDay
    }

    /** 整门删除选中的课（连同全部时间安排与关联考试）。 */
    fun deleteCourses(courseIds: Collection<String>) {
        if (courseIds.isEmpty()) return
        val ids = courseIds.toList()
        viewModelScope.launch {
            ids.forEach { courseRepository.deleteCourse(it) }
        }
    }

    private fun rowsOfWeek(
        term: Term,
        schedule: List<CourseWithBlocks>,
        week: Int,
    ): List<CleanupRow> = schedule.mapNotNull { item ->
        val matched = item.blocks.filter { it.occursInWeek(week) }
        if (matched.isEmpty()) {
            null
        } else {
            CleanupRow(
                course = item.course,
                matched = matched.sortedWith(
                    compareBy({ term.dayIndexInWeek(it.dayOfWeek) }, { it.startPeriod }),
                ),
                totalBlocks = item.blocks.size,
            )
        }
    }.sortedWith(
        compareBy(
            { term.dayIndexInWeek(it.matched.first().dayOfWeek) },
            { it.matched.first().startPeriod },
        ),
    )

    private fun rowsOfDay(
        term: Term,
        schedule: List<CourseWithBlocks>,
        week: Int,
        dayOfWeek: Int,
    ): List<CleanupRow> = schedule.mapNotNull { item ->
        val matched = item.blocks.filter { it.occursInWeek(week) && it.dayOfWeek == dayOfWeek }
        if (matched.isEmpty()) {
            null
        } else {
            CleanupRow(
                course = item.course,
                matched = matched.sortedBy { it.startPeriod },
                totalBlocks = item.blocks.size,
            )
        }
    }.sortedBy { it.matched.first().startPeriod }

    /** 星期 [dayOfWeek] 排在这个学期的一周里第几格（学期可能从周日起算）。 */
    private fun Term.dayIndexInWeek(dayOfWeek: Int): Int = (dayOfWeek - weekStartDay + 7) % 7
}
