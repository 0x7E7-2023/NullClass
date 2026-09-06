package com.nullclass.app.notification

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.nullclass.core.data.repository.CourseRepository
import com.nullclass.core.data.repository.TermRepository
import com.nullclass.core.data.prefs.UserPreferencesRepository
import com.nullclass.core.model.ReminderPlanner
import com.nullclass.core.model.ScheduleFormat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 提醒编排：排算未来 14 天的上课时刻，每条入队一个 OneTimeWorkRequest。
 *
 * - 持久化、免权限；Doze 下误差 0~15 分钟（课表场景可接受，文档明示）
 * - uniqueWork 名含 blockId+startAt 保证幂等；重排先 cancelAllWorkByTag 再全量入队
 * - 已错过的提醒（remindAt <= now）不补发
 */
@Singleton
class ReminderScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val termRepository: TermRepository,
    private val courseRepository: CourseRepository,
    private val userPrefs: UserPreferencesRepository,
) {

    suspend fun reschedule() {
        val wm = WorkManager.getInstance(context)
        wm.cancelAllWorkByTag(TAG_CLASS_REMINDER)

        val leadMinutes = userPrefs.reminderLeadMinutes.first()
        if (leadMinutes == 0) return

        val term = termRepository.getCurrent() ?: return
        val schedule = courseRepository.observeSchedule(term.id).first()
        val times = termRepository.getPeriodTimes(term.id)
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()

        ReminderPlanner.upcoming(term, schedule, times, now, horizonDays = HORIZON_DAYS, zone = zone)
            .forEach { upcoming ->
                val remindAt = upcoming.startAtMillis - leadMinutes * 60_000L
                if (remindAt <= now) return@forEach // 已错过不补发
                val startMinuteOfDay = Instant.ofEpochMilli(upcoming.startAtMillis)
                    .atZone(zone).toLocalTime().let { it.hour * 60 + it.minute }
                wm.enqueueUniqueWork(
                    uniqueNameOf(upcoming.block.id, upcoming.startAtMillis),
                    ExistingWorkPolicy.REPLACE,
                    OneTimeWorkRequestBuilder<ClassStartWorker>()
                        .setInitialDelay(Duration.ofMillis(remindAt - now))
                        .setInputData(
                            workDataOf(
                                ClassStartWorker.KEY_COURSE_NAME to upcoming.course.name,
                                ClassStartWorker.KEY_LOCATION to (upcoming.block.location ?: ""),
                                ClassStartWorker.KEY_PERIOD_LABEL to ScheduleFormat.periodRange(upcoming.block),
                                ClassStartWorker.KEY_START_TIME_LABEL to ScheduleFormat.minuteLabel(startMinuteOfDay),
                                ClassStartWorker.KEY_BLOCK_ID to upcoming.block.id,
                                ClassStartWorker.KEY_START_AT to upcoming.startAtMillis,
                            ),
                        )
                        .addTag(TAG_CLASS_REMINDER)
                        .build(),
                )
            }
    }

    companion object {
        const val TAG_CLASS_REMINDER = "class_reminder"
        const val HORIZON_DAYS = 14

        fun uniqueNameOf(blockId: String, startAtMillis: Long): String =
            "class_reminder_$blockId" + "_$startAtMillis"
    }
}
