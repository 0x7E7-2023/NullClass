package com.nullclass.app.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

/** 通知渠道（Application.onCreate 里创建一次）。 */
object NotificationChannels {
    const val CLASS_REMINDER = "class_reminder"
    const val EXAM_REMINDER = "exam_reminder"

    fun ensureCreated(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CLASS_REMINDER,
                "课前提醒",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = "课程开始前的提醒通知" },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                EXAM_REMINDER,
                "考试提醒",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = "考试开始前的提醒通知" },
        )
    }
}
