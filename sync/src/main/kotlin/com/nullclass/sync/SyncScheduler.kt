package com.nullclass.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** 自动同步调度：按用户设定的周期入队 SyncWorker。 */
@Singleton
class SyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** 应用周期设置（设置页改档位 / App 启动重申持久化周期时调用）。 */
    fun apply(interval: AutoSyncInterval) {
        val wm = WorkManager.getInstance(context)
        if (interval == AutoSyncInterval.OFF) {
            wm.cancelUniqueWork(AUTO_SYNC_WORK)
            return
        }
        wm.enqueueUniquePeriodicWork(
            AUTO_SYNC_WORK,
            // 周期变化时替换已入队任务
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<SyncWorker>(interval.hours, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
                .build(),
        )
    }

    /** 刚改配置立即跑一次一次性同步，体感更即时。 */
    fun syncNow() {
        WorkManager.getInstance(context).enqueueUniqueWork(
            ONE_SHOT_SYNC_WORK,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build(),
        )
    }

    companion object {
        const val AUTO_SYNC_WORK = "auto_sync"
        const val ONE_SHOT_SYNC_WORK = "one_shot_sync"
    }
}
