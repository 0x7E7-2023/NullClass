package com.nullclass.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nullclass.core.data.prefs.UserPreferencesRepository
import com.nullclass.core.data.repository.HolidayRepository
import com.nullclass.core.model.SkipDate
import com.nullclass.core.ui.i18n.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 节假日同步的操作反馈（手动刷新 / 自动同步结果）。 */
data class HolidayMessage(
    val text: UiText,
    val isError: Boolean,
)

@HiltViewModel
class NotificationSettingsViewModel @Inject constructor(
    private val userPreferences: UserPreferencesRepository,
    private val holidayRepository: HolidayRepository,
) : ViewModel() {

    /** 课程提醒提前量（0 = 关闭）。 */
    val reminderLeadMinutes: StateFlow<Int> = userPreferences.reminderLeadMinutes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UserPreferencesRepository.DEFAULT_LEAD_MINUTES)

    /** 考试提醒提前量（0 = 关闭）。 */
    val examReminderLeadMinutes: StateFlow<Int> = userPreferences.examReminderLeadMinutes
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            UserPreferencesRepository.DEFAULT_EXAM_REMINDER_LEAD_MINUTES,
        )

    /** 精确提醒开关（重排由 ReminderController 观察同一偏好触发）。 */
    val exactReminder: StateFlow<Boolean> = userPreferences.exactReminder
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** 勿扰下响铃（渠道更新由 ReminderController 观察同一偏好触发）。 */
    val reminderBypassDnd: StateFlow<Boolean> = userPreferences.reminderBypassDnd
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** 节假日自动同步开关。 */
    val holidaySyncEnabled: StateFlow<Boolean> = userPreferences.holidaySyncEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /** 上次节假日同步时刻（epoch ms）。 */
    val holidayLastSyncMs: StateFlow<Long> = userPreferences.holidayLastSyncMs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    /** 全部跳过日期（含补班日），按日期升序。 */
    val skipDates: StateFlow<List<SkipDate>> = holidayRepository.skipDates
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _holidayBusy = MutableStateFlow(false)
    val holidayBusy = _holidayBusy.asStateFlow()

    private val _holidayMessage = MutableStateFlow<HolidayMessage?>(null)
    val holidayMessage = _holidayMessage.asStateFlow()

    init {
        // 进页自动同步一次（内部自带开关 + 7 天节流，未到期/已关闭静默跳过）
        viewModelScope.launch { holidayRepository.refresh() }
    }

    fun setReminderLeadMinutes(value: Int) {
        viewModelScope.launch { userPreferences.setReminderLeadMinutes(value) }
    }

    fun setExamReminderLeadMinutes(value: Int) {
        viewModelScope.launch { userPreferences.setExamReminderLeadMinutes(value) }
    }

    fun setExactReminder(value: Boolean) {
        viewModelScope.launch { userPreferences.setExactReminder(value) }
    }

    fun setReminderBypassDnd(value: Boolean) {
        viewModelScope.launch { userPreferences.setReminderBypassDnd(value) }
    }

    fun setHolidaySyncEnabled(value: Boolean) {
        viewModelScope.launch { userPreferences.setHolidaySyncEnabled(value) }
    }

    fun refreshHolidays() {
        viewModelScope.launch {
            _holidayBusy.update { true }
            _holidayMessage.update { null }
            when (val result = holidayRepository.refresh(force = true)) {
                is HolidayRepository.RefreshResult.Success ->
                    _holidayMessage.update {
                        HolidayMessage(
                            UiText.Res(R.string.settings_holiday_synced, result.source, result.holidayCount),
                            false,
                        )
                    }
                is HolidayRepository.RefreshResult.Skipped ->
                    _holidayMessage.update { HolidayMessage(UiText.Res(R.string.settings_holiday_skipped), false) }
                is HolidayRepository.RefreshResult.Failed ->
                    _holidayMessage.update {
                        val text = result.failedSource
                            ?.let { UiText.Res(R.string.settings_holiday_failed, it) }
                            ?: UiText.Res(R.string.settings_holiday_no_source)
                        HolidayMessage(text, true)
                    }
            }
            _holidayBusy.update { false }
        }
    }

    fun addManualDate(epochDay: Long) {
        viewModelScope.launch { holidayRepository.addManualDate(epochDay) }
    }

    fun removeDate(epochDay: Long) {
        viewModelScope.launch { holidayRepository.removeDate(epochDay) }
    }
}
