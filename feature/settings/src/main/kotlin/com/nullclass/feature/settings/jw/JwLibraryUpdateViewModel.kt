package com.nullclass.feature.settings.jw

import com.nullclass.feature.settings.R
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nullclass.core.ui.R as CoreR
import com.nullclass.core.ui.i18n.UiText
import com.nullclass.importer.jw.JwAdapterRepository
import com.nullclass.importer.jw.JwOfficialCheck
import com.nullclass.importer.jw.JwOfficialLibrary
import com.nullclass.importer.jw.JwOfficialStore
import com.nullclass.importer.jw.JwOfficialUpdater
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class JwLibraryUpdateState(
    val version: String? = null,
    val adapterCount: Int = 0,
    val lastCheck: Long? = null,
    val busy: Boolean = false,
    /** 检查到的比当前新的版本（非 null 时按钮变成「点击更新」）。 */
    val available: JwOfficialCheck? = null,
)

/** 官方适配器库热更新：检查（官方源 + 反代并发）→ 下载验签 → 落盘 → 换上。 */
@HiltViewModel
class JwLibraryUpdateViewModel @Inject constructor(
    private val repository: JwAdapterRepository,
    private val updater: JwOfficialUpdater,
    private val officialStore: JwOfficialStore,
    private val refreshStore: JwRefreshStore,
) : ViewModel() {

    private val _state = MutableStateFlow(snapshot())
    val state = _state.asStateFlow()

    private val _toasts = Channel<UiText>(Channel.BUFFERED)
    val toasts = _toasts.receiveAsFlow()

    init {
        viewModelScope.launch {
            refreshStore.officialLastCheck.collect { t -> _state.update { it.copy(lastCheck = t) } }
        }
    }

    private fun snapshot(): JwLibraryUpdateState =
        JwLibraryUpdateState(version = repository.builtinVersion, adapterCount = repository.builtin.size)

    fun check() {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val check = withContext(Dispatchers.IO) { updater.check() }
            val current = repository.builtinVersion
            if (check.versions.isEmpty()) {
                _state.update { it.copy(busy = false) }
                _toasts.send(UiText.Res(R.string.settings_jw_library_check_failed))
                return@launch
            }
            refreshStore.setOfficialLastCheck(System.currentTimeMillis())
            val latest = check.latest
            if (JwOfficialLibrary.compareVersions(latest, current) > 0) {
                _state.update { it.copy(busy = false, available = check) }
                _toasts.send(UiText.Res(R.string.settings_jw_library_found, latest.orEmpty()))
            } else {
                _state.update { it.copy(busy = false, available = null) }
                _toasts.send(UiText.Res(R.string.settings_jw_library_latest))
            }
        }
    }

    fun update() {
        val check = _state.value.available ?: return
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            try {
                val (download, shadowed) = withContext(Dispatchers.IO) {
                    val download = updater.download(check, repository.builtinVersion)
                    officialStore.save(download)
                    download to repository.replaceBuiltin(download.bundle)
                }
                _state.update { snapshot().copy(lastCheck = it.lastCheck) }
                _toasts.send(
                    UiText.Res(
                        R.string.settings_jw_library_updated,
                        download.version,
                        // 有被官方收录的同名适配器时补一句前缀；UiText 支持嵌套代入
                        if (shadowed.isEmpty()) {
                            ""
                        } else {
                            UiText.Res(
                                R.string.settings_jw_library_shadowed,
                                UiText.Joined(shadowed, CoreR.string.common_list_separator),
                            )
                        },
                    ),
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(busy = false) }
                _toasts.send(
                    UiText.Res(
                        R.string.settings_jw_library_update_failed,
                        // 有异常原文就用原文（不翻），没有就退回一条可翻译的通用词条
                        e.message?.takeIf { it.isNotBlank() }?.let { UiText.Dynamic(it) }
                            ?: UiText.Res(R.string.settings_jw_unknown_error),
                    ),
                )
            }
        }
    }
}
