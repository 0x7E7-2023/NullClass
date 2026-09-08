package com.nullclass.feature.edit

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nullclass.core.data.repository.CourseRepository
import com.nullclass.core.data.repository.TermRepository
import com.nullclass.core.model.Course
import com.nullclass.core.model.CourseConflict
import com.nullclass.core.model.ScheduleBlock
import com.nullclass.core.model.ScheduleConflicts
import com.nullclass.core.model.ScheduleFormat
import com.nullclass.core.model.WeekType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 编辑器内的一条时间安排（新建时 id 为空，保存时生成）。 */
data class EditableBlock(
    val id: String = "",
    val startWeek: Int = 1,
    val endWeek: Int = 20,
    val weekType: WeekType = WeekType.ALL,
    val dayOfWeek: Int = 1,
    val startPeriod: Int = 1,
    val endPeriod: Int = 2,
    val location: String = "",
)

data class CourseEditUiState(
    val loading: Boolean = true,
    /** 无学期，无法编辑（界面上给引导）。 */
    val termMissing: Boolean = false,
    val isNew: Boolean = true,
    val termId: String = "",
    val totalWeeks: Int = 20,
    val totalPeriods: Int = 12,
    val name: String = "",
    val teacher: String = "",
    val note: String = "",
    val colorIndex: Int = 0,
    val blocks: List<EditableBlock> = emptyList(),
    /** 保存失败的就地错误提示。 */
    val error: String? = null,
    /** 保存进行中（或已保存成功等待离开）：期间按钮禁用，防连点多次 popBackStack。 */
    val saving: Boolean = false,
)

@HiltViewModel
class CourseEditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val termRepository: TermRepository,
    private val courseRepository: CourseRepository,
) : ViewModel() {

    private val courseId: String? = savedStateHandle.get<String>("courseId")?.takeIf { it.isNotBlank() }

    private val _state = MutableStateFlow(CourseEditUiState())
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val term = termRepository.getCurrent()
            if (term == null) {
                _state.update { it.copy(loading = false, termMissing = true) }
                return@launch
            }
            val totalPeriods = termRepository.getPeriodTimes(term.id).size.coerceAtLeast(1)

            if (courseId != null) {
                val existing = courseRepository.getCourse(courseId)
                if (existing != null) {
                    _state.update {
                        it.copy(
                            loading = false,
                            isNew = false,
                            termId = term.id,
                            totalWeeks = term.totalWeeks,
                            totalPeriods = totalPeriods,
                            name = existing.course.name,
                            teacher = existing.course.teacher.orEmpty(),
                            note = existing.course.note.orEmpty(),
                            colorIndex = existing.course.colorIndex,
                            blocks = existing.blocks.map { block ->
                                EditableBlock(
                                    id = block.id,
                                    startWeek = block.startWeek,
                                    endWeek = block.endWeek,
                                    weekType = block.weekType,
                                    dayOfWeek = block.dayOfWeek,
                                    startPeriod = block.startPeriod,
                                    endPeriod = block.endPeriod,
                                    location = block.location.orEmpty(),
                                )
                            },
                        )
                    }
                    return@launch
                }
            }

            // 新建：默认周一 1-2 节、整学期每周
            val start = 1
            val end = 2.coerceAtMost(totalPeriods)
            _state.update {
                it.copy(
                    loading = false,
                    isNew = true,
                    termId = term.id,
                    totalWeeks = term.totalWeeks,
                    totalPeriods = totalPeriods,
                    blocks = listOf(
                        EditableBlock(
                            startWeek = 1,
                            endWeek = term.totalWeeks,
                            dayOfWeek = 1,
                            startPeriod = start,
                            endPeriod = end,
                        ),
                    ),
                )
            }
        }
    }

    fun setName(value: String) = _state.update { it.copy(name = value, error = null) }

    fun setTeacher(value: String) = _state.update { it.copy(teacher = value) }

    fun setNote(value: String) = _state.update { it.copy(note = value) }

    fun setColor(index: Int) = _state.update { it.copy(colorIndex = index) }

    fun updateBlock(index: Int, block: EditableBlock) = _state.update {
        it.copy(blocks = it.blocks.toMutableList().also { list -> list[index] = block })
    }

    fun removeBlock(index: Int) = _state.update {
        it.copy(blocks = it.blocks.filterIndexed { i, _ -> i != index })
    }

    fun addBlock() = _state.update {
        it.copy(
            blocks = it.blocks + EditableBlock(
                startWeek = 1,
                endWeek = it.totalWeeks,
                dayOfWeek = it.blocks.lastOrNull()?.dayOfWeek ?: 1,
                startPeriod = it.blocks.lastOrNull()?.startPeriod ?: 1,
                endPeriod = it.blocks.lastOrNull()?.endPeriod ?: 2,
            ),
        )
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    fun save(onSaved: () -> Unit) {
        val s = _state.value
        // 防连点：保存中/已保存成功（等待离开页面）时忽略再次点击，
        // 避免退出转场期间连点触发多次 popBackStack 把返回栈弹空。
        if (s.saving) return
        when {
            s.name.isBlank() -> {
                _state.update { it.copy(error = "课程名不能为空") }
                return
            }
            s.blocks.isEmpty() -> {
                _state.update { it.copy(error = "至少添加一条时间安排") }
                return
            }
            s.blocks.firstOverlappingPair() != null -> {
                _state.update { it.copy(error = "有两条时间安排在同一时段重叠，请调整或删除多余的一条") }
                return
            }
        }
        viewModelScope.launch {
            _state.update { it.copy(saving = true) }
            try {
                val course = Course(
                    id = courseId.orEmpty(),
                    termId = s.termId,
                    name = s.name.trim(),
                    teacher = s.teacher.trim().takeIf { it.isNotBlank() },
                    note = s.note.trim().takeIf { it.isNotBlank() },
                    colorIndex = s.colorIndex,
                )
                val blocks = s.blocks.map { b ->
                    ScheduleBlock(
                        id = b.id,
                        courseId = course.id,
                        startWeek = b.startWeek,
                        endWeek = b.endWeek,
                        weekType = b.weekType,
                        dayOfWeek = b.dayOfWeek,
                        startPeriod = b.startPeriod,
                        endPeriod = b.endPeriod,
                        location = b.location.trim().takeIf { it.isNotBlank() },
                    )
                }
                // 查重：与同学期其他课程的时段冲突必须拦下（编辑时排除自身）
                val conflicts = ScheduleConflicts.find(
                    newBlocks = blocks,
                    existing = courseRepository.getSchedule(s.termId),
                    ignoreCourseId = courseId,
                )
                if (conflicts.isNotEmpty()) {
                    _state.update { it.copy(saving = false, error = conflicts.toConflictMessage()) }
                    return@launch
                }
                courseRepository.upsertCourseWithBlocks(course, blocks)
                onSaved()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 保存失败要允许重试（含查重读库失败）
                _state.update { it.copy(saving = false, error = e.message ?: "保存失败，请重试") }
            }
        }
    }
}

