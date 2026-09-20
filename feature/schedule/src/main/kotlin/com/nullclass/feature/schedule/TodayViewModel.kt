package com.nullclass.feature.schedule

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nullclass.core.data.repository.CourseRepository
import com.nullclass.core.data.repository.DayOverrideRepository
import com.nullclass.core.data.repository.HolidayRepository
import com.nullclass.core.data.repository.TermRepository
import com.nullclass.core.model.CourseWithBlocks
import com.nullclass.core.model.SkipDate
import com.nullclass.core.model.SkipDateType
import com.nullclass.core.model.Term
import com.nullclass.core.model.TodaySnapshot
import com.nullclass.core.model.assembleTodaySnapshot
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

sealed interface TodayUiState {
    data object Loading : TodayUiState

    /** 尚未创建学期 → 引导创建。 */
    data object NoTerm : TodayUiState

    data class Ready(
        val term: Term,
        val snapshot: TodaySnapshot,
        /** 详情弹层按课程 id 查全部安排用。 */
        val schedule: List<CourseWithBlocks>,
    ) : TodayUiState
}

/**
 * 今日 Tab：每天课程的时间轴视图。判定逻辑（本周过滤/节次时间映射）与桌面小组件
 * 共用 core:model 的 [assembleTodaySnapshot]，两边渲染永远一致。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TodayViewModel @Inject constructor(
    private val termRepository: TermRepository,
    private val courseRepository: CourseRepository,
    private val dayOverrideRepository: DayOverrideRepository,
    holidayRepository: HolidayRepository,
) : ViewModel() {

    /** 每分钟一拍：进程过夜存活时驱动按新日期重组快照（跨天不换日期的修复）。 */
    private val dayTicker = flow {
        while (true) {
            emit(Unit)
            delay(60_000)
        }
    }

    val uiState: StateFlow<TodayUiState> = termRepository.observeCurrent()
        .flatMapLatest { term ->
            if (term == null) {
                flowOf(TodayUiState.NoTerm)
            } else {
                combine(
                    courseRepository.observeSchedule(term.id),
                    termRepository.observePeriodTimes(term.id),
                    dayOverrideRepository.index,
                    dayTicker,
                ) { schedule, periodTimes, overrides, _ ->
                    TodayUiState.Ready(
                        term = term,
                        snapshot = assembleTodaySnapshot(
                            term = term,
                            schedule = schedule,
                            periodTimes = periodTimes,
                            today = LocalDate.now(),
                            dayOverrides = overrides,
                        ),
                        schedule = schedule,
                    )
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayUiState.Loading)

    /**
     * 今天的跳过日期（节假日/手动），补班日不算——用于顶部「今日休」提示条。
     * 课表内容照常显示（部分学校调课），只有课前提醒真正被跳过。
     * dayTicker 参与：进程过夜存活时跨午夜也能换到新的一天（与 uiState 同一拍）。
     */
    val todaySkipDate: StateFlow<SkipDate?> = combine(
        holidayRepository.skipDates,
        dayTicker,
    ) { dates, _ ->
        dates.firstOrNull {
            it.epochDay == LocalDate.now().toEpochDay() && it.type != SkipDateType.WORKDAY
        }
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun deleteCourse(courseId: String) {
        viewModelScope.launch { courseRepository.deleteCourse(courseId) }
    }

    /** 顶部横幅的「恢复原课表」：撤掉今天的串课。 */
    fun clearTodayOverride() {
        viewModelScope.launch { dayOverrideRepository.clearOverride(LocalDate.now().toEpochDay()) }
    }
}
