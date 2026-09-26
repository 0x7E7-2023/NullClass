package com.nullclass.feature.schedule

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nullclass.core.data.prefs.ScheduleWallpaperStore
import com.nullclass.core.data.prefs.UserPreferencesRepository
import com.nullclass.core.data.repository.CourseRepository
import com.nullclass.core.data.repository.DayOverrideRepository
import com.nullclass.core.data.repository.TermRepository
import com.nullclass.core.model.PeriodTime
import com.nullclass.core.model.PlacedBlock
import com.nullclass.core.model.ScheduleAppearance
import com.nullclass.core.model.Term
import com.nullclass.core.model.WeekLayout
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/** 对外观的一次修改：只改自己那个字段，不持有整份快照（见 UserPreferencesRepository.updateScheduleAppearance）。 */
internal typealias AppearanceEdit = (ScheduleAppearance) -> ScheduleAppearance

/**
 * 预览用的一周课表：当前学期本周（今天不在学期内时第 1 周）。
 * 这一周一节课都没有、或者还没有学期时为 null，界面改用示例课程。
 */
internal data class PreviewWeek(
    val term: Term,
    val week: Int,
    val periodTimes: List<PeriodTime>,
    val layout: Map<Int, List<PlacedBlock>>,
    val otherWeekLayout: Map<Int, List<PlacedBlock>>,
    val dayOverrides: Map<Long, Long>,
    /** 今天的星期；预览的这一周不是本周时为 null（表头不高亮）。 */
    val todayDayOfWeek: Int?,
)

/** 几个不在 [ScheduleAppearance] 里、但预览要跟着课表页一起画的显示开关。 */
internal data class DisplaySwitches(
    val showGridLines: Boolean = true,
    val showTimeInCards: Boolean = false,
    val showWeekend: Boolean = true,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PersonalizationViewModel @Inject constructor(
    private val preferences: UserPreferencesRepository,
    private val wallpaperStore: ScheduleWallpaperStore,
    termRepository: TermRepository,
    courseRepository: CourseRepository,
    dayOverrideRepository: DayOverrideRepository,
) : ViewModel() {

    /**
     * 正在拖的滑块：只作用于预览，松手（[commit]）才写盘 —— 拖动一次几十上百个值，
     * 每个都写一遍 DataStore 没有意义。写完且期间没有新的拖动才清掉。
     */
    private val dragging = MutableStateFlow<AppearanceEdit?>(null)

    /** 页面与预览看到的外观 = 已保存的 + 正在拖的。null = 偏好还没读出来。 */
    internal val appearance: StateFlow<ScheduleAppearance?> =
        combine(preferences.scheduleAppearance, dragging) { saved, edit ->
            edit?.invoke(saved)?.sanitized() ?: saved
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    internal val switches: StateFlow<DisplaySwitches> = combine(
        preferences.showGridLines,
        preferences.showTimeInCards,
        preferences.showWeekend,
    ) { grid, timeInCards, weekend -> DisplaySwitches(grid, timeInCards, weekend) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DisplaySwitches())

    internal val wallpaper: StateFlow<Bitmap?> = wallpaperStore.wallpaper

    private val _importing = MutableStateFlow(false)
    internal val importing: StateFlow<Boolean> = _importing.asStateFlow()

    /** 上一次选图失败（读不到 / 不是图片 / 系统解不开的格式）。再选一次或移除时清掉。 */
    private val _importFailed = MutableStateFlow(false)
    internal val importFailed: StateFlow<Boolean> = _importFailed.asStateFlow()

    /**
     * 预览的真实课表。排课只随课表数据重算，和外观草稿彻底分开：拖滑块时只重画，不重新排课。
     */
    internal val previewWeek: StateFlow<PreviewWeek?> = termRepository.observeCurrent()
        .flatMapLatest { term ->
            if (term == null) {
                flowOf(null)
            } else {
                combine(
                    courseRepository.observeSchedule(term.id),
                    termRepository.observePeriodTimes(term.id),
                    dayOverrideRepository.index,
                    preferences.showOtherWeekCourses,
                ) { schedule, periodTimes, overrides, showOtherWeek ->
                    val today = LocalDate.now()
                    val todayWeek = term.weekOf(today.toEpochDay())
                    val week = todayWeek ?: 1
                    val layout = WeekLayout.layoutForWeek(schedule, week, term, overrides)
                    if (layout.values.all { it.isEmpty() }) {
                        null
                    } else {
                        PreviewWeek(
                            term = term,
                            week = week,
                            periodTimes = periodTimes,
                            layout = layout,
                            otherWeekLayout = if (showOtherWeek) {
                                WeekLayout.otherWeekLayout(schedule, week, term, overrides)
                            } else {
                                emptyMap()
                            },
                            dayOverrides = overrides,
                            todayDayOfWeek = if (todayWeek != null) today.dayOfWeek.value else null,
                        )
                    }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 开关、分段选择、预设色这类一下就定的修改：直接写。 */
    internal fun update(edit: AppearanceEdit) {
        viewModelScope.launch { preferences.updateScheduleAppearance(edit) }
    }

    /** 滑块拖动中：只改预览，见 [commitDrag]。 */
    internal fun drag(edit: AppearanceEdit) {
        dragging.value = edit
    }

    /**
     * 滑块松手：把最后一次拖动写盘。写的是草稿本身而不是界面松手时手里的值 ——
     * 松手那一下界面可能还没按最后一次拖动重组，拿界面的值会比手指慢一格。
     * 写完且期间没有新的拖动才撤掉草稿（撤早了预览会闪回旧值）。
     */
    internal fun commitDrag() {
        val edit = dragging.value ?: return
        viewModelScope.launch {
            preferences.updateScheduleAppearance(edit)
            dragging.compareAndSet(edit, null)
        }
    }

    fun setHideGridLines(hide: Boolean) {
        viewModelScope.launch { preferences.setShowGridLines(!hide) }
    }

    fun importWallpaper(uri: Uri) {
        viewModelScope.launch {
            _importing.value = true
            _importFailed.value = false
            _importFailed.value = !wallpaperStore.import(uri)
            _importing.value = false
        }
    }

    fun removeWallpaper() {
        _importFailed.value = false
        viewModelScope.launch { wallpaperStore.clear() }
    }

    /** 恢复默认：外观、网格线、背景图片一起。 */
    fun resetAll() {
        dragging.value = null
        _importFailed.value = false
        viewModelScope.launch {
            preferences.resetScheduleAppearance()
            wallpaperStore.clear()
        }
    }
}
