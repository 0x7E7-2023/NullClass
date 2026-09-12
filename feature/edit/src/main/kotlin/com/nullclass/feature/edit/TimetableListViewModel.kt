package com.nullclass.feature.edit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nullclass.core.data.repository.TimetableRepository
import com.nullclass.core.model.Timetable
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TimetableListItem(
    val timetable: Timetable,
    val termCount: Int,
    val isActive: Boolean,
)

data class TimetableListUiState(
    val items: List<TimetableListItem> = emptyList(),
)

/** 课表管理：切换当前课表、新建、重命名、删除（最后一张不允许删）。 */
@HiltViewModel
class TimetableListViewModel @Inject constructor(
    private val timetableRepository: TimetableRepository,
) : ViewModel() {

    val uiState: StateFlow<TimetableListUiState> =
        combine(timetableRepository.observeOverviews(), timetableRepository.observeActive()) { overviews, active ->
            TimetableListUiState(
                items = overviews.map { overview ->
                    TimetableListItem(
                        timetable = overview.timetable,
                        termCount = overview.termCount,
                        isActive = overview.timetable.id == active?.id,
                    )
                },
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TimetableListUiState())

    fun setActive(id: String) {
        viewModelScope.launch { timetableRepository.setActive(id) }
    }

    fun rename(id: String, name: String) {
        viewModelScope.launch { timetableRepository.rename(id, name) }
    }

    fun delete(id: String) {
        viewModelScope.launch { timetableRepository.delete(id) }
    }
}
