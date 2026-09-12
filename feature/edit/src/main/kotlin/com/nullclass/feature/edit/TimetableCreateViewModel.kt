package com.nullclass.feature.edit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nullclass.core.data.repository.TimetableRepository
import com.nullclass.core.model.DefaultPeriodTimes
import com.nullclass.core.model.Term
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.DayOfWeek
import java.time.temporal.TemporalAdjusters
import javax.inject.Inject

data class TimetableCreateUiState(
    val timetableName: String = "我的课表",
    val termName: String = "",
    /** 第 1 周第 1 天（默认下周一开学）。 */
    val firstDayEpochDay: Long = 0L,
    val totalWeeks: Int = 20,
    val saving: Boolean = false,
    val error: String? = null,
)

/**
 * 创建课表（首次启动引导与「课表管理 → 新建」共用一屏）。
 * 创建动作 = 建课表 + 切为当前 + 建它的第一个学期（默认节次模板），**同事务落库**——
 * 一张没有学期的课表打开就是「还没有学期」，还得再点一次创建，等于把现在的流程绕了一圈；
 * 而分两步落库的话，第一步一提交闸门就放行，第二步失败就成了库里的半张课表。
 */
@HiltViewModel
class TimetableCreateViewModel @Inject constructor(
    private val timetableRepository: TimetableRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(TimetableCreateUiState())
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            // 默认值在后台算好再上屏，避免把未初始化的 0 当 epoch day 渲染出去
            val defaults = withContext(Dispatchers.Default) { defaultState() }
            _state.update { defaults }
        }
    }

    fun setTimetableName(value: String) = _state.update { it.copy(timetableName = value, error = null) }

    fun setTermName(value: String) = _state.update { it.copy(termName = value, error = null) }

    fun setFirstDay(epochDay: Long) = _state.update { it.copy(firstDayEpochDay = epochDay) }

    fun setTotalWeeks(value: Int) = _state.update { it.copy(totalWeeks = value.coerceIn(1, 25)) }

    fun dismissError() = _state.update { it.copy(error = null) }

    fun save(onSaved: () -> Unit) {
        val s = _state.value
        if (s.saving) return
        if (s.timetableName.isBlank() || s.termName.isBlank()) {
            _state.update { it.copy(error = if (s.timetableName.isBlank()) "课表名不能为空" else "学期名不能为空") }
            return
        }
        if (s.firstDayEpochDay <= 0L) return // 初始默认值还没算好（理论上到不了这）
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            try {
                timetableRepository.createWithFirstTerm(
                    timetableName = s.timetableName,
                    term = Term(
                        id = "",
                        name = s.termName.trim(),
                        firstDayEpochDay = s.firstDayEpochDay,
                        totalWeeks = s.totalWeeks,
                    ),
                    periodTimes = DefaultPeriodTimes.create(""),
                )
                onSaved()
            } catch (e: Exception) {
                _state.update { it.copy(saving = false, error = e.message ?: "创建失败") }
            }
        }
    }

    private fun defaultState(): TimetableCreateUiState {
        val today = LocalDate.now()
        // 学期名默认按开学季节给个可改的：「2026 秋」
        val season = if (today.monthValue in 2..7) "春" else "秋"
        // 默认下周一开学（与学期编辑页的新建默认一致）
        val nextMonday = if (today.dayOfWeek == DayOfWeek.MONDAY) {
            today
        } else {
            today.with(TemporalAdjusters.next(DayOfWeek.MONDAY))
        }
        return TimetableCreateUiState(
            timetableName = "我的课表",
            termName = "${today.year} $season",
            firstDayEpochDay = nextMonday.toEpochDay(),
            totalWeeks = 20,
        )
    }
}
