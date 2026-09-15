package com.nullclass.app.notification

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import com.nullclass.core.data.prefs.UserPreferencesRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/** 一条精确闹钟提醒：通知 tag 唯一，标题/正文在排程时算好（与 Worker 路径同构）。 */
data class ExactReminder(
    val tag: String,
    /** 触发时刻（epoch ms）。 */
    val remindAtMillis: Long,
    val title: String,
    val text: String,
)

/**
 * 精确提醒闹钟编排（可选，默认关）：开关开且系统授权时由 [ReminderScheduler] 切入。
 *
 * - PendingIntent 用 data URI（含 tag）参与匹配：同 tag 的闹钟幂等覆盖，不同 tag 互不干扰
 * - AlarmManager 无法枚举已排闹钟，取消靠 DataStore 里的已排键名单重建 PendingIntent
 * - 闹钟不跨重启：开机/改时间的 SystemEventReceiver → reschedule 兜底重排
 */
@Singleton
class ExactAlarmScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val userPrefs: UserPreferencesRepository,
) {

    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    /** 系统层面是否允许精确闹钟（Android 12 以下无此限制）。 */
    fun exactAllowed(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager?.canScheduleExactAlarms() == true

    /** 业务开关 + 系统授权都满足才走精确路径。 */
    suspend fun canUseExact(): Boolean =
        userPrefs.exactReminder.first() && exactAllowed()

    /**
     * 按名单重排：先设新闹钟、再按旧名单取消多余的（与 WorkManager 路径同思路，
     * 中断不留空窗）。名单外（课被删/提前量改档/提醒关闭）的闹钟逐一取消。
     */
    suspend fun reschedule(reminders: List<ExactReminder>, now: Long) {
        val am = alarmManager ?: return
        val desired = reminders.filter { it.remindAtMillis > now }
        desired.forEach { reminder ->
            val pi = pendingIntent(reminder, noCreate = false) ?: return@forEach
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.remindAtMillis, pi)
            } catch (e: SecurityException) {
                // 授权在排程中途被收回：本条放弃，reschedule 入口处会回落 WorkManager
                Log.w(TAG, "setExact failed for ${reminder.tag}", e)
            } catch (e: Exception) {
                Log.w(TAG, "setExact failed for ${reminder.tag}", e)
            }
        }
        val stored = userPrefs.scheduledAlarmKeys.first()
        val desiredTags = desired.map { it.tag }.toSet()
        (stored - desiredTags).forEach { tag -> cancelByTag(tag) }
        userPrefs.setScheduledAlarmKeys(desiredTags)
    }

    /** 全部取消（切回 WorkManager 路径 / 提醒关闭 / 无学期时调用）。 */
    suspend fun cancelAll() {
        userPrefs.scheduledAlarmKeys.first().forEach { tag -> cancelByTag(tag) }
        userPrefs.setScheduledAlarmKeys(emptySet())
    }

    private fun cancelByTag(tag: String) {
        val am = alarmManager ?: return
        val pi = pendingIntent(ExactReminder(tag, 0, "", ""), noCreate = true) ?: return
        am.cancel(pi)
    }

    private fun pendingIntent(reminder: ExactReminder, noCreate: Boolean): PendingIntent? {
        val intent = Intent(context, AlarmReceiver::class.java)
            .setAction(AlarmReceiver.ACTION_EXACT_REMINDER)
            // data URI 参与 PendingIntent 匹配（extras 不参与），唯一确定一条闹钟
            .setData(Uri.parse("nullclass://exact_reminder/${Uri.encode(reminder.tag)}"))
            .putExtra(AlarmReceiver.EXTRA_TAG, reminder.tag)
            .putExtra(AlarmReceiver.EXTRA_TITLE, reminder.title)
            .putExtra(AlarmReceiver.EXTRA_TEXT, reminder.text)
        val flags = if (noCreate) {
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        }
        return PendingIntent.getBroadcast(context, 0, intent, flags)
    }

    private companion object {
        const val TAG = "ExactAlarmScheduler"
    }
}
