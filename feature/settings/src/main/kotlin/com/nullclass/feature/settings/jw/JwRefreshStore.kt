package com.nullclass.feature.settings.jw

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.jwPrefs by preferencesDataStore(name = "jw_prefs")

/**
 * 一键刷新的记忆：上次用的适配器 + 上次成功的课表页地址。
 *
 * 登录态本身由 WebView 的数据目录保留（不杀进程、不清数据），这里只记「从哪继续」。
 */
@Singleton
class JwRefreshStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private object Keys {
        val LAST_ADAPTER = stringPreferencesKey("last_adapter_key")
        val LAST_SCHEDULE_URL = stringPreferencesKey("last_schedule_url")
        val AUTO_EXTRACT = booleanPreferencesKey("auto_extract")
    }

    val lastAdapterKey: Flow<String?> = context.jwPrefs.data.map { it[Keys.LAST_ADAPTER] }

    val lastScheduleUrl: Flow<String?> = context.jwPrefs.data.map { it[Keys.LAST_SCHEDULE_URL] }

    /** 进入导入页是否自动提取（默认开）。 */
    val autoExtract: Flow<Boolean> = context.jwPrefs.data.map { it[Keys.AUTO_EXTRACT] ?: true }

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
}
