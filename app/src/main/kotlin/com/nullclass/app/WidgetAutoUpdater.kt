package com.nullclass.app

import android.content.Context
import androidx.glance.appwidget.updateAll
import com.nullclass.core.data.repository.CourseRepository
import com.nullclass.core.data.repository.TermRepository
import com.nullclass.widget.NextClassGlanceWidget
import com.nullclass.widget.TodayGlanceWidget
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 小组件推送刷新：App 进程活着时，Room Flow 变化 → 防抖 → updateAll。
 * 进程死后由 DailyMaintenanceWorker 兜底。
 */
@Singleton
class WidgetAutoUpdater @Inject constructor(
    @ApplicationContext private val context: Context,
    private val termRepository: TermRepository,
    private val courseRepository: CourseRepository,
) {

    private val todayWidget = TodayGlanceWidget()
    private val nextClassWidget = NextClassGlanceWidget()

    fun start(scope: CoroutineScope) {
        scope.launch(Dispatchers.Default) {
            termRepository.observeCurrent()
                .flatMapLatest { term ->
                    if (term == null) {
                        flowOf(null)
                    } else {
                        courseRepository.observeSchedule(term.id).map { term }
                    }
                }
                // 编辑保存连发多条通知，防抖合并
                .debounce(500)
                .collect {
                    todayWidget.updateAll(context)
                    nextClassWidget.updateAll(context)
                }
        }
    }
}
