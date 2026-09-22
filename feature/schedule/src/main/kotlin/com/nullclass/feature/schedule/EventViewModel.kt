package com.nullclass.feature.schedule

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nullclass.core.data.repository.CalendarEventRepository
import com.nullclass.core.model.CalendarEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 日程安排页：全部日程 + 增删改。 */
@HiltViewModel
class EventViewModel @Inject constructor(
    private val repository: CalendarEventRepository,
) : ViewModel() {

    /** null = 首帧还没读到。 */
    val events: StateFlow<List<CalendarEvent>?> = repository.events
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun dismissMessage() {
        _message.value = null
    }

    /** 成功后才回调 [onSaved]（关弹窗）：失败时弹窗保留用户输入。 */
    fun save(event: CalendarEvent, onSaved: () -> Unit) = launchCatching("保存失败") {
        repository.upsert(event)
        onSaved()
    }

    fun delete(id: String) = launchCatching("删除失败") { repository.delete(id) }

    private fun launchCatching(label: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = "$label：${e.message ?: "请重试"}"
            }
        }
    }
}
