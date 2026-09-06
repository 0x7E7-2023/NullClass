package com.nullclass.core.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.userPrefs by preferencesDataStore(name = "user_prefs")

/** 用户偏好（提醒提前量等）。与 WebDAV 凭证（:sync）分库存储。 */
@Singleton
class UserPreferencesRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private object Keys {
        val REMINDER_LEAD_MINUTES = intPreferencesKey("reminder_lead_minutes")
        val NOTIFICATION_PERMISSION_ASKED = booleanPreferencesKey("notification_permission_asked")
    }

    /** 提前提醒分钟数；0 = 关闭。默认 15。 */
    val reminderLeadMinutes: Flow<Int> =
        context.userPrefs.data.map { it[Keys.REMINDER_LEAD_MINUTES] ?: DEFAULT_LEAD_MINUTES }

    /** 合法域 0..120，越界值收拢到边界。 */
    suspend fun setReminderLeadMinutes(value: Int) {
        val clamped = value.coerceIn(0, 120)
        context.userPrefs.edit { it[Keys.REMINDER_LEAD_MINUTES] = clamped }
    }

    /** 通知权限引导是否已展示过（一次性引导，拒绝不打扰）。 */
    val notificationPermissionAsked: Flow<Boolean> =
        context.userPrefs.data.map { it[Keys.NOTIFICATION_PERMISSION_ASKED] ?: false }

    suspend fun setNotificationPermissionAsked() {
        context.userPrefs.edit { it[Keys.NOTIFICATION_PERMISSION_ASKED] = true }
    }

    companion object {
        const val DEFAULT_LEAD_MINUTES = 15
    }
}
