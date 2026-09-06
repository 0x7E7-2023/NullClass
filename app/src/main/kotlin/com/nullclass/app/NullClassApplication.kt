package com.nullclass.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.nullclass.app.notification.DailyMaintenanceScheduler
import com.nullclass.app.notification.NotificationChannels
import com.nullclass.app.notification.ReminderController
import com.nullclass.sync.AutoSyncInterval
import com.nullclass.sync.SyncScheduler
import com.nullclass.sync.SyncSettingsRepository
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class NullClassApplication : Application(), Configuration.Provider {

    @Inject lateinit var widgetAutoUpdater: WidgetAutoUpdater
    @Inject lateinit var reminderController: ReminderController
    @Inject lateinit var dailyMaintenanceScheduler: DailyMaintenanceScheduler
    @Inject lateinit var syncScheduler: SyncScheduler
    @Inject lateinit var syncSettings: SyncSettingsRepository
    @Inject lateinit var workerFactory: HiltWorkerFactory

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        NotificationChannels.ensureCreated(this)

        widgetAutoUpdater.start(appScope)
        reminderController.start(appScope)
        dailyMaintenanceScheduler.enqueue(this)

        // 持久化的自动同步周期在重启后重申入队（WorkManager 本身持久，UPDATE 策略下重申无害）
        appScope.launch {
            val interval: AutoSyncInterval = syncSettings.autoSyncInterval.first()
            if (interval != AutoSyncInterval.OFF) {
                syncScheduler.apply(interval)
            }
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
