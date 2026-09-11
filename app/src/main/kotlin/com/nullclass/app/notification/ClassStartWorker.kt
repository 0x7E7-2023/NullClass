package com.nullclass.app.notification

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.nullclass.core.data.prefs.UserPreferencesRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * 课前提醒通知本体：每条提醒一个 Worker（ReminderScheduler 入队，幂等 uniqueWork）。
 * 发出后落「已发送」键，迟发补发用它去重。
 */
@HiltWorker
class ClassStartWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val userPrefs: UserPreferencesRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val name = inputData.getString(KEY_COURSE_NAME) ?: return Result.success()
        val location = inputData.getString(KEY_LOCATION).orEmpty()
        val periodLabel = inputData.getString(KEY_PERIOD_LABEL).orEmpty()
        val startTimeLabel = inputData.getString(KEY_START_TIME_LABEL).orEmpty()
        val tag = notificationTag()

        val text = listOf(periodLabel, location).filter { it.isNotBlank() }.joinToString(" · ")
        // 未授权 POST_NOTIFICATIONS 时不落键：静默成功但不算「已发送」，
        // 之后授权了还能由迟发补发补上「已开始」
        if (ReminderNotifier.post(applicationContext, tag, "$startTimeLabel · $name", text)) {
            userPrefs.markRemindersSent(listOf(tag))
        }
        return Result.success()
    }

    private fun notificationTag(): String =
        "${inputData.getString(KEY_BLOCK_ID)}:${inputData.getLong(KEY_START_AT, 0)}"

    companion object {
        const val KEY_COURSE_NAME = "courseName"
        const val KEY_LOCATION = "location"
        const val KEY_PERIOD_LABEL = "periodLabel"
        const val KEY_START_TIME_LABEL = "startTimeLabel"
        const val KEY_BLOCK_ID = "blockId"
        const val KEY_START_AT = "startAt"
    }
}
