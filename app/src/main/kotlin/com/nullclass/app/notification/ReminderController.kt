package com.nullclass.app.notification

import android.content.Context
import com.nullclass.core.data.prefs.UserPreferencesRepository
import com.nullclass.core.data.repository.CalendarEventRepository
import com.nullclass.core.data.repository.CourseRepository
import com.nullclass.core.data.repository.DayOverrideRepository
import com.nullclass.core.data.repository.ExamRepository
import com.nullclass.core.data.repository.HolidayRepository
import com.nullclass.core.data.repository.TermRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 提醒控制器：Application 常驻，观察课表/考试/日程/学期/偏好/跳过日期/串课变化 → 防抖 → 全量重排。
 * 另观察「勿扰下响铃」偏好，变化时同步通知渠道的 bypassDnd。
 */
@Singleton
class ReminderController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val scheduler: ReminderScheduler,
    private val termRepository: TermRepository,
    private val courseRepository: CourseRepository,
    private val examRepository: ExamRepository,
    private val holidayRepository: HolidayRepository,
    private val dayOverrideRepository: DayOverrideRepository,
    private val eventRepository: CalendarEventRepository,
    private val userPrefs: UserPreferencesRepository,
) {

    fun start(scope: CoroutineScope) {
        scope.launch(Dispatchers.Default) {
            combine(
                termRepository.observeCurrent(),
                userPrefs.reminderLeadMinutes,
                userPrefs.examReminderLeadMinutes,
                // 精确提醒开关切换要重排（两条路径互切时先清对方再排自己）
                userPrefs.exactReminder,
                // 日程不挂学期，放在外层：没有学期也要排
                eventRepository.events,
            ) { term, _, _, _, _ -> term }
                .flatMapLatest { term ->
                    if (term == null) {
                        flowOf(Unit)
                    } else {
                        combine(
                            courseRepository.observeSchedule(term.id),
                            examRepository.observeForTerm(term.id),
                            // 跳过日期变化（手动增删 / 节假日同步落库）触发重排
                            holidayRepository.skipDates,
                            // 串课变化：那天要提醒的是另一天的课
                            dayOverrideRepository.index,
                        ) { _, _, _, _ -> Unit }
                    }
                }
                // 编辑保存连发多条通知，防抖合并
                .debounce(800)
                .collect {
                    try {
                        scheduler.reschedule()
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // 单次重排失败不能杀死常驻收集协程（下次数据变化会重试）
                        android.util.Log.w("ReminderController", "reschedule failed", e)
                    }
                }
        }

        // 勿扰下响铃：偏好落定/变化即更新渠道（启动时也会跑一次，恢复已保存的选择）
        scope.launch {
            userPrefs.reminderBypassDnd.collect { enabled ->
                NotificationChannels.applyBypassDnd(context, enabled)
            }
        }
    }
}
