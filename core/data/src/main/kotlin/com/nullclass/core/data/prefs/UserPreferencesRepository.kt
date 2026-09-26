package com.nullclass.core.data.prefs

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nullclass.core.data.locale.AppLocale
import com.nullclass.core.model.AppLanguage
import com.nullclass.core.model.BlockBorderStyle
import com.nullclass.core.model.BlockTextAlign
import com.nullclass.core.model.ScheduleAppearance
import com.nullclass.core.model.ThemeMode
import com.nullclass.core.model.WidgetFontSize
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject
import javax.inject.Singleton

// 文件损坏时整库重置为默认值：Application.onCreate 就在读这些偏好，抛异常等于每次启动必崩
private val Context.userPrefs by preferencesDataStore(
    name = "user_prefs",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

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
        val SHOW_EXAM_TAB = booleanPreferencesKey("show_exam_tab")
        val WIDGET_FONT_SIZE = stringPreferencesKey("widget_font_size")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val APP_LANGUAGE = stringPreferencesKey("app_language")
        val SENT_REMINDER_KEYS = stringSetPreferencesKey("sent_reminder_keys")
        val EXACT_REMINDER = booleanPreferencesKey("exact_reminder")
        val REMINDER_BYPASS_DND = booleanPreferencesKey("reminder_bypass_dnd")
        val HOLIDAY_SYNC_ENABLED = booleanPreferencesKey("holiday_sync_enabled")
        val HOLIDAY_LAST_SYNC_MS = longPreferencesKey("holiday_last_sync_ms")
        val SCHEDULED_ALARM_KEYS = stringSetPreferencesKey("scheduled_alarm_keys")

        // 个性化设置（ScheduleAppearance）。键不存在 = 默认值
        val APPEARANCE_TIMELINE_MODE = booleanPreferencesKey("appearance_timeline_mode")
        val APPEARANCE_HIDE_PERIOD_TIMES = booleanPreferencesKey("appearance_hide_period_times")
        val APPEARANCE_HIDE_HEADER_DATES = booleanPreferencesKey("appearance_hide_header_dates")
        val APPEARANCE_PAGE_TEXT_COLOR = intPreferencesKey("appearance_page_text_color")
        val APPEARANCE_PERIOD_CELL_HEIGHT = intPreferencesKey("appearance_period_cell_height")
        val APPEARANCE_TIMELINE_HOUR_HEIGHT = intPreferencesKey("appearance_timeline_hour_height")
        val APPEARANCE_SIDEBAR_WIDTH = intPreferencesKey("appearance_sidebar_width")
        val APPEARANCE_HEADER_HEIGHT = intPreferencesKey("appearance_header_height")
        val APPEARANCE_BLOCK_TEXT_COLOR = intPreferencesKey("appearance_block_text_color")
        val APPEARANCE_BLOCK_TEXT_ALIGN = stringPreferencesKey("appearance_block_text_align")
        val APPEARANCE_BLOCK_BORDER_STYLE = stringPreferencesKey("appearance_block_border_style")
        val APPEARANCE_BLOCK_TEXT_SCALE = intPreferencesKey("appearance_block_text_scale")
        val APPEARANCE_BLOCK_CORNER_RADIUS = intPreferencesKey("appearance_block_corner_radius")
        val APPEARANCE_BLOCK_SPACING = floatPreferencesKey("appearance_block_spacing")
        val APPEARANCE_BLOCK_OPACITY = intPreferencesKey("appearance_block_opacity")

        /** 当前课表壁纸在 filesDir/wallpaper/ 下的文件名，见 ScheduleWallpaperStore。 */
        val SCHEDULE_WALLPAPER_FILE = stringPreferencesKey("schedule_wallpaper_file")

        val APPEARANCE_ALL: List<Preferences.Key<*>> = listOf(
            APPEARANCE_TIMELINE_MODE, APPEARANCE_HIDE_PERIOD_TIMES, APPEARANCE_HIDE_HEADER_DATES,
            APPEARANCE_PAGE_TEXT_COLOR, APPEARANCE_PERIOD_CELL_HEIGHT, APPEARANCE_TIMELINE_HOUR_HEIGHT,
            APPEARANCE_SIDEBAR_WIDTH, APPEARANCE_HEADER_HEIGHT, APPEARANCE_BLOCK_TEXT_COLOR,
            APPEARANCE_BLOCK_TEXT_ALIGN, APPEARANCE_BLOCK_BORDER_STYLE, APPEARANCE_BLOCK_TEXT_SCALE,
            APPEARANCE_BLOCK_CORNER_RADIUS, APPEARANCE_BLOCK_SPACING, APPEARANCE_BLOCK_OPACITY,
        )
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

    /** 周课表外观（「我的 → 个性化设置」）。读出即过一遍 [ScheduleAppearance.sanitized]。 */
    val scheduleAppearance: Flow<ScheduleAppearance> =
        context.userPrefs.data.map { it.toScheduleAppearance() }.distinctUntilChanged()

    /**
     * 改外观。在同一次 edit 里读出当前值、套上 [transform]、只把**变了的字段**写回 ——
     * 调用方不必持有完整快照，也就不会拿旧快照把别处刚改的字段盖回去。
     * 等于默认值的字段直接删键，「没动过」和「改回默认」存成同一个样子。
     */
    suspend fun updateScheduleAppearance(transform: (ScheduleAppearance) -> ScheduleAppearance) {
        context.userPrefs.edit { prefs ->
            val current = prefs.toScheduleAppearance()
            val next = transform(current).sanitized()
            if (next != current) prefs.writeAppearance(current, next)
        }
    }

    /** 个性化设置恢复默认：外观全部回到默认，网格线恢复显示。壁纸由 ScheduleWallpaperStore 另行清除。 */
    suspend fun resetScheduleAppearance() {
        context.userPrefs.edit { prefs ->
            Keys.APPEARANCE_ALL.forEach { prefs.remove(it) }
            prefs.remove(Keys.SHOW_GRID_LINES)
        }
    }

    /** 当前课表壁纸的文件名；null = 没有壁纸。只给 [ScheduleWallpaperStore] 用。 */
    internal val scheduleWallpaperFile: Flow<String?> =
        context.userPrefs.data.map { it[Keys.SCHEDULE_WALLPAPER_FILE] }.distinctUntilChanged()

    internal suspend fun setScheduleWallpaperFile(name: String?) {
        context.userPrefs.edit {
            if (name == null) it.remove(Keys.SCHEDULE_WALLPAPER_FILE) else it[Keys.SCHEDULE_WALLPAPER_FILE] = name
        }
    }

    /** 指针仍是 [name] 时才清掉：读坏文件的这段时间里用户可能已经换了新壁纸，不能把新的一起清了。 */
    internal suspend fun clearScheduleWallpaperFileIf(name: String) {
        context.userPrefs.edit {
            if (it[Keys.SCHEDULE_WALLPAPER_FILE] == name) it.remove(Keys.SCHEDULE_WALLPAPER_FILE)
        }
    }

    /** 周视图是否在当周空着的时段里，把「别的周要上」的课以灰色显示。默认关闭。 */
    val showOtherWeekCourses: Flow<Boolean> =
        context.userPrefs.data.map { it[Keys.SHOW_OTHER_WEEK_COURSES] ?: false }

    suspend fun setShowOtherWeekCourses(value: Boolean) {
        context.userPrefs.edit { it[Keys.SHOW_OTHER_WEEK_COURSES] = value }
    }

    /** 底部导航是否显示「考试」标签页。默认显示。 */
    val showExamTab: Flow<Boolean> =
        context.userPrefs.data.map { it[Keys.SHOW_EXAM_TAB] ?: true }

    suspend fun setShowExamTab(value: Boolean) {
        context.userPrefs.edit { it[Keys.SHOW_EXAM_TAB] = value }
    }

    /** 桌面小组件字号档。默认标准；未知值回落标准。 */
    val widgetFontSize: Flow<WidgetFontSize> =
        context.userPrefs.data.map { WidgetFontSize.fromName(it[Keys.WIDGET_FONT_SIZE]) }

    suspend fun setWidgetFontSize(value: WidgetFontSize) {
        context.userPrefs.edit { it[Keys.WIDGET_FONT_SIZE] = value.name }
    }

    /** 应用配色模式。默认 Material 取色；未知值回落默认。每次读到都顺手写进 [lastThemeMode] 的镜像。 */
    val themeMode: Flow<ThemeMode> =
        context.userPrefs.data.map { ThemeMode.fromName(it[Keys.THEME_MODE]) }
            .distinctUntilChanged()
            .onEach { themeCache.edit().putString(THEME_MODE_CACHE_KEY, it.name).apply() }

    private val themeCache by lazy { context.getSharedPreferences("theme_cache", Context.MODE_PRIVATE) }

    /**
     * [themeMode] 上次读到的值，同步可读：Activity 首帧直接用它，
     * 不必等 DataStore 异步首发（否则要么先按默认主题画一帧再切，要么首帧空白）。以 DataStore 为准。
     */
    val lastThemeMode: ThemeMode
        get() = ThemeMode.fromName(themeCache.getString(THEME_MODE_CACHE_KEY, null))

    suspend fun setThemeMode(value: ThemeMode) {
        context.userPrefs.edit { it[Keys.THEME_MODE] = value.name }
    }

    /**
     * 应用界面语言。默认跟随系统；未知值回落到跟随系统。
     *
     * 读到值时顺手写入 [AppLocale] 的同步镜像 —— Activity 的 attachBaseContext
     * 早于依赖注入执行，无法在那里读 DataStore。
     */
    val appLanguage: Flow<AppLanguage> =
        context.userPrefs.data.map { AppLanguage.fromName(it[Keys.APP_LANGUAGE]) }
            .distinctUntilChanged()
            .onEach { AppLocale.cache(context, it) }

    suspend fun setAppLanguage(value: AppLanguage) {
        context.userPrefs.edit { it[Keys.APP_LANGUAGE] = value.name }
        AppLocale.cache(context, value)
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

    private fun Preferences.toScheduleAppearance(): ScheduleAppearance {
        val default = ScheduleAppearance()
        return ScheduleAppearance(
            timelineMode = this[Keys.APPEARANCE_TIMELINE_MODE] ?: default.timelineMode,
            hidePeriodTimes = this[Keys.APPEARANCE_HIDE_PERIOD_TIMES] ?: default.hidePeriodTimes,
            hideHeaderDates = this[Keys.APPEARANCE_HIDE_HEADER_DATES] ?: default.hideHeaderDates,
            pageTextColor = this[Keys.APPEARANCE_PAGE_TEXT_COLOR],
            periodCellHeightDp = this[Keys.APPEARANCE_PERIOD_CELL_HEIGHT],
            timelineHourHeightDp = this[Keys.APPEARANCE_TIMELINE_HOUR_HEIGHT],
            sidebarWidthDp = this[Keys.APPEARANCE_SIDEBAR_WIDTH],
            headerHeightDp = this[Keys.APPEARANCE_HEADER_HEIGHT],
            blockTextColor = this[Keys.APPEARANCE_BLOCK_TEXT_COLOR],
            blockTextAlign = BlockTextAlign.fromName(this[Keys.APPEARANCE_BLOCK_TEXT_ALIGN]),
            blockBorderStyle = BlockBorderStyle.fromName(this[Keys.APPEARANCE_BLOCK_BORDER_STYLE]),
            blockTextScalePercent = this[Keys.APPEARANCE_BLOCK_TEXT_SCALE] ?: default.blockTextScalePercent,
            blockCornerRadiusDp = this[Keys.APPEARANCE_BLOCK_CORNER_RADIUS] ?: default.blockCornerRadiusDp,
            blockSpacingDp = this[Keys.APPEARANCE_BLOCK_SPACING] ?: default.blockSpacingDp,
            blockOpacityPercent = this[Keys.APPEARANCE_BLOCK_OPACITY] ?: default.blockOpacityPercent,
        ).sanitized()
    }

    /** 只写 [current] → [next] 之间变了的字段；null 或等于默认值的删键。 */
    private fun MutablePreferences.writeAppearance(current: ScheduleAppearance, next: ScheduleAppearance) {
        val default = ScheduleAppearance()
        fun <T : Any> put(key: Preferences.Key<T>, old: T?, new: T?, defaultValue: T?) {
            if (old == new) return
            if (new == null || new == defaultValue) remove(key) else this[key] = new
        }
        put(Keys.APPEARANCE_TIMELINE_MODE, current.timelineMode, next.timelineMode, default.timelineMode)
        put(Keys.APPEARANCE_HIDE_PERIOD_TIMES, current.hidePeriodTimes, next.hidePeriodTimes, default.hidePeriodTimes)
        put(Keys.APPEARANCE_HIDE_HEADER_DATES, current.hideHeaderDates, next.hideHeaderDates, default.hideHeaderDates)
        put(Keys.APPEARANCE_PAGE_TEXT_COLOR, current.pageTextColor, next.pageTextColor, null)
        put(Keys.APPEARANCE_PERIOD_CELL_HEIGHT, current.periodCellHeightDp, next.periodCellHeightDp, null)
        put(Keys.APPEARANCE_TIMELINE_HOUR_HEIGHT, current.timelineHourHeightDp, next.timelineHourHeightDp, null)
        put(Keys.APPEARANCE_SIDEBAR_WIDTH, current.sidebarWidthDp, next.sidebarWidthDp, null)
        put(Keys.APPEARANCE_HEADER_HEIGHT, current.headerHeightDp, next.headerHeightDp, null)
        put(Keys.APPEARANCE_BLOCK_TEXT_COLOR, current.blockTextColor, next.blockTextColor, null)
        put(
            Keys.APPEARANCE_BLOCK_TEXT_ALIGN,
            current.blockTextAlign.name,
            next.blockTextAlign.name,
            default.blockTextAlign.name,
        )
        put(
            Keys.APPEARANCE_BLOCK_BORDER_STYLE,
            current.blockBorderStyle.name,
            next.blockBorderStyle.name,
            default.blockBorderStyle.name,
        )
        put(
            Keys.APPEARANCE_BLOCK_TEXT_SCALE,
            current.blockTextScalePercent,
            next.blockTextScalePercent,
            default.blockTextScalePercent,
        )
        put(
            Keys.APPEARANCE_BLOCK_CORNER_RADIUS,
            current.blockCornerRadiusDp,
            next.blockCornerRadiusDp,
            default.blockCornerRadiusDp,
        )
        put(Keys.APPEARANCE_BLOCK_SPACING, current.blockSpacingDp, next.blockSpacingDp, default.blockSpacingDp)
        put(
            Keys.APPEARANCE_BLOCK_OPACITY,
            current.blockOpacityPercent,
            next.blockOpacityPercent,
            default.blockOpacityPercent,
        )
    }

    companion object {
        const val DEFAULT_LEAD_MINUTES = 15
        const val DEFAULT_EXAM_REMINDER_LEAD_MINUTES = 24 * 60
        private const val MAX_EXAM_REMINDER_LEAD_MINUTES = 7 * 24 * 60
        private const val THEME_MODE_CACHE_KEY = "theme_mode"
        private const val PRUNE_AFTER_MS = 24L * 3600 * 1000
    }
}
