package com.nullclass.feature.exam

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nullclass.core.data.repository.CourseRepository
import com.nullclass.core.data.repository.ExamRepository
import com.nullclass.core.data.repository.TermRepository
import com.nullclass.core.model.Course
import com.nullclass.core.model.Exam
import com.nullclass.core.ui.i18n.UiText
import com.nullclass.core.ui.i18n.toUiText
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

data class ExamEditUiState(
    val loading: Boolean = true,
    val termMissing: Boolean = false,
    val noCourses: Boolean = false,
    val isNew: Boolean = true,
    val examId: String? = null,
    val termId: String = "",
    val courses: List<Course> = emptyList(),
    val courseId: String = "",
    val title: String = "",
    val dateText: String = LocalDate.now().toString(),
    val startText: String = "",
    val endText: String = "",
    val location: String = "",
    val seat: String = "",
    val note: String = "",
    val error: UiText? = null,
    val saving: Boolean = false,
)

@HiltViewModel
class ExamEditViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    savedStateHandle: SavedStateHandle,
    private val termRepository: TermRepository,
    private val courseRepository: CourseRepository,
    private val examRepository: ExamRepository,
) : ViewModel() {

    private val examId = savedStateHandle.get<String>("examId")?.takeIf { it.isNotBlank() }
    private val requestedCourseId = savedStateHandle.get<String>("courseId")?.takeIf { it.isNotBlank() }

    private val _state = MutableStateFlow(ExamEditUiState(examId = examId))
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val term = termRepository.getCurrent()
            if (term == null) {
                _state.update { it.copy(loading = false, termMissing = true) }
                return@launch
            }

            val existing = examId?.let { examRepository.getExam(it) }
            val preferredCourseId = existing?.exam?.courseId ?: requestedCourseId
            // 同名课程在选择器中只展示一次；编辑已有考试或从课程详情新增时优先保留指定课程。
            val courses = courseRepository.getSchedule(term.id)
                .map { it.course }
                .sortedBy { it.id != preferredCourseId }
                .distinctBy { it.name.trim() }
            val selectedCourseId = existing?.exam?.courseId
                ?: requestedCourseId?.takeIf { id -> courses.any { it.id == id } }
                ?: courses.firstOrNull()?.id.orEmpty()

            _state.update {
                it.copy(
                    loading = false,
                    termId = term.id,
                    courses = courses,
                    courseId = selectedCourseId,
                    noCourses = courses.isEmpty(),
                    isNew = existing == null,
                    // 新建时给一个默认名，按当前界面语言取 —— 它会作为内容存进库里
                    title = existing?.exam?.title ?: context.getString(R.string.exam_edit_default_title),
                    dateText = existing?.exam?.dateEpochDay?.let { day -> LocalDate.ofEpochDay(day).toString() }
                        ?: LocalDate.now().toString(),
                    startText = existing?.exam?.startMinuteOfDay?.let(::minuteText).orEmpty(),
                    endText = existing?.exam?.endMinuteOfDay?.let(::minuteText).orEmpty(),
                    location = existing?.exam?.location.orEmpty(),
                    seat = existing?.exam?.seat.orEmpty(),
                    note = existing?.exam?.note.orEmpty(),
                )
            }
        }
    }

    fun setCourseId(value: String) = _state.update { it.copy(courseId = value, error = null) }

    fun setTitle(value: String) = _state.update { it.copy(title = value, error = null) }

    fun setDateText(value: String) = _state.update { it.copy(dateText = value, error = null) }

    fun setStartText(value: String) = _state.update { it.copy(startText = value, error = null) }

    fun setEndText(value: String) = _state.update { it.copy(endText = value, error = null) }

    fun setLocation(value: String) = _state.update { it.copy(location = value) }

    fun setSeat(value: String) = _state.update { it.copy(seat = value) }

    fun setNote(value: String) = _state.update { it.copy(note = value) }

    fun dismissError() = _state.update { it.copy(error = null) }

    fun save(onSaved: () -> Unit) {
        val s = _state.value
        if (s.saving) return

        val date = runCatching { LocalDate.parse(s.dateText.trim()) }.getOrNull()
        val start = parseMinute(s.startText)
        val end = parseMinute(s.endText)
        val timeError = when {
            s.startText.isBlank() && s.endText.isBlank() -> null
            start == null || end == null -> UiText.Res(R.string.exam_edit_error_time_format)
            end <= start -> UiText.Res(R.string.exam_edit_error_time_order)
            else -> null
        }

        when {
            s.courseId.isBlank() -> showError(UiText.Res(R.string.exam_edit_error_no_course))
            s.title.isBlank() -> showError(UiText.Res(R.string.exam_edit_error_no_name))
            date == null -> showError(UiText.Res(R.string.exam_edit_error_date_format))
            timeError != null -> showError(timeError)
            else -> viewModelScope.launch {
                _state.update { it.copy(saving = true) }
                try {
                    examRepository.upsert(
                        Exam(
                            id = s.examId.orEmpty(),
                            courseId = s.courseId,
                            title = s.title.trim(),
                            dateEpochDay = date.toEpochDay(),
                            startMinuteOfDay = start,
                            endMinuteOfDay = end,
                            location = s.location.trim().takeIf { it.isNotBlank() },
                            seat = s.seat.trim().takeIf { it.isNotBlank() },
                            note = s.note.trim().takeIf { it.isNotBlank() },
                        ),
                    )
                    onSaved()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _state.update {
                        it.copy(saving = false, error = e.toUiText(R.string.exam_edit_error_save_failed))
                    }
                }
            }
        }
    }

    private fun showError(message: UiText) = _state.update { it.copy(error = message) }
}

private fun parseMinute(value: String): Int? {
    if (value.isBlank()) return null
    val parts = value.trim().split(":")
    if (parts.size != 2) return null
    val hour = parts[0].toIntOrNull() ?: return null
    val minute = parts[1].toIntOrNull() ?: return null
    return if (hour in 0..23 && minute in 0..59) hour * 60 + minute else null
}

private fun minuteText(value: Int): String = "%02d:%02d".format(value / 60, value % 60)
