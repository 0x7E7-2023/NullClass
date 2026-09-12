package com.nullclass.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nullclass.core.data.prefs.UserPreferencesRepository
import com.nullclass.core.model.WidgetFontSize
import com.nullclass.sync.AutoSyncInterval
import com.nullclass.sync.SyncManager
import com.nullclass.sync.SyncResult
import com.nullclass.sync.SyncScheduler
import com.nullclass.sync.SyncSettingsRepository
import com.nullclass.sync.WebDavConfig
import com.nullclass.sync.WebDavResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val url: String = "",
    val username: String = "",
    val password: String = "",
    val configured: Boolean = false,
    val lastSyncAt: Long? = null,
    val busy: Boolean = false,
    /** 操作结果提示（连接测试/同步）。 */
    val message: String? = null,
    val messageIsError: Boolean = false,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val syncManager: SyncManager,
    private val syncSettings: SyncSettingsRepository,
    private val syncScheduler: SyncScheduler,
    private val userPreferences: UserPreferencesRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state = _state.asStateFlow()

    val lastSyncAt: StateFlow<Long?> = syncSettings.lastSyncAtFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 提前提醒分钟数（0 = 关闭）。 */
    val reminderLeadMinutes: StateFlow<Int> = userPreferences.reminderLeadMinutes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UserPreferencesRepository.DEFAULT_LEAD_MINUTES)

    /** 自动同步周期。 */
    val autoSyncInterval: StateFlow<AutoSyncInterval> = syncSettings.autoSyncInterval
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AutoSyncInterval.OFF)

    /** 桌面小组件字号档。 */
    val widgetFontSize: StateFlow<WidgetFontSize> = userPreferences.widgetFontSize
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WidgetFontSize.STANDARD)

    /** 周视图是否在空着的时段显示非本周的课。与课表「显示设置」里的开关是同一个偏好。 */
    val showOtherWeekCourses: StateFlow<Boolean> = userPreferences.showOtherWeekCourses
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setReminderLeadMinutes(value: Int) {
        viewModelScope.launch { userPreferences.setReminderLeadMinutes(value) }
    }

    fun setShowOtherWeekCourses(value: Boolean) {
        viewModelScope.launch { userPreferences.setShowOtherWeekCourses(value) }
    }

    fun setWidgetFontSize(value: WidgetFontSize) {
        viewModelScope.launch { userPreferences.setWidgetFontSize(value) }
    }

    fun setAutoSyncInterval(value: AutoSyncInterval) {
        viewModelScope.launch {
            syncSettings.setAutoSyncInterval(value)
            syncScheduler.apply(value)
            // 改为开启时立即跑一次一次性同步，体感更即时
            if (value != AutoSyncInterval.OFF) syncScheduler.syncNow()
        }
    }

    init {
        viewModelScope.launch {
            syncSettings.getConfig()?.let { config ->
                _state.update {
                    it.copy(
                        url = config.url,
                        username = config.username,
                        password = config.password,
                        configured = true,
                    )
                }
            }
        }
    }

    fun setUrl(value: String) = _state.update { it.copy(url = value, message = null) }

    fun setUsername(value: String) = _state.update { it.copy(username = value, message = null) }

    fun setPassword(value: String) = _state.update { it.copy(password = value, message = null) }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    fun saveConfig() {
        val config = WebDavConfig(_state.value.url, _state.value.username, _state.value.password)
        config.validate()?.let { error ->
            _state.update { it.copy(message = error, messageIsError = true) }
            return
        }
        viewModelScope.launch {
            syncSettings.saveConfig(config)
            _state.update { it.copy(configured = true, message = "已保存", messageIsError = false) }
        }
    }

    fun testConnection() {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = null) }
            val result = syncManager.testConnection()
            _state.update {
                when (result) {
                    WebDavResult.Ok -> it.copy(busy = false, message = "连接成功", messageIsError = false)
                    is WebDavResult.Error -> it.copy(busy = false, message = result.message, messageIsError = true)
                }
            }
        }
    }

    fun syncNow() {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = null) }
            when (val result = syncManager.sync()) {
                is SyncResult.Success -> _state.update {
                    it.copy(
                        busy = false,
                        message = "同步完成：采纳 ${result.adoptedFromRemote} 条远端记录",
                        messageIsError = false,
                    )
                }
                is SyncResult.NotConfigured -> _state.update {
                    it.copy(busy = false, message = result.message, messageIsError = true)
                }
                is SyncResult.Error -> _state.update {
                    it.copy(busy = false, message = result.message, messageIsError = true)
                }
            }
        }
    }
}
