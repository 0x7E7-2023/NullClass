package com.nullclass.feature.schedule

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nullclass.core.data.repository.CourseRepository
import com.nullclass.core.data.repository.TermRepository
import com.nullclass.core.model.CourseWithBlocks
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
                    dayTicker,
                ) { schedule, periodTimes, _ ->
                    TodayUiState.Ready(
                        term = term,
                        snapshot = assembleTodaySnapshot(term, schedule, periodTimes, LocalDate.now()),
                        schedule = schedule,
                    )
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayUiState.Loading)

    fun deleteCourse(courseId: String) {
        viewModelScope.launch { courseRepository.deleteCourse(courseId) }
    }
}
