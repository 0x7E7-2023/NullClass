package com.nullclass.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/** 后台自动同步（SyncScheduler 编排，周期由用户设置）。 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val syncManager: SyncManager,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = when (val result = syncManager.sync()) {
        is SyncResult.Success -> Result.success()
        // 配置被清了，正常结束（不该 retry）
        is SyncResult.NotConfigured -> Result.success()
        is SyncResult.Error ->
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
    }

    companion object {
        private const val MAX_ATTEMPTS = 3
    }
}
