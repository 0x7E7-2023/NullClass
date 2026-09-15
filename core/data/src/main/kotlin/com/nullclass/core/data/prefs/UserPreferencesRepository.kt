package com.nullclass.core.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nullclass.core.model.WidgetFontSize
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.userPrefs by preferencesDataStore(name = "user_prefs")

/** 用户偏好（课程/考试提醒提前量等）。与 WebDAV 凭证（:sync）分库存储。 */
@Singleton
class UserPreferencesRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private object Keys {
        val ACTIVE_TIMETABLE_ID = stringPreferencesKey("active_timetable_id")
        val REMINDER_LEAD_MINUTES = intPreferencesKey("reminder_lead_minutes")
        val EXAM_REMINDER_LEAD_MINUTES = intPreferencesKey("exam_reminder_lead_minutes")
        val NOTIFICATION_PERMISSION_ASKED = booleanPreferencesKey("notification_permission_asked")
        val SHOW_WEEKEND = booleanPreferencesKey("show_weekend")
        val SHOW_TIME_IN_CARDS = booleanPreferencesKey("show_time_in_cards")
        val SHOW_NOW_LINE = booleanPreferencesKey("show_now_line")
        val SHOW_GRID_LINES = booleanPreferencesKey("show_grid_lines")
        val SHOW_OTHER_WEEK_COURSES = booleanPreferencesKey("show_other_week_courses")
        val WIDGET_FONT_SIZE = stringPreferencesKey("widget_font_size")
        val SENT_REMINDER_KEYS = stringSetPreferencesKey("sent_reminder_keys")
        val EXACT_REMINDER = booleanPreferencesKey("exact_reminder")
        val REMINDER_BYPASS_DND = booleanPreferencesKey("reminder_bypass_dnd")
        val HOLIDAY_SYNC_ENABLED = booleanPreferencesKey("holiday_sync_enabled")
        val HOLIDAY_LAST_SYNC_MS = longPreferencesKey("holiday_last_sync_ms")
        val SCHEDULED_ALARM_KEYS = stringSetPreferencesKey("scheduled_alarm_keys")
    }

    /**
     * 当前课表 id。**本地**选择，不进同步（换设备看同一份数据本来就少见，
     * 写进库列会被 LWW 传来传去，切换课表变成跨设备互相顶）。
     * 指向已删课表时的回落由 TimetableRepository 处理。
     */
    val activeTimetableId: Flow<String?> =
        context.userPrefs.data.map { it[Keys.ACTIVE_TIMETABLE_ID] }

    suspend fun setActiveTimetableId(value: String) {
        context.userPrefs.edit { it[Keys.ACTIVE_TIMETABLE_ID] = value }
    }

    /** 提前提醒分钟数；0 = 关闭。默认 15。 */
    val reminderLeadMinutes: Flow<Int> =
        context.userPrefs.data.map { it[Keys.REMINDER_LEAD_MINUTES] ?: DEFAULT_LEAD_MINUTES }

    /** 合法域 0..120，越界值收拢到边界。 */
    suspend fun setReminderLeadMinutes(value: Int) {
        val clamped = value.coerceIn(0, 120)
        context.userPrefs.edit { it[Keys.REMINDER_LEAD_MINUTES] = clamped }
    }

    /** 考试提醒提前量；0 = 关闭，默认考试前 1 天。 */
    val examReminderLeadMinutes: Flow<Int> =
        context.userPrefs.data.map {
            (it[Keys.EXAM_REMINDER_LEAD_MINUTES] ?: DEFAULT_EXAM_REMINDER_LEAD_MINUTES)
                .coerceIn(0, MAX_EXAM_REMINDER_LEAD_MINUTES)
        }

    suspend fun setExamReminderLeadMinutes(value: Int) {
        val clamped = value.coerceIn(0, MAX_EXAM_REMINDER_LEAD_MINUTES)
        context.userPrefs.edit { it[Keys.EXAM_REMINDER_LEAD_MINUTES] = clamped }
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

    /** 周视图是否显示每个节次与星期列的网格线。默认显示。 */
    val showGridLines: Flow<Boolean> =
        context.userPrefs.data.map { it[Keys.SHOW_GRID_LINES] ?: true }

    suspend fun setShowGridLines(value: Boolean) {
        context.userPrefs.edit { it[Keys.SHOW_GRID_LINES] = value }
    }

    /** 周视图是否在当周空着的时段里，把「别的周要上」的课以灰色显示。默认关闭。 */
    val showOtherWeekCourses: Flow<Boolean> =
        context.userPrefs.data.map { it[Keys.SHOW_OTHER_WEEK_COURSES] ?: false }

    suspend fun setShowOtherWeekCourses(value: Boolean) {
        context.userPrefs.edit { it[Keys.SHOW_OTHER_WEEK_COURSES] = value }
    }

    /** 桌面小组件字号档。默认标准；未知值回落标准。 */
    val widgetFontSize: Flow<WidgetFontSize> =
        context.userPrefs.data.map { WidgetFontSize.fromName(it[Keys.WIDGET_FONT_SIZE]) }

    suspend fun setWidgetFontSize(value: WidgetFontSize) {
        context.userPrefs.edit { it[Keys.WIDGET_FONT_SIZE] = value.name }
    }

    /**
     * 已实际发出的提醒通知 tag 集合（课程/考试各自的 reminder tag），迟发补发用它去重，
     * 防止每次重排都复活用户已划掉的通知。写入时顺手清掉 24h 前的旧键，集合不会无限膨胀。
     */
    val sentReminderKeys: Flow<Set<String>> =
        context.userPrefs.data.map { it[Keys.SENT_REMINDER_KEYS] ?: emptySet() }

    suspend fun markRemindersSent(keys: Collection<String>) {
        if (keys.isEmpty()) return
        context.userPrefs.edit { prefs ->
            val now = System.currentTimeMillis()
            val (keep, _) = (prefs[Keys.SENT_REMINDER_KEYS] ?: emptySet()).partition { key ->
                // 解析不出时间戳的脏数据直接淘汰
                val startAt = key.substringAfterLast(':', "").toLongOrNull() ?: return@partition false
                startAt > now - PRUNE_AFTER_MS
            }
            prefs[Keys.SENT_REMINDER_KEYS] = (keep + keys).toSet()
        }
    }

    /**
     * 精确闹钟提醒：开启且系统授权后，课程提醒改走 AlarmManager 精确闹钟；
     * 未授权/关闭时维持 WorkManager 方案。默认关闭。
     */
    val exactReminder: Flow<Boolean> =
        context.userPrefs.data.map { it[Keys.EXACT_REMINDER] ?: false }

    suspend fun setExactReminder(value: Boolean) {
        context.userPrefs.edit { it[Keys.EXACT_REMINDER] = value }
    }

    /** 提醒是否在勿扰模式下响铃（需已授予勿扰访问权限）。默认关闭。 */
    val reminderBypassDnd: Flow<Boolean> =
        context.userPrefs.data.map { it[Keys.REMINDER_BYPASS_DND] ?: false }

    suspend fun setReminderBypassDnd(value: Boolean) {
        context.userPrefs.edit { it[Keys.REMINDER_BYPASS_DND] = value }
    }

    /** 是否自动在线同步节假日信息。默认开启。 */
    val holidaySyncEnabled: Flow<Boolean> =
        context.userPrefs.data.map { it[Keys.HOLIDAY_SYNC_ENABLED] ?: true }

    suspend fun setHolidaySyncEnabled(value: Boolean) {
        context.userPrefs.edit { it[Keys.HOLIDAY_SYNC_ENABLED] = value }
    }

    /** 上次节假日同步成功的时刻（epoch ms）；0 = 从未同步。 */
    val holidayLastSyncMs: Flow<Long> =
        context.userPrefs.data.map { it[Keys.HOLIDAY_LAST_SYNC_MS] ?: 0L }

    suspend fun setHolidayLastSyncMs(value: Long) {
        context.userPrefs.edit { it[Keys.HOLIDAY_LAST_SYNC_MS] = value }
    }

    /**
     * 当前已排的精确闹钟键集合（blockId:startAt，与提醒 tag 同构）。
     * AlarmManager 无法枚举已排闹钟，取消阶段靠这份名单重建 PendingIntent。
     */
    val scheduledAlarmKeys: Flow<Set<String>> =
        context.userPrefs.data.map { it[Keys.SCHEDULED_ALARM_KEYS] ?: emptySet() }

    suspend fun setScheduledAlarmKeys(keys: Set<String>) {
        context.userPrefs.edit { it[Keys.SCHEDULED_ALARM_KEYS] = keys }
    }

    companion object {
        const val DEFAULT_LEAD_MINUTES = 15
        const val DEFAULT_EXAM_REMINDER_LEAD_MINUTES = 24 * 60
        private const val MAX_EXAM_REMINDER_LEAD_MINUTES = 7 * 24 * 60
        private const val PRUNE_AFTER_MS = 24L * 3600 * 1000
    }
}
