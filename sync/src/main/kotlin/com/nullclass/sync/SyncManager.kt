package com.nullclass.sync

import com.nullclass.core.data.repository.TimetableRepository
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

/** 导入合并结果（confirmMerge / UI 消息用）。 */
data class MergeImportResult(
    val merged: ScheduleDocument,
    val adopted: Int,
    /** 本次导入**新增**的课表（本地没有的 id）：导入同学的备份会多出一张课表，UI 要指明去哪了。 */
    val newTimetableNames: List<String> = emptyList(),
)

/**
 * 同步管理器：Pull → Merge → Apply → Push（手动/自动触发，互斥防重入）。
 * 本地库读写委托 [SnapshotCodec]（与导入导出共用同一套语义）。
 */
@Singleton
class SyncManager @Inject constructor(
    private val settings: SyncSettingsRepository,
    private val codec: SnapshotCodec,
    private val timetableRepository: TimetableRepository,
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
                // activeTimetableId：旧版本写的快照里学期没有课表归属，合并时落到当前课表
                merged = SyncEngine.merge(local, downloaded.snapshot, now, timetableRepository.getActiveId())
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

    /**
     * 导入合并（B9）：与同步同一把锁串行化。
     * 用户确认导入与后台 SyncWorker 各自做全库 dump→merge→apply 读改写，
     * 不加锁交错时 periodTimes 的 deleteAll+insert 会把对方刚写入的学期节次表
     * 整体清掉，且 isCurrent 归一化基于各自的过期 dump —— 谁后写谁生效。
     */
    suspend fun mergeImport(document: ScheduleDocument): MergeImportResult = mutex.withLock {
        val now = System.currentTimeMillis()
        val local = codec.dump(deviceId = null, nowMillis = now)
        // 适配器/WakeUp 每次导入都生成全新 UUID，纯按 ID 合并会让「一键刷新」复制一份课表；
        // 先按名字对齐到本地记录（详见 ImportAligner——名字匹配限定在当前课表内），再走常规 LWW。
        val activeTimetableId = timetableRepository.getActiveId()
        val aligned = ImportAligner.align(local, document, now, activeTimetableId)
        val merged = SyncEngine.merge(aligned.local, aligned.incoming, now, activeTimetableId)
        val adopted = codec.countAdopted(aligned.local, merged)
        codec.apply(merged)
        val localLiveIds = local.timetables.filter { it.deletedAt == null }.map { it.id }.toSet()
        MergeImportResult(
            merged = merged,
            adopted = adopted,
            newTimetableNames = merged.timetables
                .filter { it.deletedAt == null && it.id !in localLiveIds }
                .map { it.name },
        )
    }

    suspend fun testConnection(): WebDavResult {
        val config = settings.getConfig() ?: return WebDavResult.Error("未配置 WebDAV")
        config.validate()?.let { return WebDavResult.Error(it) }
        return WebDavClient(config).testConnection()
    }
}
