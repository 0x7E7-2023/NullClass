package com.nullclass.app.notification

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.nullclass.core.data.repository.CourseRepository
import com.nullclass.core.data.repository.DayOverrideRepository
import com.nullclass.core.data.repository.HolidayRepository
import com.nullclass.core.data.repository.TermRepository
import com.nullclass.widget.buildTodaySnapshot
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 小组件边界闹钟：系统允许精确闹钟时，在下一个上课/下课/零点时刻排一个闹钟，
 * 到点由 [SystemEventReceiver] 刷小组件并排下一个，自成一条链。
 *
 * 补的是两个缺口：进程被杀后只剩 30 分钟一次的系统兜底；进程活着时协程 delay
 * 在息屏深睡期间不走表。只看系统授权，与「精确提醒」开关无关。未授权时什么都不做。
 */
@Singleton
class WidgetBoundaryAlarm @Inject constructor(
    @ApplicationContext private val context: Context,
    private val exactAlarmScheduler: ExactAlarmScheduler,
    private val termRepository: TermRepository,
    private val courseRepository: CourseRepository,
    private val dayOverrideRepository: DayOverrideRepository,
    private val holidayRepository: HolidayRepository,
) {

    /** 按当前课表重排下一个边界；同一个 PendingIntent，重复调用只是覆盖。 */
    suspend fun schedule() {
        if (!exactAlarmScheduler.exactAllowed()) return
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val snapshot = buildTodaySnapshot(
            termRepository,
            courseRepository,
            dayOverrideRepository,
            holidayRepository,
        )
        val now = LocalTime.now()
        val boundary = snapshot.nextBoundaryMinute(now.hour * 60 + now.minute)
        val today = LocalDate.now()
        val zone = ZoneId.systemDefault()
        val at = if (boundary >= 1440) {
            today.plusDays(1).atStartOfDay(zone)
        } else {
            today.atTime(boundary / 60, boundary % 60).atZone(zone)
        }
        val pi = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, SystemEventReceiver::class.java).setAction(ACTION_WIDGET_BOUNDARY),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toInstant().toEpochMilli(), pi)
        } catch (e: SecurityException) {
            // 授权在检查之后被收回：放弃这一次，退回系统 30 分钟兜底
            Log.w(TAG, "widget boundary alarm rejected", e)
        }
    }

    companion object {
        private const val TAG = "WidgetBoundaryAlarm"
        const val ACTION_WIDGET_BOUNDARY = "com.nullclass.app.action.WIDGET_BOUNDARY"
    }
}
