package com.nullclass.feature.schedule

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nullclass.core.data.prefs.UserPreferencesRepository
import com.nullclass.core.data.repository.CourseRepository
import com.nullclass.core.data.repository.TermRepository
import com.nullclass.core.model.CourseWithBlocks
import com.nullclass.core.model.PeriodTime
import com.nullclass.core.model.PlacedBlock
import com.nullclass.core.model.Term
import com.nullclass.core.model.WeekLayout
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
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

sealed interface ScheduleUiState {
    data object Loading : ScheduleUiState

    /** 尚未创建学期 → 引导创建。 */
    data object NoTerm : ScheduleUiState

    data class Ready(
        val term: Term,
        /**
         * 今天所在的周；**今天不在学期内**（开学前几天、学期已结束且没有新学期）为 null。
         *
         * 不要兜成 1：那样顶栏会写「第 1 周 · 本周」，还把第 1 周那一列按今天高亮、在上面画
         * 当前时间线 —— 而今日页/提醒/学期列表此时都说「不在学期内」，两处自相矛盾。
         */
        val todayWeek: Int?,
        val selectedWeek: Int,
        val periodTimes: List<PeriodTime>,
        val schedule: List<CourseWithBlocks>,
        /** 该周每天的课块（已排序）。 */
        val layout: Map<Int, List<PlacedBlock>>,
        /** 该周**不上**、但别的周要上的课块（灰块）；关掉开关时为空。 */
        val otherWeekLayout: Map<Int, List<PlacedBlock>>,
        /** 今天的星期（1..7）。使用方结合 todayWeek 判断是否高亮今天列。 */
        val todayDayOfWeek: Int,
        /** 周视图是否显示周末两列。 */
        val showWeekend: Boolean,
        /** 起止时间标在课块角上（节次列随之收窄为仅节次号）。 */
        val showTimeInCards: Boolean,
        /** 周视图是否画当前时间线。 */
        val showNowLine: Boolean,
        /** 是否把非本周的课画成灰块。 */
        val showOtherWeek: Boolean,
    ) : ScheduleUiState
}

/** 周视图的四个显示开关。合成一个流，免得 combine 超过 5 个参数要去走 Array 重载。 */
private data class DisplayPrefs(
    val showWeekend: Boolean,
    val showTimeInCards: Boolean,
    val showNowLine: Boolean,
    val showOtherWeek: Boolean,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ScheduleViewModel @Inject constructor(
    private val termRepository: TermRepository,
    private val courseRepository: CourseRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
) : ViewModel() {

    private val today: LocalDate = LocalDate.now()

    /** null = 跟随今天自动定位；用户翻页/选周后写入具体值。 */
    private val userSelectedWeek = MutableStateFlow<Int?>(null)

    /** 四个显示开关一起变，任一变化都重算周视图。 */
    private val displayPrefs: Flow<DisplayPrefs> = combine(
        userPreferencesRepository.showWeekend,
        userPreferencesRepository.showTimeInCards,
        userPreferencesRepository.showNowLine,
        userPreferencesRepository.showOtherWeekCourses,
    ) { showWeekend, showTimeInCards, showNowLine, showOtherWeek ->
        DisplayPrefs(showWeekend, showTimeInCards, showNowLine, showOtherWeek)
    }

    val uiState: StateFlow<ScheduleUiState> =
        combine(termRepository.observeCurrent(), userSelectedWeek) { term, selected ->
            term to selected
        }
            .flatMapLatest { (term, selected) ->
                if (term == null) {
                    flowOf(ScheduleUiState.NoTerm)
                } else {
                    // 今天不在学期内 → todayWeek 为 null：翻页默认落到第 1 周，但不谎称「本周」
                    val todayWeek = term.weekOf(today.toEpochDay())
                    val week = selected ?: todayWeek ?: 1
                    combine(
                        courseRepository.observeSchedule(term.id),
                        termRepository.observePeriodTimes(term.id),
                        displayPrefs,
                    ) { schedule, periodTimes, prefs ->
                        ScheduleUiState.Ready(
                            term = term,
                            todayWeek = todayWeek,
                            selectedWeek = week,
                            periodTimes = periodTimes,
                            schedule = schedule,
                            layout = WeekLayout.layoutForWeek(schedule, week),
                            otherWeekLayout = if (prefs.showOtherWeek) {
                                WeekLayout.otherWeekLayout(schedule, week)
                            } else {
                                emptyMap()
                            },
                            todayDayOfWeek = today.dayOfWeek.value,
                            showWeekend = prefs.showWeekend,
                            showTimeInCards = prefs.showTimeInCards,
                            showNowLine = prefs.showNowLine,
                            showOtherWeek = prefs.showOtherWeek,
                        )
                    }
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ScheduleUiState.Loading)

    /** 翻页或周次选择器触发。 */
    fun selectWeek(week: Int) {
        userSelectedWeek.value = week
    }

    /** 回到本周（恢复自动跟随）。 */
    fun backToCurrentWeek() {
        userSelectedWeek.value = null
    }

    /** 切换周视图周末列显示。 */
    fun setShowWeekend(value: Boolean) {
        viewModelScope.launch { userPreferencesRepository.setShowWeekend(value) }
    }

    /** 切换起止时间显示位置（节次列内 ↔ 课块角上）。 */
    fun setShowTimeInCards(value: Boolean) {
        viewModelScope.launch { userPreferencesRepository.setShowTimeInCards(value) }
    }

    /** 切换当前时间线显示。 */
    fun setShowNowLine(value: Boolean) {
        viewModelScope.launch { userPreferencesRepository.setShowNowLine(value) }
    }

    /** 切换非本周课程灰块显示。 */
    fun setShowOtherWeek(value: Boolean) {
        viewModelScope.launch { userPreferencesRepository.setShowOtherWeekCourses(value) }
    }

    fun deleteCourse(courseId: String) {
        viewModelScope.launch { courseRepository.deleteCourse(courseId) }
    }
}
