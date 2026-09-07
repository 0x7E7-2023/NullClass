package com.nullclass.feature.edit

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nullclass.core.data.DefaultPeriodTimes
import com.nullclass.core.data.repository.CourseRepository
import com.nullclass.core.data.repository.TermRepository
import com.nullclass.core.model.PeriodTime
import com.nullclass.core.model.Term
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import javax.inject.Inject

/** 编辑器内的节次（时间用文本承载，保存时解析校验）。 */
data class EditablePeriod(
    val periodIndex: Int,
    val startText: String,
    val endText: String,
    val session: Int,
)

data class TermEditUiState(
    val loading: Boolean = true,
    val isNew: Boolean = true,
    val name: String = "",
    /** 开学第一周周一（epoch day）。 */
    val firstDayEpochDay: Long = 0L,
    val totalWeeks: Int = 20,
    val periods: List<EditablePeriod> = emptyList(),
    /** 存在上学期时显示「复制课程」。 */
    val previousTermName: String? = null,
    val copyFromPrevious: Boolean = false,
    val error: String? = null,
    /** 保存进行中（或已保存成功等待离开）：期间按钮禁用，防连点多次 popBackStack。 */
    val saving: Boolean = false,
)

@HiltViewModel
class TermEditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val termRepository: TermRepository,
    private val courseRepository: CourseRepository,
) : ViewModel() {

    private val termId: String? = savedStateHandle.get<String>("termId")?.takeIf { it.isNotBlank() }

    private val _state = MutableStateFlow(TermEditUiState())
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            if (termId != null) {
                val term = termRepository.getById(termId)
                if (term != null) {
                    _state.update {
                        it.copy(
                            loading = false,
                            isNew = false,
                            name = term.name,
                            firstDayEpochDay = term.firstDayEpochDay,
                            totalWeeks = term.totalWeeks,
                            periods = termRepository.getPeriodTimes(term.id).map { p ->
                                EditablePeriod(
                                    periodIndex = p.periodIndex,
                                    startText = minuteLabel(p.startMinuteOfDay),
                                    endText = minuteLabel(p.endMinuteOfDay),
                                    session = p.session,
                                )
                            },
                        )
                    }
                    return@launch
                }
            }
            // 新建默认值：下周一开学、20 周、默认节次模板
            val nextMonday = LocalDate.now().let {
                if (it.dayOfWeek == DayOfWeek.MONDAY) it else it.with(java.time.temporal.TemporalAdjusters.next(DayOfWeek.MONDAY))
            }
            _state.update {
                it.copy(
                    loading = false,
                    isNew = true,
                    firstDayEpochDay = nextMonday.toEpochDay(),
                    totalWeeks = 20,
                    periods = DefaultPeriodTimes.create("").map { p ->
                        EditablePeriod(
                            periodIndex = p.periodIndex,
                            startText = minuteLabel(p.startMinuteOfDay),
                            endText = minuteLabel(p.endMinuteOfDay),
                            session = p.session,
                        )
                    },
                )
            }
            // 是否有上学期可复制
            termRepository.getPreviousTerm()?.let { prev ->
                _state.update { it.copy(previousTermName = prev.name) }
            }
        }
    }

    fun setName(value: String) = _state.update { it.copy(name = value, error = null) }

    fun setFirstDay(epochDay: Long) = _state.update { it.copy(firstDayEpochDay = epochDay) }

    fun setTotalWeeks(value: Int) = _state.update {
        it.copy(totalWeeks = value.coerceIn(1, 25))
    }

    fun updatePeriod(index: Int, period: EditablePeriod) = _state.update {
        it.copy(periods = it.periods.toMutableList().also { list -> list[index] = period })
    }

    fun removePeriod(index: Int) = _state.update {
        if (it.periods.size <= 1) {
            it.copy(error = "至少保留一节课的时间")
        } else {
            val remaining = it.periods.filterIndexed { i, _ -> i != index }
            // 重新连续编号
            it.copy(periods = remaining.mapIndexed { i, p -> p.copy(periodIndex = i + 1) })
        }
    }

    fun addPeriod() = _state.update {
        val last = it.periods.last()
        it.copy(
            periods = it.periods + EditablePeriod(
                periodIndex = it.periods.size + 1,
                startText = last.startText,
                endText = last.endText,
                session = last.session,
            ),
        )
    }

    fun resetDefaultPeriods() = _state.update {
        it.copy(
            periods = DefaultPeriodTimes.create("").map { p ->
                EditablePeriod(
                    periodIndex = p.periodIndex,
                    startText = minuteLabel(p.startMinuteOfDay),
                    endText = minuteLabel(p.endMinuteOfDay),
                    session = p.session,
                )
            },
        )
    }

    fun setCopyFromPrevious(value: Boolean) = _state.update { it.copy(copyFromPrevious = value) }

    fun dismissError() = _state.update { it.copy(error = null) }

    fun save(onSaved: () -> Unit) {
        val s = _state.value
        // 防连点：保存中/已保存成功（等待离开页面）时忽略再次点击。
        // 保存成功后本页即将 popBackStack，但退出转场期间按钮仍可点，
        // 连点会触发多次 popBackStack 把返回栈弹空（白屏卡死根因）。
        if (s.saving) return
        if (s.name.isBlank()) {
            _state.update { it.copy(error = "学期名不能为空") }
            return
        }
        // 解析并校验节次时间
        val parsed = s.periods.mapIndexedNotNull { index, p ->
            val start = parseMinute(p.startText)
            val end = parseMinute(p.endText)
            when {
                start == null || end == null -> {
                    _state.update { it.copy(error = "第${index + 1}节时间格式应为 HH:mm") }
                    null
                }
                start >= end -> {
                    _state.update { it.copy(error = "第${index + 1}节开始时间必须早于结束时间") }
                    null
                }
                else -> PeriodTime(
                    termId = termId.orEmpty(),
                    periodIndex = index + 1,
                    startMinuteOfDay = start,
                    endMinuteOfDay = end,
                    session = p.session,
                )
            }
        }
        if (parsed.size != s.periods.size || parsed.isEmpty()) return
        _state.update { it.copy(saving = true) }

        viewModelScope.launch {
            try {
                val term = Term(
                    id = termId.orEmpty(),
                    name = s.name.trim(),
                    firstDayEpochDay = s.firstDayEpochDay,
                    totalWeeks = s.totalWeeks,
                )
                val newTermId = termRepository.upsert(term, parsed)
                if (s.isNew && s.copyFromPrevious && s.previousTermName != null) {
                    termRepository.getPreviousTerm()?.let { prev ->
                        courseRepository.copyCoursesFromTerm(prev.id, newTermId)
                    }
                }
                onSaved()
            } catch (e: IllegalArgumentException) {
                // 保存失败要允许重试
                _state.update { it.copy(saving = false, error = e.message ?: "输入不合法") }
            }
        }
    }

    companion object {
        internal fun minuteLabel(minuteOfDay: Int): String {
            val hour = minuteOfDay / 60
            val minute = minuteOfDay % 60
            return "${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}"
        }

        /** "8:00"/"08:00" → 分钟数；不合法返回 null。 */
        internal fun parseMinute(text: String): Int? {
            val regex = Regex("""^\s*(\d{1,2}):(\d{2})\s*$""")
            val match = regex.matchEntire(text.trim()) ?: return null
            val hour = match.groupValues[1].toIntOrNull() ?: return null
            val minute = match.groupValues[2].toIntOrNull() ?: return null
            if (hour !in 0..23 || minute !in 0..59) return null
            return hour * 60 + minute
        }
    }
}
