package com.nullclass.sync

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val Context.syncDataStore by preferencesDataStore(name = "sync_settings")

/** 同步设置（WebDAV 凭证、设备 ID、同步状态）。凭证为 DataStore 明文，见 docs/impl 五。 */
@Singleton
class SyncSettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private object Keys {
        val URL = stringPreferencesKey("webdav_url")
        val USERNAME = stringPreferencesKey("webdav_username")
        val PASSWORD = stringPreferencesKey("webdav_password")
        val DEVICE_ID = stringPreferencesKey("device_id")
        val LAST_SYNC_AT = longPreferencesKey("last_sync_at")
        val LAST_REV = longPreferencesKey("last_rev")
    }

    val configFlow: Flow<WebDavConfig?> = context.syncDataStore.data.map { prefs ->
        val url = prefs[Keys.URL]
        if (url.isNullOrBlank()) {
            null
        } else {
            WebDavConfig(url, prefs[Keys.USERNAME].orEmpty(), prefs[Keys.PASSWORD].orEmpty())
        }
    }

    suspend fun getConfig(): WebDavConfig? = configFlow.first()

    suspend fun saveConfig(config: WebDavConfig) {
        context.syncDataStore.edit { prefs ->
            prefs[Keys.URL] = config.url.trim()
            prefs[Keys.USERNAME] = config.username.trim()
            prefs[Keys.PASSWORD] = config.password
        }
    }

    suspend fun clearConfig() {
        context.syncDataStore.edit { prefs ->
            prefs.remove(Keys.URL)
            prefs.remove(Keys.USERNAME)
            prefs.remove(Keys.PASSWORD)
        }
    }

    /** 设备唯一标识，首次访问时生成。 */
    suspend fun deviceId(): String {
        context.syncDataStore.data.first()[Keys.DEVICE_ID]?.let { return it }
        val id = UUID.randomUUID().toString()
        context.syncDataStore.edit { it[Keys.DEVICE_ID] = id }
        return id
    }

    val lastSyncAtFlow: Flow<Long?> = context.syncDataStore.data.map { it[Keys.LAST_SYNC_AT] }

    suspend fun setLastSync(at: Long, rev: Long) {
        context.syncDataStore.edit { prefs ->
            prefs[Keys.LAST_SYNC_AT] = at
            prefs[Keys.LAST_REV] = rev
        }
    }

    suspend fun getLastRev(): Long =
        context.syncDataStore.data.first()[Keys.LAST_REV] ?: 0L
}
