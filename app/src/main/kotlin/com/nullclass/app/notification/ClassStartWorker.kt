package com.nullclass.app.notification

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * 课前提醒通知本体：每条提醒一个 Worker（ReminderScheduler 入队，幂等 uniqueWork）。
 * 未授权 POST_NOTIFICATIONS 时静默成功（设置页保留重试入口）。
 */
@HiltWorker
class ClassStartWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val name = inputData.getString(KEY_COURSE_NAME) ?: return Result.success()
        val location = inputData.getString(KEY_LOCATION).orEmpty()
        val periodLabel = inputData.getString(KEY_PERIOD_LABEL).orEmpty()
        val startTimeLabel = inputData.getString(KEY_START_TIME_LABEL).orEmpty()

        if (ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return Result.success()
        }

        val contentIntent = applicationContext.packageManager
            .getLaunchIntentForPackage(applicationContext.packageName)
            ?.apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }
            ?.let { intent ->
                PendingIntent.getActivity(
                    applicationContext,
                    0,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            }

        val text = listOf(periodLabel, location).filter { it.isNotBlank() }.joinToString(" · ")
        val notification: Notification = NotificationCompat.Builder(
            applicationContext,
            NotificationChannels.CLASS_REMINDER,
        )
            .setSmallIcon(com.nullclass.app.R.drawable.ic_notification)
            .setContentTitle("$startTimeLabel · $name")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()

        // 同一节课重排可能残留旧通知，用 blockId+startAt 做 tag 保证覆盖
        NotificationManagerCompat.from(applicationContext)
            .notify(notificationTag(), notificationId(), notification)
        return Result.success()
    }

    private fun notificationTag(): String =
        "${inputData.getString(KEY_BLOCK_ID)}:${inputData.getLong(KEY_START_AT, 0)}"

    private fun notificationId(): Int = notificationTag().hashCode()

    companion object {
        const val KEY_COURSE_NAME = "courseName"
        const val KEY_LOCATION = "location"
        const val KEY_PERIOD_LABEL = "periodLabel"
        const val KEY_START_TIME_LABEL = "startTimeLabel"
        const val KEY_BLOCK_ID = "blockId"
        const val KEY_START_AT = "startAt"
    }
}
