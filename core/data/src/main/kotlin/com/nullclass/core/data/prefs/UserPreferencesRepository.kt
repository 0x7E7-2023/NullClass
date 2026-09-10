package com.nullclass.core.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nullclass.core.model.WidgetFontSize
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
        val SHOW_WEEKEND = booleanPreferencesKey("show_weekend")
        val SHOW_TIME_IN_CARDS = booleanPreferencesKey("show_time_in_cards")
        val SHOW_NOW_LINE = booleanPreferencesKey("show_now_line")
        val WIDGET_FONT_SIZE = stringPreferencesKey("widget_font_size")
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

    /** 周视图是否显示周末两列。默认显示。 */
    val showWeekend: Flow<Boolean> =
        context.userPrefs.data.map { it[Keys.SHOW_WEEKEND] ?: true }

    suspend fun setShowWeekend(value: Boolean) {
        context.userPrefs.edit { it[Keys.SHOW_WEEKEND] = value }
    }

    /** 起止时间显示位置：false = 节次列内（默认）；true = 课块左上/右下角，节次列随之收窄。 */
    val showTimeInCards: Flow<Boolean> =
        context.userPrefs.data.map { it[Keys.SHOW_TIME_IN_CARDS] ?: false }

    suspend fun setShowTimeInCards(value: Boolean) {
        context.userPrefs.edit { it[Keys.SHOW_TIME_IN_CARDS] = value }
    }

    /** 周视图是否画当前时间线。默认显示。 */
    val showNowLine: Flow<Boolean> =
        context.userPrefs.data.map { it[Keys.SHOW_NOW_LINE] ?: true }

    suspend fun setShowNowLine(value: Boolean) {
        context.userPrefs.edit { it[Keys.SHOW_NOW_LINE] = value }
    }

    /** 桌面小组件字号档。默认标准；未知值回落标准。 */
    val widgetFontSize: Flow<WidgetFontSize> =
        context.userPrefs.data.map { WidgetFontSize.fromName(it[Keys.WIDGET_FONT_SIZE]) }

    suspend fun setWidgetFontSize(value: WidgetFontSize) {
        context.userPrefs.edit { it[Keys.WIDGET_FONT_SIZE] = value.name }
    }

    companion object {
        const val DEFAULT_LEAD_MINUTES = 15
    }
}
