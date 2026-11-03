package com.nullclass.feature.settings.jw

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.webkit.Profile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.jwPrefs by preferencesDataStore(name = "jw_prefs")

/**
 * 一键刷新的记忆：上次用的适配器 + 上次成功的课表页地址。
 *
 * 登录态按课表对应的 WebView Profile 隔离保存；这里只记「从哪继续」和默认 Profile 的归属。
 */
@Singleton
class JwRefreshStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private object Keys {
        val LAST_ADAPTER = stringPreferencesKey("last_adapter_key")
        val LAST_SCHEDULE_URL = stringPreferencesKey("last_schedule_url")
        val AUTO_EXTRACT = booleanPreferencesKey("auto_extract")
        val OFFICIAL_LAST_CHECK = longPreferencesKey("official_last_check")
        val DEFAULT_PROFILE_OWNER = stringPreferencesKey("default_profile_owner")
        /** 旧 WebView 上共用登录态当前属于哪张课表；没记过就当来路不明，先清一次。 */
        val SHARED_PROFILE_OWNER = stringPreferencesKey("shared_profile_owner")
    }

    val lastAdapterKey: Flow<String?> = context.jwPrefs.data.map { it[Keys.LAST_ADAPTER] }

    val lastScheduleUrl: Flow<String?> = context.jwPrefs.data.map { it[Keys.LAST_SCHEDULE_URL] }

    /** 进入导入页是否自动提取（默认开）。 */
    val autoExtract: Flow<Boolean> = context.jwPrefs.data.map { it[Keys.AUTO_EXTRACT] ?: true }

    /**
     * 当前课表用哪个 WebView Profile。
     *
     * 旧 WebView 不支持多 Profile，所有课表只能共用默认 Profile：换了课表就先 [clearShared]
     * 再记到新课表名下，不让上一张课表的账号带过来（代价是换课表后要重新登录）。
     * 先清后记：中途被杀，下次还会再清一遍。默认 Profile 的归属一并改过去，日后 WebView 升级也不会串号。
     */
    suspend fun profileName(
        timetableId: String,
        multiProfile: Boolean,
        clearShared: suspend () -> Unit,
    ): String {
        if (!multiProfile) {
            if (context.jwPrefs.data.first()[Keys.SHARED_PROFILE_OWNER] != timetableId) {
                clearShared()
                context.jwPrefs.edit {
                    it[Keys.SHARED_PROFILE_OWNER] = timetableId
                    it[Keys.DEFAULT_PROFILE_OWNER] = timetableId
                }
            }
            return Profile.DEFAULT_PROFILE_NAME
        }
        var defaultOwner = timetableId
        context.jwPrefs.edit { prefs ->
            defaultOwner = prefs[Keys.DEFAULT_PROFILE_OWNER] ?: timetableId.also {
                prefs[Keys.DEFAULT_PROFILE_OWNER] = it
            }
        }
        return timetableProfileName(timetableId, defaultOwner)
    }

    suspend fun remember(adapterKey: String, scheduleUrl: String?) {
        context.jwPrefs.edit { prefs ->
            prefs[Keys.LAST_ADAPTER] = adapterKey
            if (scheduleUrl.isNullOrBlank()) {
                prefs.remove(Keys.LAST_SCHEDULE_URL)
            } else {
                prefs[Keys.LAST_SCHEDULE_URL] = scheduleUrl
            }
        }
    }

    suspend fun setAutoExtract(value: Boolean) {
        context.jwPrefs.edit { it[Keys.AUTO_EXTRACT] = value }
    }

    /** 官方适配器库上次检查更新的时间（epoch 毫秒）。 */
    val officialLastCheck: Flow<Long?> = context.jwPrefs.data.map { it[Keys.OFFICIAL_LAST_CHECK] }

    suspend fun setOfficialLastCheck(epochMillis: Long) {
        context.jwPrefs.edit { it[Keys.OFFICIAL_LAST_CHECK] = epochMillis }
    }
}
