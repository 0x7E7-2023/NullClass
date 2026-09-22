package com.nullclass.app.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

/** 通知渠道（Application.onCreate 里创建一次）。 */
object NotificationChannels {
    const val CLASS_REMINDER = "class_reminder"
    const val EXAM_REMINDER = "exam_reminder"
    const val EVENT_REMINDER = "event_reminder"

    /** 渠道定义集中一处：ensureCreated 与 applyBypassDnd 重建渠道时保持文案一致。 */
    private data class ChannelSpec(
        val id: String,
        val name: String,
        val description: String,
    )

    private val channels = listOf(
        ChannelSpec(CLASS_REMINDER, "课前提醒", "课程开始前的提醒通知"),
        ChannelSpec(EXAM_REMINDER, "考试提醒", "考试开始前的提醒通知"),
        ChannelSpec(EVENT_REMINDER, "日程提醒", "日程安排的提醒通知"),
    )

    fun ensureCreated(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        channels.forEach { spec ->
            manager.createNotificationChannel(
                NotificationChannel(spec.id, spec.name, NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = spec.description },
            )
        }
    }

    /**
     * 提醒渠道是否绕过勿扰。setBypassDnd 只对持有 ACCESS_NOTIFICATION_POLICY
     * （勿扰访问权限）的应用生效，未授权时调用会被系统忽略——所以开启前先由
     * 调用方确认授权；对已存在的渠道重跑 createNotificationChannel 即更新该项。
     */
    fun applyBypassDnd(context: Context, enabled: Boolean) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (enabled && !manager.isNotificationPolicyAccessGranted) return
        channels.forEach { spec ->
            manager.createNotificationChannel(
                NotificationChannel(spec.id, spec.name, NotificationManager.IMPORTANCE_DEFAULT)
                    .apply {
                        description = spec.description
                        setBypassDnd(enabled)
                    },
            )
        }
    }
}
