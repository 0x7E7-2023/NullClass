package com.nullclass.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nullclass.core.data.prefs.UserPreferencesRepository
import android.content.Context
import com.nullclass.core.data.locale.AppLocale
import com.nullclass.core.model.AppLanguage
import com.nullclass.core.model.ThemeMode
import com.nullclass.core.model.WidgetFontSize
import com.nullclass.sync.AutoSyncInterval
import com.nullclass.sync.SyncManager
import com.nullclass.sync.SyncResult
import com.nullclass.sync.SyncScheduler
import com.nullclass.sync.SyncSettingsRepository
import com.nullclass.sync.WebDavConfig
import com.nullclass.core.ui.i18n.UiText
import com.nullclass.sync.SyncError
import com.nullclass.sync.WebDavResult
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
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
    val message: UiText? = null,
    val messageIsError: Boolean = false,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val syncManager: SyncManager,
    private val syncSettings: SyncSettingsRepository,
    private val syncScheduler: SyncScheduler,
    private val userPreferences: UserPreferencesRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state = _state.asStateFlow()

    val lastSyncAt: StateFlow<Long?> = syncSettings.lastSyncAtFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 自动同步周期。 */
    val autoSyncInterval: StateFlow<AutoSyncInterval> = syncSettings.autoSyncInterval
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AutoSyncInterval.OFF)

    /** 桌面小组件字号档。 */
    val widgetFontSize: StateFlow<WidgetFontSize> = userPreferences.widgetFontSize
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WidgetFontSize.STANDARD)

    /** 周视图是否在空着的时段显示非本周的课。与课表「显示设置」里的开关是同一个偏好。 */
    val showOtherWeekCourses: StateFlow<Boolean> = userPreferences.showOtherWeekCourses
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** 应用配色模式。 */
    val themeMode: StateFlow<ThemeMode> = userPreferences.themeMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThemeMode.DYNAMIC)

    /** 应用界面语言。 */
    val appLanguage: StateFlow<AppLanguage> = userPreferences.appLanguage
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppLanguage.SYSTEM)

    /** 底部导航是否显示「考试」标签页。 */
    val showExamTab: StateFlow<Boolean> = userPreferences.showExamTab
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    fun setThemeMode(value: ThemeMode) {
        viewModelScope.launch { userPreferences.setThemeMode(value) }
    }

    /**
     * 切换界面语言。
     *
     * Android 13+ 交给系统的 LocaleManager，由它负责重建界面；
     * 低版本由 MainActivity 观察偏好变化后自行重建。见 [AppLocale]。
     */
    fun setAppLanguage(value: AppLanguage) {
        viewModelScope.launch {
            userPreferences.setAppLanguage(value)
            AppLocale.applyToSystem(context, value)
        }
    }

    fun setShowExamTab(value: Boolean) {
        viewModelScope.launch { userPreferences.setShowExamTab(value) }
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
            _state.update { it.copy(message = UiText.Res(error.messageRes), messageIsError = true) }
            return
        }
        viewModelScope.launch {
            syncSettings.saveConfig(config)
            _state.update {
                it.copy(
                    configured = true,
                    message = UiText.Res(R.string.settings_webdav_saved),
                    messageIsError = false,
                )
            }
        }
    }

    fun testConnection() {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = null) }
            val result = syncManager.testConnection()
            _state.update {
                when (result) {
                    WebDavResult.Ok -> it.copy(
                        busy = false,
                        message = UiText.Res(R.string.settings_webdav_connected),
                        messageIsError = false,
                    )
                    is WebDavResult.Error -> it.copy(
                        busy = false,
                        message = result.failure.toUiText(),
                        messageIsError = true,
                    )
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
                        message = UiText.Plural(R.plurals.settings_webdav_sync_done, result.adoptedFromRemote),
                        messageIsError = false,
                    )
                }
                is SyncResult.NotConfigured -> _state.update {
                    it.copy(
                        busy = false,
                        message = UiText.Res(SyncError.NOT_CONFIGURED.messageRes),
                        messageIsError = true,
                    )
                }
                is SyncResult.Error -> _state.update {
                    it.copy(busy = false, message = result.failure.toUiText(), messageIsError = true)
                }
            }
        }
    }
}
