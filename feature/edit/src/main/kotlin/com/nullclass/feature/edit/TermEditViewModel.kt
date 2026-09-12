package com.nullclass.feature.edit

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nullclass.core.model.DefaultPeriodTimes
import com.nullclass.core.data.repository.CourseRepository
import com.nullclass.core.data.repository.TermRepository
import com.nullclass.core.model.MINUTES_PER_DAY
import com.nullclass.core.model.PeriodTime
import com.nullclass.core.model.RetimeResult
import com.nullclass.core.model.Term
import com.nullclass.core.model.nearestWeekday
import com.nullclass.core.model.retimeSections
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import javax.inject.Inject

/**
 * 「快速设定」的取值范围与默认值。默认值与默认节次模板一致（45 分钟一节、大节内课间 10 分钟），
 * 所以对着新建学期的默认模板点「套用」是恒等变换，不会把好好的模板改坏。
 */
private const val DEFAULT_LESSON_MINUTES = 45
private const val DEFAULT_BREAK_MINUTES = 10

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
    /** 第 1 周的第 1 天（epoch day）。每周从这天的星期几算起，见 [Term.weekStartDay]。 */
    val firstDayEpochDay: Long = 0L,
    val totalWeeks: Int = 20,
    val periods: List<EditablePeriod> = emptyList(),
    /** 「快速设定」的两个输入（分钟，文本便于直接改写）。只用来生成，不跟随手改的行；套用时才解析。 */
    val quickLessonText: String = DEFAULT_LESSON_MINUTES.toString(),
    val quickBreakText: String = DEFAULT_BREAK_MINUTES.toString(),
    /** 快速设定的就地提示（时间改不动时的原因），比弹窗更贴着手上的操作。 */
    val quickNotice: String? = null,
    /** 存在上学期时显示「复制课程」。 */
    val previousTermName: String? = null,
    val copyFromPrevious: Boolean = false,
    /** 已有学期的课程数，清空按钮用。 */
    val courseCount: Int = 0,
    val error: String? = null,
    /** 保存进行中（或已保存成功等待离开）：期间按钮禁用，防连点多次 popBackStack。 */
    val saving: Boolean = false,
    val clearing: Boolean = false,
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
                    val periods = termRepository.getPeriodTimes(term.id).map { p ->
                        EditablePeriod(
                            periodIndex = p.periodIndex,
                            startText = minuteLabel(p.startMinuteOfDay),
                            endText = minuteLabel(p.endMinuteOfDay),
                            session = p.session,
                        )
                    }
                    val (lesson, breakMinutes) = quickDefaults(periods)
                    _state.update {
                        it.copy(
                            loading = false,
                            isNew = false,
                            name = term.name,
                            firstDayEpochDay = term.firstDayEpochDay,
                            totalWeeks = term.totalWeeks,
                            courseCount = courseRepository.getSchedule(term.id).size,
                            periods = periods,
                            quickLessonText = lesson.toString(),
                            quickBreakText = breakMinutes.toString(),
                        )
                    }
                    return@launch
                }
            }
            // 新建默认值：下周一开学、20 周、默认节次模板
            val nextMonday = LocalDate.now().let {
                if (it.dayOfWeek == DayOfWeek.MONDAY) it else it.with(java.time.temporal.TemporalAdjusters.next(DayOfWeek.MONDAY))
            }
            val periods = DefaultPeriodTimes.create("").map { p ->
                EditablePeriod(
                    periodIndex = p.periodIndex,
                    startText = minuteLabel(p.startMinuteOfDay),
                    endText = minuteLabel(p.endMinuteOfDay),
                    session = p.session,
                )
            }
            _state.update {
                it.copy(
                    loading = false,
                    isNew = true,
                    firstDayEpochDay = nextMonday.toEpochDay(),
                    totalWeeks = 20,
                    periods = periods,
                    quickLessonText = DEFAULT_LESSON_MINUTES.toString(),
                    quickBreakText = DEFAULT_BREAK_MINUTES.toString(),
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

    /**
     * 设「每周起始日」：把第 1 周的日期挪到最近的、该星期几的那天。
     *
     * 一周从哪天算起 = 第 1 周从哪天开始，是同一个值的两种说法，所以不另存字段 ——
     * 改起始日就是改日期，改日期（[setFirstDay]）也就顺带改了起始日，两边不会打架。
     */
    fun setWeekStartDay(dayOfWeek: Int) = _state.update {
        it.copy(firstDayEpochDay = nearestWeekday(it.firstDayEpochDay, dayOfWeek))
    }

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

    fun resetDefaultPeriods() {
        val periods = DefaultPeriodTimes.create("").map { p ->
            EditablePeriod(
                periodIndex = p.periodIndex,
                startText = minuteLabel(p.startMinuteOfDay),
                endText = minuteLabel(p.endMinuteOfDay),
                session = p.session,
            )
        }
        _state.update {
            it.copy(
                periods = periods,
                quickLessonText = DEFAULT_LESSON_MINUTES.toString(),
                quickBreakText = DEFAULT_BREAK_MINUTES.toString(),
                quickNotice = null,
            )
        }
    }

    fun setCopyFromPrevious(value: Boolean) = _state.update { it.copy(copyFromPrevious = value) }

    /** 输入框只收数字（最多 3 位）：敲进字母后干瞪眼比直接拦下来更让人摸不着头脑。 */
    fun setQuickLessonText(value: String) = _state.update {
        if (value.length > 3 || !value.all(Char::isDigit)) it
        else it.copy(quickLessonText = value, quickNotice = null)
    }

    fun setQuickBreakText(value: String) = _state.update {
        if (value.length > 3 || !value.all(Char::isDigit)) it
        else it.copy(quickBreakText = value, quickNotice = null)
    }

    /**
     * 快速设定：按「单节时长 + 大节内课间」重排全部节次，**大节的开课时刻不动**
     * （大节与大节之间的休息因此原样保留）。见 [retimeSections]。
     */
    fun applyQuickTimes() {
        val state = _state.value
        val lesson = state.quickLessonText.toIntOrNull()?.takeIf { it in QUICK_LESSON_RANGE }
        val breakMinutes = state.quickBreakText.toIntOrNull()?.takeIf { it in QUICK_BREAK_RANGE }
        if (lesson == null || breakMinutes == null) {
            _state.update {
                it.copy(
                    quickNotice = "单节课请填 ${QUICK_LESSON_RANGE.first}~${QUICK_LESSON_RANGE.last} 分钟，" +
                        "课间休息请填 ${QUICK_BREAK_RANGE.first}~${QUICK_BREAK_RANGE.last} 分钟。",
                )
            }
            return
        }
        // 节次表是按文本编辑的：先用与保存同一套解析校验一遍，免得拿半截输入去重排
        val parsed = state.periods.mapIndexed { index, period ->
            val start = parseMinute(period.startText)
            val end = parseMinute(period.endText)
            if (start == null || end == null || start >= end) {
                _state.update {
                    it.copy(
                        quickNotice = "第 ${index + 1} 节的时间不是有效的 HH:mm（开始要早于结束），" +
                            "先改好再用快速设定。",
                    )
                }
                return
            }
            PeriodTime(
                periodIndex = index + 1,
                startMinuteOfDay = start,
                endMinuteOfDay = end,
                session = period.session,
            )
        }

        when (val result = retimeSections(parsed, lesson, breakMinutes)) {
            is RetimeResult.Ok -> _state.update {
                it.copy(
                    periods = result.periods.map { timed ->
                        EditablePeriod(
                            periodIndex = timed.periodIndex,
                            startText = minuteLabel(timed.startMinuteOfDay),
                            endText = minuteLabel(timed.endMinuteOfDay),
                            session = timed.session,
                        )
                    },
                    quickNotice = null,
                )
            }

            is RetimeResult.Overflow -> _state.update {
                it.copy(
                    quickNotice = "按每节 $lesson 分钟、课间 $breakMinutes 分钟排，第 ${result.section} 大节要到 " +
                        "${minuteLabel(result.endMinuteOfDay)}，而下一个大节 ${minuteLabel(result.nextStartMinuteOfDay)} " +
                        "就开课了 —— 把单节时长或课间休息调小一点。",
                )
            }

            is RetimeResult.OutOfDay -> _state.update {
                it.copy(
                    quickNotice = "按每节 $lesson 分钟、课间 $breakMinutes 分钟排，第 ${result.section} 大节要到 " +
                        "${minuteLabel(result.endMinuteOfDay)}，已经排到第二天了 —— " +
                        "那一大节开得太晚，把单节时长或课间休息调小一点。",
                )
            }
        }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    /** 清空本学期课程（软删除）。学期与节次表不动。 */
    fun clearCourses() {
        val s = _state.value
        val id = termId
        if (s.isNew || s.saving || s.clearing || id == null) return
        _state.update { it.copy(clearing = true) }
        viewModelScope.launch {
            courseRepository.clearTermCourses(id)
            _state.update { it.copy(clearing = false, courseCount = 0) }
        }
    }

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
        /** 快速设定输入框能接受的分钟数范围（套用时校验，越界就地提示）。 */
        internal val QUICK_LESSON_RANGE = 5..180
        internal val QUICK_BREAK_RANGE = 0..180

        internal fun minuteLabel(minuteOfDay: Int): String {
            val hour = minuteOfDay / 60
            val minute = minuteOfDay % 60
            return "${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}"
        }

        /**
         * "8:00"/"08:00" → 分钟数；不合法返回 null。
         *
         * 多认一个 "24:00"（= [MINUTES_PER_DAY]）：模型本来就允许 `endMinuteOfDay = 1440`
         * （当天最后一刻），`minuteLabel(1440)` 写出来正是这个串 —— 不认它，界面上显示得出的值
         * 就再也存不回去（快速设定排到 24:00 的行会卡在保存那一步）。起始时间填 24:00 不要紧，
         * 它必然撞上「开始要早于结束」那一关。
         */
        internal fun parseMinute(text: String): Int? {
            val regex = Regex("""^\s*(\d{1,2}):(\d{2})\s*$""")
            val match = regex.matchEntire(text.trim()) ?: return null
            val hour = match.groupValues[1].toIntOrNull() ?: return null
            val minute = match.groupValues[2].toIntOrNull() ?: return null
            if (minute !in 0..59) return null
            if (hour == 24) return if (minute == 0) MINUTES_PER_DAY else null
            if (hour !in 0..23) return null
            return hour * 60 + minute
        }
    }

    /**
     * 从现有节次推「快速设定」那两格的初值（第 1 节的时长、第 1 节与第 2 节之间的空档），
     * 打开编辑页时它们就跟现状对上；推不出来（时间没填好）就用默认值。
     */
    private fun quickDefaults(periods: List<EditablePeriod>): Pair<Int, Int> {
        val fallback = DEFAULT_LESSON_MINUTES to DEFAULT_BREAK_MINUTES
        val first = periods.getOrNull(0) ?: return fallback
        val start = parseMinute(first.startText) ?: return fallback
        val end = parseMinute(first.endText) ?: return fallback
        if (end <= start) return fallback
        val lesson = (end - start).coerceIn(QUICK_LESSON_RANGE)
        val secondStart = periods.getOrNull(1)?.let { parseMinute(it.startText) }
            ?: return lesson to DEFAULT_BREAK_MINUTES
        return lesson to (secondStart - end).coerceIn(QUICK_BREAK_RANGE)
    }
}
