package com.nullclass.feature.schedule

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nullclass.core.data.prefs.UserPreferencesRepository
import com.nullclass.core.data.repository.CourseRepository
import com.nullclass.core.data.repository.DayOverrideRepository
import com.nullclass.core.data.repository.TermRepository
import com.nullclass.core.data.repository.TimetableRepository
import com.nullclass.core.model.CourseWithBlocks
import com.nullclass.core.model.PeriodTime
import com.nullclass.core.model.PlacedBlock
import com.nullclass.core.model.Term
import com.nullclass.core.model.WeekLayout
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

sealed interface ScheduleUiState {
    data object Loading : ScheduleUiState

    /** 尚未创建学期 → 引导创建。 */
    data object NoTerm : ScheduleUiState

    data class Ready(
        val term: Term,
        /**
         * 今天所在的周；**今天不在学期内**（开学前几天、学期已结束且没有新学期）为 null。
         *
         * 不要兜成 1：那样顶栏会写「第 1 周 · 本周」，还把第 1 周那一列按今天高亮、在上面画
         * 当前时间线 —— 而今日页/提醒/学期列表此时都说「不在学期内」，两处自相矛盾。
         */
        val todayWeek: Int?,
        val selectedWeek: Int,
        val periodTimes: List<PeriodTime>,
        val schedule: List<CourseWithBlocks>,
        /** 该周每天的课块（已排序）。 */
        val layout: Map<Int, List<PlacedBlock>>,
        /** 该周**不上**、但别的周要上的课块（灰块）；关掉开关时为空。 */
        val otherWeekLayout: Map<Int, List<PlacedBlock>>,
        /** 今天的星期（1..7）。使用方结合 todayWeek 判断是否高亮今天列。 */
        val todayDayOfWeek: Int,
        /** 周视图是否显示周末两列。 */
        val showWeekend: Boolean,
        /** 起止时间标在课块角上（节次列随之收窄为仅节次号）。 */
        val showTimeInCards: Boolean,
        /** 周视图是否画当前时间线。 */
        val showNowLine: Boolean,
        /** 周视图是否显示节次与星期列的网格线。 */
        val showGridLines: Boolean,
        /** 是否把非本周的课画成灰块。 */
        val showOtherWeek: Boolean,
        /**
         * 串课表：date → source date（见 [com.nullclass.core.model.DayOverrides]）。
         * 翻页器给别的周现算布局时也要它，所以整张表随状态一起发出去。
         */
        val dayOverrides: Map<Long, Long> = emptyMap(),
        /**
         * 当前课表名。**只有课表多于一张时才非 null**（顶栏第二行前缀「我的课表 · 第 3 周」）：
         * 单课表用户看到的界面一个字都不变。今天在这张课表里第几周，与课表名无关，
         * 所以它不影响周次计算，只影响标题文案。
         */
        val timetableName: String? = null,
    ) : ScheduleUiState
}

