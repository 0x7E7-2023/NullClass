package com.nullclass.feature.edit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nullclass.core.data.repository.TermRepository
import com.nullclass.core.model.Term
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

data class TermListItem(
    val term: Term,
    val isCurrent: Boolean,
    /** 今天所在周次；不在该学期内为 null。 */
    val currentWeek: Int?,
)

data class TermListUiState(
    val items: List<TermListItem> = emptyList(),
)

@HiltViewModel
class TermListViewModel @Inject constructor(
    private val termRepository: TermRepository,
) : ViewModel() {

    val uiState: StateFlow<TermListUiState> =
        combine(termRepository.observeAll(), termRepository.observeCurrent()) { terms, current ->
            val today = LocalDate.now().toEpochDay()
            TermListUiState(
                items = terms.map { term ->
                    TermListItem(
                        term = term,
                        isCurrent = term.id == current?.id,
                        currentWeek = term.weekOf(today),
                    )
                },
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TermListUiState())

    fun setCurrent(termId: String) {
        if (uiState.value.items.any { it.isCurrent && it.term.id == termId }) return
        viewModelScope.launch { termRepository.setCurrent(termId) }
    }

    fun deleteTerm(termId: String) {
        viewModelScope.launch { termRepository.deleteTerm(termId) }
    }
}
