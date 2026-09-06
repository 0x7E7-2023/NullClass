package com.nullclass.sync

/**
 * 同步合并引擎（纯函数，无 IO，可单测）。
 *
 * 合并规则（详见 docs/impl 2.3）：
 *  - 记录按 id 对齐；只存在一侧 → 采用
 *  - 两边都有 → updatedAt 新者胜（LWW）；相等 → 取本地（确定性）
 *  - 一侧为墓碑且不比另一侧旧 → 结果为墓碑（删除传播）
 *  - period_times 按 (termId, periodIndex) 对齐，同样 LWW
 *  - 合并后归一化 isCurrent：多者取 updatedAt 最新；无者激活开学日最新
 */
object SyncEngine {

    fun merge(local: SnapshotDto, remote: SnapshotDto, now: Long): SnapshotDto = SnapshotDto(
        formatVersion = maxOf(local.formatVersion, remote.formatVersion),
        deviceId = local.deviceId,
        generatedAt = now,
        terms = mergeByKey(local.terms, remote.terms, key = { it.id }, updatedAt = { it.updatedAt })
            .let { normalizeCurrent(it, now) },
        courses = mergeByKey(local.courses, remote.courses, key = { it.id }, updatedAt = { it.updatedAt }),
        blocks = mergeByKey(local.blocks, remote.blocks, key = { it.id }, updatedAt = { it.updatedAt }),
        periodTimes = mergePeriodTimes(local, remote),
    )

    /**
     * 节次表不逐行合并：每个学期的节次表整体随该学期 updatedAt 较新的一侧（docs/impl 2.3）。
     * 否则编辑学期删掉的节次会被对端快照复活。
     */
    private fun mergePeriodTimes(local: SnapshotDto, remote: SnapshotDto): List<PeriodTimeDto> {
        val localTermStamp = local.terms.associate { it.id to it.updatedAt }
        val remoteTermStamp = remote.terms.associate { it.id to it.updatedAt }
        val localByTerm = local.periodTimes.groupBy { it.termId }
        val remoteByTerm = remote.periodTimes.groupBy { it.termId }

        return (localTermStamp.keys + remoteTermStamp.keys).flatMap { termId ->
            val localStamp = localTermStamp[termId]
            val remoteStamp = remoteTermStamp[termId]
            when {
                localStamp == null -> remoteByTerm[termId].orEmpty()
                remoteStamp == null -> localByTerm[termId].orEmpty()
                remoteStamp > localStamp -> remoteByTerm[termId].orEmpty()
                else -> localByTerm[termId].orEmpty() // 相等取本地
            }
        }
    }

    /** 通用 LWW 合并：相等取本地。 */
    private fun <T, K> mergeByKey(
        local: List<T>,
        remote: List<T>,
        key: (T) -> K,
        updatedAt: (T) -> Long,
    ): List<T> {
        val result = LinkedHashMap<K, T>()
        local.forEach { result[key(it)] = it }
        remote.forEach { record ->
            val existing = result[key(record)]
            if (existing == null || updatedAt(record) > updatedAt(existing)) {
                result[key(record)] = record
            }
        }
        return result.values.toList()
    }

    /**
     * 归一化当前学期标记：
     *  - 多个 isCurrent=true → 保留 updatedAt 最新，其余清除
     *  - 无 isCurrent=true → 激活开学日最新的未删学期
     * 被改动的学期 bump updatedAt 为 [now]，保证标记收敛到其他设备。
     */
    private fun normalizeCurrent(terms: List<TermDto>, now: Long): List<TermDto> {
        val alive = terms.filter { it.deletedAt == null }
        val current = alive.filter { it.isCurrent }

        return when {
            current.size > 1 -> {
                val keep = current.maxBy { it.updatedAt }
                terms.map {
                    when {
                        it.id == keep.id -> it
                        it.isCurrent -> it.copy(isCurrent = false, updatedAt = now)
                        else -> it
                    }
                }
            }

            current.isEmpty() && alive.isNotEmpty() -> {
                val newest = alive.maxBy { it.firstDayEpochDay }
                terms.map {
                    if (it.id == newest.id) {
                        it.copy(isCurrent = true, updatedAt = now)
                    } else {
                        it
                    }
                }
            }

            else -> terms
        }
    }
}
