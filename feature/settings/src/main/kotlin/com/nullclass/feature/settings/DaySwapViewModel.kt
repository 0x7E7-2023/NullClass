package com.nullclass.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nullclass.core.data.repository.DayOverrideRepository
import com.nullclass.core.data.repository.TermRepository
import com.nullclass.core.model.DayOverride
import com.nullclass.core.model.Term
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 快捷操作页里的调课（串课）：当前学期 + 全部调课记录，增删两个动作。 */
@HiltViewModel
class DaySwapViewModel @Inject constructor(
    private val dayOverrideRepository: DayOverrideRepository,
    termRepository: TermRepository,
) : ViewModel() {

    /** 当前学期：调课只在学期内有意义，日期选择器据此限定可选范围。 */
    val currentTerm: StateFlow<Term?> = termRepository.observeCurrent()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 全部调课（串课）记录，按日期升序。 */
    val dayOverrides: StateFlow<List<DayOverride>> = dayOverrideRepository.overrides
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 调课：[epochDay] 这天改上 [sourceEpochDay] 那天的课。 */
    fun setDayOverride(epochDay: Long, sourceEpochDay: Long) {
        viewModelScope.launch { dayOverrideRepository.setOverride(epochDay, sourceEpochDay) }
    }

    /** 取消某天的调课。 */
    fun clearDayOverride(epochDay: Long) {
        viewModelScope.launch { dayOverrideRepository.clearOverride(epochDay) }
    }
}
