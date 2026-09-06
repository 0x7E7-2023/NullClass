package com.nullclass.app.notification

import android.content.Context
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
 * - 重排提醒（horizon 窗口前移，14 天外的课还没入队）
 */
@HiltWorker
class DailyMaintenanceWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val reminderScheduler: ReminderScheduler,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        TodayGlanceWidget().updateAll(applicationContext)
        NextClassGlanceWidget().updateAll(applicationContext)
        reminderScheduler.reschedule()
        return Result.success()
    }
}

/** 入队每日维护任务（Application.onCreate 调用，KEEP 幂等）。 */
@Singleton
class DailyMaintenanceScheduler @Inject constructor() {
    fun enqueue(context: Context) {
        androidx.work.WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            androidx.work.ExistingPeriodicWorkPolicy.KEEP,
            androidx.work.PeriodicWorkRequestBuilder<DailyMaintenanceWorker>(24, java.util.concurrent.TimeUnit.HOURS)
                .setConstraints(
                    androidx.work.Constraints.Builder()
                        .setRequiresBatteryNotLow(true)
                        .build(),
                )
                .build(),
        )
    }

    companion object {
        const val WORK_NAME = "daily_maintenance"
    }
}
