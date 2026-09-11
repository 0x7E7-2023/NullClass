package com.nullclass.app.notification

import android.content.Context
import android.util.Log
import androidx.glance.appwidget.updateAll
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.nullclass.widget.NextClassGlanceWidget
import com.nullclass.widget.TodayGlanceWidget
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 每日维护 Worker（24h 周期兜底）：
 * - 刷新两个小组件（跨天后 App 没打开也要更新「今日」）
 * - 重排提醒（horizon 窗口前移，14 天外的课还没入队）+ 迟发补发对账
 *
 * 这是进程死掉后唯一的兜底通道，失败必须 retry（此前无条件 success，一次失败
 * 小组件就能陈旧到用户手动打开 App）。不带任何约束：一天一次的代价不值得
 * 用 battery-not-low 换——低电量时它恰恰是最需要跑的时刻（跨天零点附近）。
 */
@HiltWorker
class DailyMaintenanceWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val reminderScheduler: ReminderScheduler,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        try {
            TodayGlanceWidget().updateAll(applicationContext)
            NextClassGlanceWidget().updateAll(applicationContext)
            reminderScheduler.reschedule()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "daily maintenance failed, retrying", e)
            return Result.retry()
        }
        return Result.success()
    }

    private companion object {
        const val TAG = "DailyMaintenanceWorker"
    }
}

/**
 * 入队每日维护任务（Application.onCreate 调用）。
 *
 * 对齐每天 00:05（KEEP 保相位）：跨天刷新不再取决于用户哪天装的 App——
 * 旧版「从入队时刻起算的随机相位」会让早上的小组件一直是昨天的课，最长近 24h。
 * 一次性迁移：旧名任务（随机相位 + 电量约束）显式取消，新名重新排。
 * 注：周期是「上次运行 + 24h」，夏令时切换后会漂移一小时，仍在深夜窗口内，可接受。
 */
@Singleton
class DailyMaintenanceScheduler @Inject constructor() {
    fun enqueue(context: Context) {
        val wm = androidx.work.WorkManager.getInstance(context)
        wm.cancelUniqueWork(LEGACY_WORK_NAME)
        wm.enqueueUniquePeriodicWork(
            WORK_NAME,
            androidx.work.ExistingPeriodicWorkPolicy.KEEP,
            androidx.work.PeriodicWorkRequestBuilder<DailyMaintenanceWorker>(24, java.util.concurrent.TimeUnit.HOURS)
                .setInitialDelay(java.time.Duration.between(java.time.ZonedDateTime.now(), nextRun()))
                .build(),
        )
    }

    /** 下一个 00:05（本地时区）；今天 00:05 已过则取明天。 */
    private fun nextRun(): java.time.ZonedDateTime {
        val now = java.time.ZonedDateTime.now()
        val today = now.toLocalDate().atTime(0, 5).atZone(now.zone)
        return if (today.isAfter(now)) today else today.plusDays(1)
    }

    companion object {
        const val WORK_NAME = "daily_maintenance_midnight"
        const val LEGACY_WORK_NAME = "daily_maintenance"
    }
}
