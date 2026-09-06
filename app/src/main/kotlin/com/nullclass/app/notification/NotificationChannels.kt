package com.nullclass.app.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

/** 通知渠道（Application.onCreate 里创建一次）。 */
object NotificationChannels {
    const val CLASS_REMINDER = "class_reminder"

    fun ensureCreated(context: Context) {
        val channel = NotificationChannel(
            CLASS_REMINDER,
            "课前提醒",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = "课程开始前的提醒通知" }
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }
}
