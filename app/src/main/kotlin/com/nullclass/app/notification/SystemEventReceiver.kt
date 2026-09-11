package com.nullclass.app.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.glance.appwidget.updateAll
import com.nullclass.widget.NextClassGlanceWidget
import com.nullclass.widget.TodayGlanceWidget
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 系统事件对账：开机 / 应用更新完成 / 用户改系统时间 / 换时区 后立即
 * 刷小组件 + 重排提醒（含迟发补发）。
 *
 * 不做这些对账的后果：WorkManager 的 delay 不感知墙钟跳变（改时间后提醒全错位）；
 * 应用更新后进程要等用户打开才起，小组件陈旧；开机后早八的课没人补发。
 * 四个都是 protected broadcast，exported=false 即可；BOOT_COMPLETED 需要声明
 * RECEIVE_BOOT_COMPLETED（normal 权限，安装即授予，用户无感）。
 */
@AndroidEntryPoint
class SystemEventReceiver : BroadcastReceiver() {

    @Inject lateinit var reminderScheduler: ReminderScheduler

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            -> Unit
            else -> return
        }
        val appContext = context.applicationContext
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                TodayGlanceWidget().updateAll(appContext)
                NextClassGlanceWidget().updateAll(appContext)
                reminderScheduler.reschedule()
            } catch (e: Exception) {
                Log.w(TAG, "system event reconcile failed (${intent.action})", e)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "SystemEventReceiver"
    }
}
