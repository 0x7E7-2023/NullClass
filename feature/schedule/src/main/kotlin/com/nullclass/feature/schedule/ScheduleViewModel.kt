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
        /** 今天所在的周（不在学期内时回退 1）。 */
        val currentWeek: Int,
        val selectedWeek: Int,
        val periodTimes: List<PeriodTime>,
        val schedule: List<CourseWithBlocks>,
        /** 该周每天的课块（已排序）。 */
        val layout: Map<Int, List<PlacedBlock>>,
        /** 今天的星期（1..7）。使用方结合 currentWeek 判断是否高亮今天列。 */
        val todayDayOfWeek: Int,
        /** 周视图是否显示周末两列。 */
        val showWeekend: Boolean,
        /** 起止时间标在课块角上（节次列随之收窄为仅节次号）。 */
        val showTimeInCards: Boolean,
        /** 周视图是否画当前时间线。 */
        val showNowLine: Boolean,
    ) : ScheduleUiState
}

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

    val uiState: StateFlow<ScheduleUiState> =
        combine(termRepository.observeCurrent(), userSelectedWeek) { term, selected ->
            term to selected
        }
            .flatMapLatest { (term, selected) ->
                if (term == null) {
                    flowOf(ScheduleUiState.NoTerm)
                } else {
                    val currentWeek = term.weekOf(today.toEpochDay()) ?: 1
                    val week = selected ?: currentWeek
                    combine(
                        courseRepository.observeSchedule(term.id),
                        termRepository.observePeriodTimes(term.id),
                        userPreferencesRepository.showWeekend,
                        userPreferencesRepository.showTimeInCards,
                        userPreferencesRepository.showNowLine,
                    ) { schedule, periodTimes, showWeekend, showTimeInCards, showNowLine ->
                        ScheduleUiState.Ready(
                            term = term,
                            currentWeek = currentWeek,
                            selectedWeek = week,
                            periodTimes = periodTimes,
                            schedule = schedule,
                            layout = WeekLayout.layoutForWeek(schedule, week),
                            todayDayOfWeek = today.dayOfWeek.value,
                            showWeekend = showWeekend,
                            showTimeInCards = showTimeInCards,
                            showNowLine = showNowLine,
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

    fun deleteCourse(courseId: String) {
        viewModelScope.launch { courseRepository.deleteCourse(courseId) }
    }
}
