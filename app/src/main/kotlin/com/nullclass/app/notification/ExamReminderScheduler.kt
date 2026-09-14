package com.nullclass.app.notification

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.nullclass.core.data.prefs.UserPreferencesRepository
import com.nullclass.core.data.repository.ExamRepository
import com.nullclass.core.data.repository.TermRepository
import com.nullclass.core.model.ExamFormat
import com.nullclass.core.model.ExamReminderPlanner
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.time.Duration
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** 考试提醒编排：按独立的考试提醒提前量排算未来一段时间的考试。 */
@Singleton
class ExamReminderScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val termRepository: TermRepository,
    private val examRepository: ExamRepository,
    private val userPrefs: UserPreferencesRepository,
) {

    suspend fun reschedule() {
        val wm = WorkManager.getInstance(context)
        val leadMinutes = userPrefs.examReminderLeadMinutes.first()
        val term = termRepository.getCurrent()
        val desired = mutableSetOf<String>()

        if (leadMinutes != 0 && term != null) {
            val now = System.currentTimeMillis()
            val sentKeys = userPrefs.sentReminderKeys.first()
            val exams = examRepository.getForTerm(term.id)

            ExamReminderPlanner.upcoming(
                exams = exams,
                fromMillis = now,
                leadMinutes = leadMinutes,
                zone = ZoneId.systemDefault(),
            ).forEach { planned ->
                val reminderTag = ExamReminderPlanner.reminderTag(planned)
                if (reminderTag in sentKeys) return@forEach

                val uniqueName = ExamReminderPlanner.uniqueWorkName(
                    planned.exam.exam.id,
                    planned.remindAtMillis,
                )
                wm.enqueueUniqueWork(
                    uniqueName,
                    ExistingWorkPolicy.REPLACE,
                    OneTimeWorkRequestBuilder<ExamReminderWorker>()
                        // 提醒时刻已过但考试尚未开始时，立即补发一次，避免用户刚改完考试信息却错过提醒。
                        .setInitialDelay(Duration.ofMillis((planned.remindAtMillis - now).coerceAtLeast(0)))
                        .setInputData(
                            workDataOf(
                                ExamReminderWorker.KEY_TAG to reminderTag,
                                ExamReminderWorker.KEY_COURSE_NAME to planned.exam.course.name,
                                ExamReminderWorker.KEY_EXAM_TITLE to planned.exam.exam.title,
                                ExamReminderWorker.KEY_DATE_LABEL to ExamFormat.dateLabel(planned.exam.exam.dateEpochDay),
                                ExamReminderWorker.KEY_TIME_LABEL to (ExamFormat.timeRange(planned.exam.exam) ?: "时间待定"),
                                ExamReminderWorker.KEY_LOCATION to (planned.exam.exam.location ?: ""),
                                ExamReminderWorker.KEY_SEAT to (planned.exam.exam.seat ?: ""),
                            ),
                        )
                        .addTag(TAG_EXAM_REMINDER)
                        // 名单标记：取消阶段据此识别这条 work 还需不需要。
                        .addTag(uniqueName)
                        .build(),
                )
                desired += uniqueName
            }
        }

        // 只取消不再需要的考试提醒：考试被删、换学期、提前量改变或提醒关闭。
        wm.getWorkInfosByTag(TAG_EXAM_REMINDER).get()
            .filter { it.state == WorkInfo.State.ENQUEUED }
            .forEach { info ->
                if (info.tags.none { it in desired }) {
                    wm.cancelWorkById(info.id)
                }
            }
    }

    companion object {
        const val TAG_EXAM_REMINDER = "exam_reminder"
    }
}

