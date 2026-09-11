package com.nullclass.app.notification

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * 提醒通知投递：Worker 正常触发与「已开课」迟发补发共用，保证 tag/渠道/样式一致。
 * 同 tag 的重复投递会互相覆盖（迟发补发和迟到的原任务并发时不会叠两条）。
 */
object ReminderNotifier {

    private const val TAG = "ReminderNotifier"

    /**
     * 发一条提醒通知。未授权 POST_NOTIFICATIONS 或投递异常时返回 false（不落「已发送」，
     * 之后授权了还能由迟发补发路径补上）。
     */
    fun post(context: Context, tag: String, title: String, text: String): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        val contentIntent = context.packageManager
            .getLaunchIntentForPackage(context.packageName)
            ?.apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }
            ?.let { intent ->
                PendingIntent.getActivity(
                    context,
                    0,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            }
        val notification: Notification = NotificationCompat.Builder(
            context,
            NotificationChannels.CLASS_REMINDER,
        )
            .setSmallIcon(com.nullclass.app.R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()
        return try {
            NotificationManagerCompat.from(context).notify(tag, tag.hashCode(), notification)
            true
        } catch (e: Exception) {
            // 通知被系统策略关死等极端情况：记日志留排查线索，不当成发送成功
            Log.w(TAG, "notify failed for $tag", e)
            false
        }
    }
}
