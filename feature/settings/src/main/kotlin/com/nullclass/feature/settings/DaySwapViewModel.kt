package com.nullclass.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nullclass.core.data.repository.CourseRepository
import com.nullclass.core.data.repository.DayOverrideRepository
import com.nullclass.core.data.repository.TermRepository
import com.nullclass.core.model.ClassMove
import com.nullclass.core.model.ClassMoves
import com.nullclass.core.model.CourseWithBlocks
import com.nullclass.core.model.DayOverride
import com.nullclass.core.model.DayOverrides
import com.nullclass.core.model.PlacedBlock
import com.nullclass.core.model.Term
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 单次调课要用的整份数据：选「哪一次课」要现算那天的课，选节次要知道一天几节。 */
data class ClassMoveData(
    val term: Term,
    val schedule: List<CourseWithBlocks>,
    val periodCount: Int,
    val dayOverrides: Map<Long, Long>,
    val moves: List<ClassMove>,
) {
    /** [epochDay] 这天能挪的课：串课解析后的原有课，去掉已挪走的（挪进来的那次要改先删记录）。 */
    fun classesOn(epochDay: Long): List<PlacedBlock> =
        ClassMoves.withoutMovedAway(DayOverrides.blocksOn(term, schedule, dayOverrides, epochDay), moves, epochDay)
            .sortedBy { it.block.startPeriod }

    /** [epochDay] 这天最终上的课（含挪进来的），给目标节次判重叠用。 */
    fun finalClassesOn(epochDay: Long): List<PlacedBlock> =
        ClassMoves.apply(term, schedule, moves, epochDay, DayOverrides.blocksOn(term, schedule, dayOverrides, epochDay))

    /** 按 id 找课块（列表行展示课程名与原节次）。 */
    fun placedOf(blockId: String): PlacedBlock? = schedule.firstNotNullOfOrNull { cwb ->
        cwb.blocks.firstOrNull { it.id == blockId }?.let { PlacedBlock(cwb.course, it) }
    }
}

/** 快捷操作页里的调课：整天串课 + 单次调课，各自增删。 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DaySwapViewModel @Inject constructor(
    private val dayOverrideRepository: DayOverrideRepository,
    termRepository: TermRepository,
    courseRepository: CourseRepository,
) : ViewModel() {

    /** 当前学期：调课只在学期内有意义，日期选择器据此限定可选范围。 */
    val currentTerm: StateFlow<Term?> = termRepository.observeCurrent()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 全部调课（串课）记录，按日期升序。 */
    val dayOverrides: StateFlow<List<DayOverride>> = dayOverrideRepository.overrides
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 单次调课的数据；没有学期时为 null。 */
    val moveData: StateFlow<ClassMoveData?> = termRepository.observeCurrent()
        .flatMapLatest { term ->
            if (term == null) {
                flowOf(null)
            } else {
                combine(
                    courseRepository.observeSchedule(term.id),
                    termRepository.observePeriodTimes(term.id),
                    dayOverrideRepository.adjustments,
                ) { schedule, periodTimes, (overrides, moves) ->
                    ClassMoveData(term, schedule, periodTimes.size, overrides, moves)
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 调课：[epochDay] 这天改上 [sourceEpochDay] 那天的课。 */
    fun setDayOverride(epochDay: Long, sourceEpochDay: Long) {
        viewModelScope.launch { dayOverrideRepository.setOverride(epochDay, sourceEpochDay) }
    }

    /** 取消某天的调课。 */
    fun clearDayOverride(epochDay: Long) {
        viewModelScope.launch { dayOverrideRepository.clearOverride(epochDay) }
    }

    /** 单次调课：课块 [blockId] 在 [fromEpochDay] 那次挪到 [toEpochDay] 的第 [startPeriod]..[endPeriod] 节。 */
    fun addMove(blockId: String, fromEpochDay: Long, toEpochDay: Long, startPeriod: Int, endPeriod: Int) {
        viewModelScope.launch {
            dayOverrideRepository.addMove(
                ClassMove(
                    blockId = blockId,
                    fromEpochDay = fromEpochDay,
                    toEpochDay = toEpochDay,
                    toStartPeriod = startPeriod,
                    toEndPeriod = endPeriod,
                ),
            )
        }
    }

    fun deleteMove(id: Long) {
        viewModelScope.launch { dayOverrideRepository.deleteMove(id) }
    }
}
