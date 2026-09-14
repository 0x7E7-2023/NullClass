package com.nullclass.feature.exam

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nullclass.core.data.repository.ExamRepository
import com.nullclass.core.data.repository.TermRepository
import com.nullclass.core.model.ExamWithCourse
import com.nullclass.core.model.Term
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

sealed interface ExamUiState {
    data object Loading : ExamUiState

    data object NoTerm : ExamUiState

    data class Ready(
        val term: Term,
        val exams: List<ExamWithCourse>,
        val todayEpochDay: Long,
    ) : ExamUiState
}

/** 考试 Tab：当前学期的考试汇总与删除操作。 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ExamViewModel @Inject constructor(
    private val termRepository: TermRepository,
    private val examRepository: ExamRepository,
) : ViewModel() {

    /** 进程跨夜存活时更新倒计时和「已结束」分组。 */
    private val dayTicker = flow {
        while (true) {
            emit(LocalDate.now().toEpochDay())
            delay(60_000)
        }
    }

    val uiState: StateFlow<ExamUiState> = termRepository.observeCurrent()
        .flatMapLatest { term ->
            if (term == null) {
                flowOf(ExamUiState.NoTerm)
            } else {
                combine(examRepository.observeForTerm(term.id), dayTicker) { exams, today ->
                    ExamUiState.Ready(term = term, exams = exams, todayEpochDay = today)
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ExamUiState.Loading)

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun dismissMessage() = _message.update { null }

    fun deleteExam(examId: String) {
        viewModelScope.launch {
            try {
                examRepository.delete(examId)
            } catch (e: Exception) {
                _message.value = "删除考试失败：${e.message ?: "请重试"}"
            }
        }
    }
}
