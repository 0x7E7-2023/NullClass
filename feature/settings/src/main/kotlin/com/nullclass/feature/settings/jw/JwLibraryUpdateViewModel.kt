package com.nullclass.feature.settings.jw

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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

    private val _toasts = Channel<String>(Channel.BUFFERED)
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
                _toasts.send("检查失败：所有更新源都连不上，请稍后再试")
                return@launch
            }
            refreshStore.setOfficialLastCheck(System.currentTimeMillis())
            val latest = check.latest
            if (JwOfficialLibrary.compareVersions(latest, current) > 0) {
                _state.update { it.copy(busy = false, available = check) }
                _toasts.send("检测到新版本 v$latest")
            } else {
                _state.update { it.copy(busy = false, available = null) }
                _toasts.send("已是最新版本")
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
                    "适配器库已更新到 v${download.version}" +
                        if (shadowed.isEmpty()) "" else "；官方已收录 ${shadowed.joinToString("、")}，同名的自添加适配器将改用官方版",
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(busy = false) }
                _toasts.send("更新失败：${e.message ?: "未知错误"}")
            }
        }
    }
}
