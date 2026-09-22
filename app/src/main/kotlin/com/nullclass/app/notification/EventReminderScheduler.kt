package com.nullclass.app.notification

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.nullclass.core.data.prefs.UserPreferencesRepository
import com.nullclass.core.data.repository.CalendarEventRepository
import com.nullclass.core.model.EventReminderPlanner
import com.nullclass.core.model.ExamFormat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.time.Duration
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** 日程提醒编排：每个日程自带提前量，与学期无关；口径同 [ExamReminderScheduler]。 */
@Singleton
class EventReminderScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val eventRepository: CalendarEventRepository,
    private val userPrefs: UserPreferencesRepository,
) {

    suspend fun reschedule() {
        val wm = WorkManager.getInstance(context)
        val now = System.currentTimeMillis()
        val sentKeys = userPrefs.sentReminderKeys.first()
        val desired = mutableSetOf<String>()

        val events = eventRepository.getFrom(LocalDate.now().toEpochDay())
        EventReminderPlanner.upcoming(events, fromMillis = now).forEach { planned ->
            val tag = EventReminderPlanner.reminderTag(planned)
            if (tag in sentKeys) return@forEach
            val uniqueName = EventReminderPlanner.uniqueWorkName(planned)
            val event = planned.event
            val text = listOf(
                "${ExamFormat.dateLabel(event.dateEpochDay)} ${EventReminderPlanner.timeLabel(event)}",
                event.note.orEmpty(),
            ).filter { it.isNotBlank() }.joinToString(" · ")
            wm.enqueueUniqueWork(
                uniqueName,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<EventReminderWorker>()
                    // 提醒时刻已过但日程未开始：立即补发
                    .setInitialDelay(Duration.ofMillis((planned.remindAtMillis - now).coerceAtLeast(0)))
                    .setInputData(
                        workDataOf(
                            EventReminderWorker.KEY_TAG to tag,
                            EventReminderWorker.KEY_TITLE to event.title,
                            EventReminderWorker.KEY_TEXT to text,
                        ),
                    )
                    .addTag(TAG_EVENT_REMINDER)
                    .addTag(uniqueName)
                    .build(),
            )
            desired += uniqueName
        }

        // 只取消不再需要的（日程被删 / 改时间 / 关提醒）
        wm.getWorkInfosByTag(TAG_EVENT_REMINDER).get()
            .filter { it.state == WorkInfo.State.ENQUEUED }
            .forEach { info ->
                if (info.tags.none { it in desired }) wm.cancelWorkById(info.id)
            }
    }

    companion object {
        const val TAG_EVENT_REMINDER = "event_reminder"
    }
}
