package com.nullclass.widget

import android.content.Context
import android.content.Intent
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback

/**
 * 小组件点击 → 打开应用并落在周课表页。
 *
 * 不用 actionStartActivity&lt;MainActivity&gt;（MainActivity 在 :app，:widget 看不到），
 * 用 LaunchIntent 解耦。
 */
class OpenAppAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        // 换掉 MAIN/LAUNCHER：与任务根 Intent 相同时系统可能只把任务提到前台、不投递 onNewIntent
        intent.setAction(ACTION_OPEN_SCHEDULE)
        intent.removeCategory(Intent.CATEGORY_LAUNCHER)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    companion object {
        const val ACTION_OPEN_SCHEDULE = "com.nullclass.widget.action.OPEN_SCHEDULE"
    }
}
