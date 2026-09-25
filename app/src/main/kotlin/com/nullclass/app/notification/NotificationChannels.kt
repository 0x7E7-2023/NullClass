package com.nullclass.app.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.annotation.StringRes
import com.nullclass.app.R

/** 通知渠道（Application.onCreate 里创建一次）。 */
object NotificationChannels {
    const val CLASS_REMINDER = "class_reminder"
    const val EXAM_REMINDER = "exam_reminder"
    const val EVENT_REMINDER = "event_reminder"

    /**
     * 渠道定义集中一处：ensureCreated 与 applyBypassDnd 重建渠道时保持文案一致。
     *
     * 名称与说明存的是资源 id：渠道文案由系统缓存，重建渠道时会按当时的语言重新取，
     * 存成已取好的字符串会让切换语言后系统设置里仍显示旧语言。
     */
    private data class ChannelSpec(
        val id: String,
        @StringRes val name: Int,
        @StringRes val description: Int,
    )

    private val channels = listOf(
        ChannelSpec(CLASS_REMINDER, R.string.app_channel_class, R.string.app_channel_class_desc),
        ChannelSpec(EXAM_REMINDER, R.string.app_channel_exam, R.string.app_channel_exam_desc),
        ChannelSpec(EVENT_REMINDER, R.string.app_channel_event, R.string.app_channel_event_desc),
    )

    fun ensureCreated(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        channels.forEach { spec ->
            manager.createNotificationChannel(
                NotificationChannel(
                    spec.id,
                    context.getString(spec.name),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply { description = context.getString(spec.description) },
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
                NotificationChannel(spec.id, context.getString(spec.name), NotificationManager.IMPORTANCE_DEFAULT)
                    .apply {
                        description = context.getString(spec.description)
                        setBypassDnd(enabled)
                    },
            )
        }
    }
}
