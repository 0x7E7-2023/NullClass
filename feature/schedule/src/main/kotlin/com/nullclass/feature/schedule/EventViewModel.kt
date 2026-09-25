package com.nullclass.feature.schedule

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nullclass.core.data.repository.CalendarEventRepository
import com.nullclass.core.model.CalendarEvent
import com.nullclass.core.ui.i18n.UiText
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

    /** 一次性错误提示。文案在界面层解析，语言切换后不会留下旧语言的残句。 */
    private val _message = MutableStateFlow<UiText?>(null)
    val message: StateFlow<UiText?> = _message.asStateFlow()

    fun dismissMessage() {
        _message.value = null
    }

    /** 成功后才回调 [onSaved]（关弹窗）：失败时弹窗保留用户输入。 */
    fun save(event: CalendarEvent, onSaved: () -> Unit) = launchCatching(
        failed = R.string.schedule_event_save_failed,
        failedWithDetail = R.string.schedule_event_save_failed_detail,
    ) {
        repository.upsert(event)
        onSaved()
    }

    fun delete(id: String) = launchCatching(
        failed = R.string.schedule_event_delete_failed,
        failedWithDetail = R.string.schedule_event_delete_failed_detail,
    ) { repository.delete(id) }

    /** 异常详情原样带出，便于反馈问题；没有详情时只给结论，不拼出半句话。 */
    private fun launchCatching(
        @StringRes failed: Int,
        @StringRes failedWithDetail: Int,
        block: suspend () -> Unit,
    ) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = e.message?.takeIf { it.isNotBlank() }
                    ?.let { UiText.Res(failedWithDetail, it) }
                    ?: UiText.Res(failed)
                // 日程只读写本机，不会抛带原因码的 DataException，这里保持「详情优先」
            }
        }
    }
}
