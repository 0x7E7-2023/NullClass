package com.nullclass.app.notification

import com.nullclass.core.data.repository.CourseRepository
import com.nullclass.core.data.repository.TermRepository
import com.nullclass.core.data.prefs.UserPreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers

/**
 * 提醒控制器：Application 常驻，观察课表/学期/偏好变化 → 防抖 → 全量重排。
 */
@Singleton
class ReminderController @Inject constructor(
    private val scheduler: ReminderScheduler,
    private val termRepository: TermRepository,
    private val courseRepository: CourseRepository,
    private val userPrefs: UserPreferencesRepository,
) {

    fun start(scope: CoroutineScope) {
        scope.launch(Dispatchers.Default) {
            combine(
                termRepository.observeCurrent(),
                userPrefs.reminderLeadMinutes,
            ) { term, lead -> term to lead }
                .flatMapLatest { (term, _) ->
                    if (term == null) flowOf(emptyList()) else courseRepository.observeSchedule(term.id)
                }
                // 编辑保存连发多条通知，防抖合并
                .debounce(800)
                .collect { scheduler.reschedule() }
        }
    }
}
