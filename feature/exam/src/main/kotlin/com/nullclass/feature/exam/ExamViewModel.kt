package com.nullclass.feature.exam

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nullclass.core.data.repository.ExamRepository
import com.nullclass.core.data.repository.TermRepository
import com.nullclass.core.model.ExamWithCourse
import com.nullclass.core.model.Term
import com.nullclass.core.ui.i18n.UiText
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

    /** 一次性错误提示。文案在界面层解析，语言切换后不会留下旧语言的残句。 */
    private val _message = MutableStateFlow<UiText?>(null)
    val message: StateFlow<UiText?> = _message.asStateFlow()

    fun dismissMessage() = _message.update { null }

    fun deleteExam(examId: String) {
        viewModelScope.launch {
            try {
                examRepository.delete(examId)
            } catch (e: Exception) {
                // 异常详情原样带出，便于反馈问题；没有详情时只给结论，不拼出半句话
                _message.value = e.message?.takeIf { it.isNotBlank() }
                    ?.let { UiText.Res(R.string.exam_delete_failed_detail, it) }
                    ?: UiText.Res(R.string.exam_delete_failed)
            }
        }
    }
}
