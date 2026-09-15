package com.nullclass.app.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.nullclass.core.data.prefs.UserPreferencesRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 精确闹钟到点投递：payload（标题/正文/tag）在排程时就算好塞进 extras，
 * 这里只负责发通知 + 落「已发送」键（与 ClassStartWorker 同一套去重口径）。
 */
@AndroidEntryPoint
class AlarmReceiver : BroadcastReceiver() {

    @Inject lateinit var userPrefs: UserPreferencesRepository

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_EXACT_REMINDER) return
        val tag = intent.getStringExtra(EXTRA_TAG) ?: return
        val title = intent.getStringExtra(EXTRA_TITLE) ?: return
        val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()

        val appContext = context.applicationContext
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                // 未授权 POST_NOTIFICATIONS 时 post 返回 false、不落键，
                // 与 Worker 路径一致：之后授权了还能由迟发补发补上
                if (ReminderNotifier.post(appContext, tag, title, text)) {
                    userPrefs.markRemindersSent(listOf(tag))
                }
            } catch (e: Exception) {
                Log.w(TAG, "exact reminder post failed for $tag", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "AlarmReceiver"

        const val ACTION_EXACT_REMINDER = "com.nullclass.app.action.EXACT_REMINDER"
        const val EXTRA_TAG = "tag"
        const val EXTRA_TITLE = "title"
        const val EXTRA_TEXT = "text"
    }
}
