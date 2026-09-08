package com.nullclass.feature.settings.jw

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nullclass.importer.jw.JwAdapter
import com.nullclass.importer.jw.JwAdapterRepository
import com.nullclass.importer.jw.JwBrokenAdapter
import com.nullclass.importer.jw.JwLibraryClient
import com.nullclass.importer.jw.JwLibraryEntry
import com.nullclass.importer.jw.JwLibrarySnapshot
import com.nullclass.importer.jw.JwPackage
import com.nullclass.importer.jw.JwPackageReader
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class JwUiState(
    val builtin: List<JwAdapter> = emptyList(),
    val user: List<JwAdapter> = emptyList(),
    val broken: List<JwBrokenAdapter> = emptyList(),
    val busy: Boolean = false,
    val message: String? = null,
    val messageIsError: Boolean = false,
    /** 从链接拉取到的库索引（非 null 时展示候选列表）。 */
    val library: JwLibrarySnapshot? = null,
    val libraryUrl: String = "",
    /** 待用户确认安装的适配器（zip 或链接）。 */
    val pendingInstall: List<JwAdapter> = emptyList(),
    val pendingSourceUrl: String? = null,
    val pendingFileName: String? = null,
    /** 正在查看详情/脚本的适配器。 */
    val viewing: JwAdapter? = null,
    val lastAdapterKey: String? = null,
    val lastScheduleUrl: String? = null,
    val autoExtract: Boolean = true,
)

/**
 * 教务适配器管理：内置 / 用户添加 / 从链接拉取 / 一键刷新状态。
 *
 * 用户导入的适配器**不经过我们的代码审计**，安装前必须由用户确认（UI 层负责）。
 */
@HiltViewModel
class JwImportViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: JwAdapterRepository,
    private val libraryClient: JwLibraryClient,
    private val refreshStore: JwRefreshStore,
) : ViewModel() {

    private val _state = MutableStateFlow(JwUiState())
    val state = _state.asStateFlow()

    init {
        refresh()
        viewModelScope.launch {
            _state.update {
                it.copy(
                    lastAdapterKey = refreshStore.lastAdapterKey.first(),
                    lastScheduleUrl = refreshStore.lastScheduleUrl.first(),
                    autoExtract = refreshStore.autoExtract.first(),
                )
            }
        }
    }

    /** 重新扫描用户适配器（读盘，必须在 IO 线程）。 */
    fun refresh() {
        viewModelScope.launch {
            val library = withContext(Dispatchers.IO) { repository.userLibrary() }
            _state.update {
                it.copy(
                    builtin = repository.builtin,
                    user = library.adapters,
                    broken = library.broken,
                )
            }
        }
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    fun dismissLibrary() = _state.update { it.copy(library = null) }

    fun dismissInstall() = _state.update {
        it.copy(pendingInstall = emptyList(), pendingSourceUrl = null, pendingFileName = null)
    }

    fun showDetails(adapter: JwAdapter) = _state.update { it.copy(viewing = adapter) }

    fun dismissDetails() = _state.update { it.copy(viewing = null) }

    // ---- 导入 zip ----

    fun importZip(uri: Uri, fileName: String?) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = null) }
            try {
                val bytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: error("无法读取文件")
                }
                val pkg = withContext(Dispatchers.Default) {
                    JwPackageReader.readZip(
                        bytes = bytes,
                        source = com.nullclass.importer.jw.JwAdapterSource.USER,
                        appVersionCode = JwInstallSupport.appVersionCode(context),
                    )
                }
                _state.update {
                    it.copy(
                        busy = false,
                        pendingInstall = pkg.adapters,
                        pendingSourceUrl = null,
                        pendingFileName = fileName,
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, message = e.message ?: "适配器包读取失败", messageIsError = true) }
            }
        }
    }

    // ---- 从链接拉取 ----

    fun loadLibrary(url: String) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = null, libraryUrl = url) }
            try {
                val snapshot = withContext(Dispatchers.IO) { libraryClient.loadIndex(url) }
                _state.update { it.copy(busy = false, library = snapshot) }
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, message = e.message ?: "适配器库读取失败", messageIsError = true) }
            }
        }
    }

    fun fetchFromLibrary(entry: JwLibraryEntry) {
        val snapshot = _state.value.library ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = null) }
            try {
                val adapter = withContext(Dispatchers.IO) { libraryClient.fetchAdapter(snapshot, entry) }
                _state.update {
                    it.copy(
                        busy = false,
                        library = null,
                        pendingInstall = listOf(adapter),
                        pendingSourceUrl = snapshot.url,
                        pendingFileName = null,
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, message = e.message ?: "适配器下载失败", messageIsError = true) }
            }
        }
    }

    // ---- 安装 / 删除 ----

    fun confirmInstall() {
        val state = _state.value
        if (state.pendingInstall.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = null) }
            try {
                val installed = withContext(Dispatchers.Default) {
                    repository.install(
                        pkg = JwPackage(state.pendingInstall),
                        installedAt = System.currentTimeMillis(),
                        sourceUrl = state.pendingSourceUrl,
                        fileName = state.pendingFileName,
                    )
                }
                _state.update {
                    it.copy(
                        busy = false,
                        pendingInstall = emptyList(),
                        pendingSourceUrl = null,
                        pendingFileName = null,
                        message = "已添加：${installed.joinToString("、") { adapter -> adapter.displayName }}",
                        messageIsError = false,
                    )
                }
                refresh()
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, message = e.message ?: "安装失败", messageIsError = true) }
            }
        }
    }

    fun delete(adapter: JwAdapter) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { repository.delete(adapter.key) }
            _state.update {
                it.copy(
                    viewing = null,
                    message = if (ok) "已删除「${adapter.displayName}」" else "内置适配器不能删除",
                    messageIsError = !ok,
                )
            }
            refresh()
        }
    }

    // ---- 一键刷新 ----

    fun rememberRefresh(adapterKey: String, scheduleUrl: String?) {
        viewModelScope.launch {
            refreshStore.remember(adapterKey, scheduleUrl)
            _state.update { it.copy(lastAdapterKey = adapterKey, lastScheduleUrl = scheduleUrl) }
        }
    }

    fun setAutoExtract(value: Boolean) {
        viewModelScope.launch {
            refreshStore.setAutoExtract(value)
            _state.update { it.copy(autoExtract = value) }
        }
    }

    /** 上次用过、且仍然存在的适配器（一键刷新的入口条件）。 */
    fun lastAdapter(): JwAdapter? = _state.value.lastAdapterKey?.let { repository.byKey(it) }
}
