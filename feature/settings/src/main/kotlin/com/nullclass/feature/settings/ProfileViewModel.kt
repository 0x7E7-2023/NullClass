package com.nullclass.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nullclass.core.data.repository.TermRepository
import com.nullclass.core.data.repository.TimetableRepository
import com.nullclass.core.model.Term
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import javax.inject.Inject

/** 我的页顶部学期卡片需要的信息。 */
data class ProfileUiState(
    /** 当前课表名（卡片小字标注，一眼知道自己在哪张课表里）。 */
    val timetableName: String? = null,
    val term: Term? = null,
    /** 今天所在周次；不在学期内为 null。 */
    val currentWeek: Int? = null,
)

@HiltViewModel
class ProfileViewModel @Inject constructor(
    termRepository: TermRepository,
    timetableRepository: TimetableRepository,
) : ViewModel() {

    /** 每分钟一拍：进程过夜存活时跨天/跨周自动刷新周次，不依赖重建 VM。 */
    private val dayTicker = flow {
        while (true) {
            emit(Unit)
            delay(60_000)
        }
    }

    val uiState: StateFlow<ProfileUiState> =
        combine(
            termRepository.observeCurrent(),
            timetableRepository.observeActive(),
            dayTicker,
        ) { term, activeTimetable, _ ->
            ProfileUiState(
                timetableName = activeTimetable?.name,
                term = term,
                currentWeek = term?.weekOf(LocalDate.now().toEpochDay()),
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProfileUiState())
}
