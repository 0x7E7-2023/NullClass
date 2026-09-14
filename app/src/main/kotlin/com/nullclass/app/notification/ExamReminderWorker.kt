package com.nullclass.app.notification

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.nullclass.core.data.prefs.UserPreferencesRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/** 考试提醒通知本体；每场考试的一次提醒对应一个幂等 Worker。 */
@HiltWorker
class ExamReminderWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val userPrefs: UserPreferencesRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val tag = inputData.getString(KEY_TAG) ?: return Result.success()
        val courseName = inputData.getString(KEY_COURSE_NAME).orEmpty()
        val examTitle = inputData.getString(KEY_EXAM_TITLE).orEmpty()
        val dateLabel = inputData.getString(KEY_DATE_LABEL).orEmpty()
        val timeLabel = inputData.getString(KEY_TIME_LABEL).orEmpty()
        val location = inputData.getString(KEY_LOCATION).orEmpty()
        val seat = inputData.getString(KEY_SEAT).orEmpty()
        val text = buildList {
            if (dateLabel.isNotBlank()) add(dateLabel)
            if (timeLabel.isNotBlank()) add(timeLabel)
            if (location.isNotBlank()) add(location)
            if (seat.isNotBlank()) add("座位 $seat")
        }.joinToString(" · ")

        if (ReminderNotifier.post(
                context = applicationContext,
                tag = tag,
                title = listOf(courseName, examTitle).filter { it.isNotBlank() }.joinToString(" · "),
                text = text,
                channelId = NotificationChannels.EXAM_REMINDER,
            )
        ) {
            userPrefs.markRemindersSent(listOf(tag))
        }
        return Result.success()
    }

    companion object {
        const val KEY_TAG = "tag"
        const val KEY_COURSE_NAME = "courseName"
        const val KEY_EXAM_TITLE = "examTitle"
        const val KEY_DATE_LABEL = "dateLabel"
        const val KEY_TIME_LABEL = "timeLabel"
        const val KEY_LOCATION = "location"
        const val KEY_SEAT = "seat"
    }
}