/** 周视图的五个显示开关。合成一个流，免得 combine 超过 5 个参数要去走 Array 重载。 */
private data class DisplayPrefs(
    val showWeekend: Boolean,
    val showTimeInCards: Boolean,
    val showNowLine: Boolean,
    val showGridLines: Boolean,
    val showOtherWeek: Boolean,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ScheduleViewModel @Inject constructor(
    private val termRepository: TermRepository,
    private val courseRepository: CourseRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val dayOverrideRepository: DayOverrideRepository,
    timetableRepository: TimetableRepository,
) : ViewModel() {

    private val today: LocalDate = LocalDate.now()

    /**
     * 用户翻到的周次**连同当时的周次编号**。null = 跟随今天自动定位。
     *
     * 存的是哪个学期、哪一周：换学期之后（教务导入新学期）上学期翻到的第几周就没有意义了，
     * 读的时候按编号一比对即作废（见 [weekIn]），不需要额外的协程去"记得清空"。
     * 由界面写回——它本来就拿着当前学期。
     */
    private val selectedWeek = MutableStateFlow<SelectedWeek?>(null)

    init {
        // 切换课表后重置翻到的周次：页码式记忆跨课表没有意义（新课表可能根本没有那一周）
        viewModelScope.launch {
            timetableRepository.observeActive().drop(1).collect { selectedWeek.value = null }
        }
    }

    /** 顶栏课表名：只有课表多于一张时才显示。 */
    private val timetableLabel: Flow<String?> = combine(
        timetableRepository.observeActive(),
        timetableRepository.observeOverviews(),
    ) { active, all ->
        if (all.size > 1) active?.name else null
    }.distinctUntilChanged()

    /** 四个显示开关一起变，任一变化都重算周视图。 */
    private val displayPrefs: Flow<DisplayPrefs> = combine(
        userPreferencesRepository.showWeekend,
        userPreferencesRepository.showTimeInCards,
        userPreferencesRepository.showNowLine,
        userPreferencesRepository.showGridLines,
        userPreferencesRepository.showOtherWeekCourses,
    ) { showWeekend, showTimeInCards, showNowLine, showGridLines, showOtherWeek ->
        DisplayPrefs(showWeekend, showTimeInCards, showNowLine, showGridLines, showOtherWeek)
    }

    val uiState: StateFlow<ScheduleUiState> =
        combine(termRepository.observeCurrent(), selectedWeek, timetableLabel) { term, selected, timetableName ->
            Triple(term, selected, timetableName)
        }
            .flatMapLatest { (term, selected, timetableName) ->
                if (term == null) {
                    flowOf(ScheduleUiState.NoTerm)
                } else {
                    // 今天不在学期内 → todayWeek 为 null：翻页默认落到第 1 周，但不谎称「本周」
                    val todayWeek = term.weekOf(today.toEpochDay())
                    // 翻到的周次是哪个学期的、现在还作不作数，都在这里定（见 [weekIn]）
                    val week = resolveVisibleWeek(
                        selected = selected.weekIn(WeekNumbering.of(term)),
                        todayWeek = todayWeek,
                        totalWeeks = term.totalWeeks,
                    )
                    combine(
                        courseRepository.observeSchedule(term.id),
                        termRepository.observePeriodTimes(term.id),
                        displayPrefs,
                        dayOverrideRepository.index,
                    ) { schedule, periodTimes, prefs, overrides ->
                        ScheduleUiState.Ready(
                            term = term,
                            todayWeek = todayWeek,
                            selectedWeek = week,
                            periodTimes = periodTimes,
                            schedule = schedule,
                            layout = WeekLayout.layoutForWeek(schedule, week, term, overrides),
                            otherWeekLayout = if (prefs.showOtherWeek) {
                                WeekLayout.otherWeekLayout(schedule, week, term, overrides)
                            } else {
                                emptyMap()
                            },
                            todayDayOfWeek = today.dayOfWeek.value,
                            showWeekend = prefs.showWeekend,
                            showTimeInCards = prefs.showTimeInCards,
                            showNowLine = prefs.showNowLine,
                            showGridLines = prefs.showGridLines,
                            showOtherWeek = prefs.showOtherWeek,
                            dayOverrides = overrides,
                            timetableName = timetableName,
                        )
                    }
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ScheduleUiState.Loading)

    /**
     * 翻页或周次选择器触发。[numbering] 是**翻页器所在那一屏**的周次编号（界面手里就有），
     * 记下来才能判断这次选择在换学期之后还算不算数（见 [weekIn]）。
     *
     * 不在这里读一遍当前学期来补全：读到的可能是还没换过去的旧值，那正好把刚作废的选择
     * 又按旧编号收下——这个选择就永远跟着用户走了。
     */
    internal fun selectWeek(week: Int, numbering: WeekNumbering) {
        selectedWeek.value = SelectedWeek(numbering, week)
    }

    /** 回到本周（恢复自动跟随）。 */
    fun backToCurrentWeek() {
        selectedWeek.value = null
    }

    /** 切换周视图周末列显示。 */
    fun setShowWeekend(value: Boolean) {
        viewModelScope.launch { userPreferencesRepository.setShowWeekend(value) }
    }

    /** 切换起止时间显示位置（节次列内 ↔ 课块角上）。 */
    fun setShowTimeInCards(value: Boolean) {
        viewModelScope.launch { userPreferencesRepository.setShowTimeInCards(value) }
    }

    /** 切换当前时间线显示。 */
    fun setShowNowLine(value: Boolean) {
        viewModelScope.launch { userPreferencesRepository.setShowNowLine(value) }
    }

    /** 切换课表网格线显示。 */
    fun setShowGridLines(value: Boolean) {
        viewModelScope.launch { userPreferencesRepository.setShowGridLines(value) }
    }

    /** 切换非本周课程灰块显示。 */
    fun setShowOtherWeek(value: Boolean) {
        viewModelScope.launch { userPreferencesRepository.setShowOtherWeekCourses(value) }
    }

    /**
     * 串课：[epochDay] 这天改上 [sourceEpochDay] 那天的课。
     * 选回它自己即取消（仓库层同一口径，见 [DayOverrideRepository.setOverride]）。
     */
    fun setDayOverride(epochDay: Long, sourceEpochDay: Long) {
        viewModelScope.launch { dayOverrideRepository.setOverride(epochDay, sourceEpochDay) }
    }

    /** 取消某天的串课，恢复原课表。 */
    fun clearDayOverride(epochDay: Long) {
        viewModelScope.launch { dayOverrideRepository.clearOverride(epochDay) }
    }

    fun deleteCourse(courseId: String) {
        viewModelScope.launch { courseRepository.deleteCourse(courseId) }
    }
}
