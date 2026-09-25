package com.nullclass.app

import android.app.Application
import android.content.Context
import android.content.res.Configuration as AndroidConfiguration
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.nullclass.app.notification.DailyMaintenanceScheduler
import com.nullclass.app.notification.NotificationChannels
import com.nullclass.app.notification.ReminderController
import com.nullclass.core.data.locale.AppLocale
import com.nullclass.core.data.prefs.UserPreferencesRepository
import com.nullclass.core.model.AppLanguage
import com.nullclass.sync.AutoSyncInterval
import com.nullclass.sync.SyncScheduler
import com.nullclass.sync.SyncSettingsRepository
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltAndroidApp
class NullClassApplication : Application(), Configuration.Provider {

    @Inject lateinit var widgetAutoUpdater: WidgetAutoUpdater
    @Inject lateinit var reminderController: ReminderController
    @Inject lateinit var dailyMaintenanceScheduler: DailyMaintenanceScheduler
    @Inject lateinit var syncScheduler: SyncScheduler
    @Inject lateinit var syncSettings: SyncSettingsRepository
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var userPreferences: UserPreferencesRepository
    @Inject lateinit var localeChanges: LocaleChanges

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * 在 Application 层就切好语言：通知、桌面小组件等在 Activity 之外取文案的路径，
     * 走的都是 Application Context 的资源。Android 13+ 由系统负责，[AppLocale.wrap] 原样返回。
     * 这只是启动时的一次；运行期切换见 [observeAppLanguage] 与 [onConfigurationChanged]。
     */
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLocale.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        NotificationChannels.ensureCreated(this)
        localeChanges.report(resources.configuration.locales)
        observeAppLanguage()

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

    override fun onConfigurationChanged(newConfig: AndroidConfiguration) {
        super.onConfigurationChanged(newConfig)
        if (AppLocale.isSystemManaged) {
            // 用户可能是在系统的「应用语言」页改的：把结果回写偏好，设置页才显示得对
            appScope.launch { syncLanguageFromSystem() }
        } else {
            // 系统配置变化会按启动时的配置重算 Application 资源，把当前选择重新套上
            AppLocale.applyToApplication(this, AppLocale.current(this))
        }
        localeChanges.report(resources.configuration.locales)
    }

    /**
     * 让 Application 的语言与用户选择保持一致，并在变化时刷新 Activity 之外的文字。
     *
     * - 12 及以下：偏好一变就把新语言套到 Application 资源上（Activity 由 MainActivity 自行重建）；
     * - 13+：以系统 LocaleManager 为准，偏好只是它的镜像。
     */
    private fun observeAppLanguage() {
        if (AppLocale.isSystemManaged) {
            appScope.launch {
                val stored = userPreferences.appLanguage.first()
                // 从 12 升上来的设备：旧选择只在本地，交给系统一次；之后系统说了算
                if (AppLocale.takeSystemMigration(this@NullClassApplication) &&
                    stored != AppLanguage.SYSTEM &&
                    AppLocale.current(this@NullClassApplication) == AppLanguage.SYSTEM
                ) {
                    AppLocale.applyToSystem(this@NullClassApplication, stored)
                } else {
                    syncLanguageFromSystem()
                }
            }
        } else {
            appScope.launch {
                userPreferences.appLanguage.collect { language ->
                    withContext(Dispatchers.Main) {
                        AppLocale.applyToApplication(this@NullClassApplication, language)
                        localeChanges.report(resources.configuration.locales)
                    }
                }
            }
        }
        // 渠道名是创建时取的文字，同 id 再建一次即更新为新语言
        appScope.launch {
            localeChanges.version.drop(1).collect { NotificationChannels.ensureCreated(this@NullClassApplication) }
        }
    }

    private suspend fun syncLanguageFromSystem() {
        val actual = AppLocale.current(this)
        if (userPreferences.appLanguage.first() != actual) userPreferences.setAppLanguage(actual)
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
