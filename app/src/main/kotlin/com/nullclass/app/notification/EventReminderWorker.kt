package com.nullclass.app.notification

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.nullclass.core.data.prefs.UserPreferencesRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/** 日程提醒通知本体；每个日程的一次提醒对应一个幂等 Worker。 */
@HiltWorker
class EventReminderWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val userPrefs: UserPreferencesRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val tag = inputData.getString(KEY_TAG) ?: return Result.success()
        if (ReminderNotifier.post(
                context = applicationContext,
                tag = tag,
                title = inputData.getString(KEY_TITLE).orEmpty(),
                text = inputData.getString(KEY_TEXT).orEmpty(),
                channelId = NotificationChannels.EVENT_REMINDER,
            )
        ) {
            userPrefs.markRemindersSent(listOf(tag))
        }
        return Result.success()
    }

    companion object {
        const val KEY_TAG = "tag"
        const val KEY_TITLE = "title"
        const val KEY_TEXT = "text"
    }
}
