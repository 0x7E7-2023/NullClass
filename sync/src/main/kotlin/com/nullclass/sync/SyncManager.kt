package com.nullclass.sync

import com.nullclass.importer.ScheduleDocument
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** 同步结果（设置页展示用）。 */
sealed interface SyncResult {
    data class Success(
        /** 合并后从远端采纳的记录数（新增+更新）。 */
        val adoptedFromRemote: Int,
        val rev: Long,
    ) : SyncResult

    data class NotConfigured(val message: String = "未配置 WebDAV") : SyncResult
    data class Error(val message: String) : SyncResult
}

/**
 * 同步管理器：Pull → Merge → Apply → Push（手动/自动触发，互斥防重入）。
 * 本地库读写委托 [SnapshotCodec]（与导入导出共用同一套语义）。
 */
@Singleton
class SyncManager @Inject constructor(
    private val settings: SyncSettingsRepository,
    private val codec: SnapshotCodec,
) {

    private val mutex = Mutex()

    suspend fun sync(): SyncResult = mutex.withLock {
        val config = settings.getConfig()
            ?: return SyncResult.NotConfigured()
        config.validate()?.let { return SyncResult.Error(it) }

        val client = WebDavClient(config)
        return try {
            val now = System.currentTimeMillis()
            val local = codec.dump(deviceId = null, nowMillis = now)

            val downloaded = client.download()
            val merged: ScheduleDocument
            val adopted: Int
            if (downloaded == null) {
                // 远端为空：首次同步，直接上传本地
                merged = local
                adopted = 0
            } else {
                merged = SyncEngine.merge(local, downloaded.snapshot, now)
                adopted = codec.countAdopted(local, merged)
                codec.apply(merged)
            }

            val previousRev = if (downloaded == null) settings.getLastRev() else downloaded.manifest.rev
            client.upload(merged, previousRev)
            settings.setLastSync(now, previousRev + 1)

            SyncResult.Success(adoptedFromRemote = adopted, rev = previousRev + 1)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 协程取消必须重抛，不能伪装成同步失败（B4）
            throw e
        } catch (e: Exception) {
            SyncResult.Error(e.message ?: e.javaClass.simpleName)
        }
    }

    suspend fun testConnection(): WebDavResult {
        val config = settings.getConfig() ?: return WebDavResult.Error("未配置 WebDAV")
        config.validate()?.let { return WebDavResult.Error(it) }
        return WebDavClient(config).testConnection()
    }
}
