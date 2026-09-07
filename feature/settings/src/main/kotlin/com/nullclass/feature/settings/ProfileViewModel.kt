package com.nullclass.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nullclass.core.data.repository.TermRepository
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
    val term: Term? = null,
    /** 今天所在周次；不在学期内为 null。 */
    val currentWeek: Int? = null,
)

@HiltViewModel
class ProfileViewModel @Inject constructor(
    termRepository: TermRepository,
) : ViewModel() {

    /** 每分钟一拍：进程过夜存活时跨天/跨周自动刷新周次，不依赖重建 VM。 */
    private val dayTicker = flow {
        while (true) {
            emit(Unit)
            delay(60_000)
        }
    }

    val uiState: StateFlow<ProfileUiState> =
        combine(termRepository.observeCurrent(), dayTicker) { term, _ ->
            ProfileUiState(term = term, currentWeek = term?.weekOf(LocalDate.now().toEpochDay()))
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProfileUiState())
}