/** 冲突提示文案：一行一处，最多列 [MAX_CONFLICT_LINES] 处，避免弹窗过长。 */
private fun List<CourseConflict>.toConflictMessage(): String {
    val lines = map { "与《${it.course.name}》冲突：${ScheduleFormat.blockSummary(it.existingBlock)}" }
        .distinct()
    val shown = lines.take(MAX_CONFLICT_LINES)
    return buildString {
        append("时间冲突，请调整后再保存：\n")
        append(shown.joinToString("\n"))
        if (lines.size > shown.size) append("\n…另有 ${lines.size - shown.size} 处冲突")
    }
}

private const val MAX_CONFLICT_LINES = 3

/** 同课程两条安排的时段重叠：同一天、节次区间相交，且在各自周型下存在共同出现的周。 */
private fun List<EditableBlock>.firstOverlappingPair(): Pair<EditableBlock, EditableBlock>? {
    for (i in indices) for (j in i + 1 until size) {
        val a = this[i]
        val b = this[j]
        if (a.dayOfWeek != b.dayOfWeek) continue
        if (a.startPeriod > b.endPeriod || b.startPeriod > a.endPeriod) continue
        val sharedWeeks = maxOf(a.startWeek, b.startWeek)..minOf(a.endWeek, b.endWeek)
        if (sharedWeeks.any { w -> a.occursIn(w) && b.occursIn(w) }) return a to b
    }
    return null
}

private fun EditableBlock.occursIn(week: Int): Boolean = when (weekType) {
    WeekType.ALL -> true
    WeekType.ODD -> week % 2 == 1
    WeekType.EVEN -> week % 2 == 0
}
