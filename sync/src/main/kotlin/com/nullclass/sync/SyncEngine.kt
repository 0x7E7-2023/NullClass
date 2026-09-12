package com.nullclass.sync

import com.nullclass.core.model.DefaultTimetable
import com.nullclass.importer.BlockDto
import com.nullclass.importer.CourseDto
import com.nullclass.importer.ManifestDto
import com.nullclass.importer.PeriodTimeDto
import com.nullclass.importer.ScheduleDocument
import com.nullclass.importer.TermDto
import com.nullclass.importer.TimetableDto

/**
 * 同步合并引擎（纯函数，无 IO，可单测）。
 *
 * 合并规则：
 *  - 记录按 id 对齐；只存在一侧 → 采用
 *  - 两边都有 → updatedAt 新者胜（LWW）；相等 → 取本地（确定性）
 *  - 一侧为墓碑且不比另一侧旧 → 结果为墓碑（删除传播）
 *  - period_times 按 (termId, periodIndex) 对齐，同样 LWW
 *  - timetables 按 id 对齐，同样 LWW（删除传播同上）
 *  - 学期归属（timetableId）为空 = 旧版本写的：先沿用本地同 id 记录的归属，
 *    再不行落到 [activeTimetableId]；合并后**活学期必须挂在活课表上**，
 *    否则改挂到目标课表——数据在、界面上进不去等于静默丢数据
 *  - 合并后归一化 isCurrent，**按课表分组**：每张课表各留一个当前学期
 *    （全局只留一个的话，别的课表的当前学期会被清掉）
 */
object SyncEngine {

    fun merge(
        local: ScheduleDocument,
        remote: ScheduleDocument,
        now: Long,
        /** 本地当前课表 id（归属解析的落点；null = 本地还没有课表，如全新安装）。 */
        activeTimetableId: String? = null,
    ): ScheduleDocument {
        var timetables = mergeByKey(local.timetables, remote.timetables, key = { it.id }, updatedAt = { it.updatedAt })

        // 归属解析的目标课表：当前课表失效（如刚被对端删掉）时回落到创建最早的一张
        val liveTimetables = timetables.filter { it.deletedAt == null }
        var target = activeTimetableId?.takeIf { id -> liveTimetables.any { it.id == id } }
            ?: liveTimetables.minWithOrNull(compareBy({ it.createdAt }, { it.id }))?.id

        // 没有任何活课表却合并出了活学期（旧版本快照落进一个还没建过课表的库）：
        // 造一张默认课表兜住，否则这些学期无家可归
        if (target == null && (local.terms + remote.terms).any { it.deletedAt == null }) {
            timetables = timetables + TimetableDto(
                id = DefaultTimetable.ID, name = DefaultTimetable.NAME, createdAt = now, updatedAt = now,
            )
            target = DefaultTimetable.ID
        }

        // 旧版本写的学期（timetableId 为空）：先沿用本地同 id 记录的归属，再不行落目标课表
        val localById = local.terms.associateBy { it.id }
        val resolvedRemote = remote.terms.map { term ->
            when {
                term.timetableId.isNotEmpty() -> term
                else -> {
                    val inherited = localById[term.id]?.timetableId
                    val resolved = if (!inherited.isNullOrEmpty()) inherited else target
                    if (resolved != null && resolved.isNotEmpty()) term.copy(timetableId = resolved) else term
                }
            }
        }

        val mergedTerms = mergeByKey(local.terms, resolvedRemote, key = { it.id }, updatedAt = { it.updatedAt })
            .let { rehomeOrphans(it, liveIds = liveTimetables.map { it.id }.toSet(), target = target) }
            .let { normalizeCurrent(it, now) }

        return ScheduleDocument(
            formatVersion = maxOf(local.formatVersion, remote.formatVersion),
            deviceId = local.deviceId,
            generatedAt = now,
            timetables = timetables,
            terms = mergedTerms,
            courses = mergeByKey(local.courses, remote.courses, key = { it.id }, updatedAt = { it.updatedAt }),
            blocks = mergeByKey(local.blocks, remote.blocks, key = { it.id }, updatedAt = { it.updatedAt }),
            periodTimes = mergePeriodTimes(local, remote),
        )
    }

    /**
     * 活学期挂在不存在/已删的课表上 → 改挂目标课表（[target]）。
     * 墓碑学期保持原归属不动——它们本来就不可见，保留归属信息供后续合并比对。
     * 不 bump updatedAt：孤儿本身是异常态（正常的删除会连学期一起墓碑），
     * 伪造时间戳反而会顶掉并发端的合法编辑。
     */
    private fun rehomeOrphans(terms: List<TermDto>, liveIds: Set<String>, target: String?): List<TermDto> {
        if (target == null || terms.none { it.deletedAt == null && it.timetableId !in liveIds }) return terms
        return terms.map { term ->
            if (term.deletedAt == null && term.timetableId !in liveIds) {
                term.copy(timetableId = target)
            } else {
                term
            }
        }
    }

    /**
     * 节次表不逐行合并：每个学期的节次表整体随该学期 updatedAt 较新的一侧。
     * 否则编辑学期删掉的节次会被对端快照复活。
     */
    private fun mergePeriodTimes(local: ScheduleDocument, remote: ScheduleDocument): List<PeriodTimeDto> {
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
     * 归一化当前学期标记，**按课表分组**（每张课表各留一个）：
     *  - 同组多个 isCurrent=true → 保留 updatedAt 最新，其余清除
     *  - 同组无 isCurrent=true 且有未删学期 → 激活开学日最新的未删学期
     * 被改动的学期 bump updatedAt 为 [now]，保证标记收敛到其他设备。
     * 只改有变化的记录，且保持列表原有顺序。
     */
    private fun normalizeCurrent(terms: List<TermDto>, now: Long): List<TermDto> {
        // termId → (是否设为当前)。墓碑学期不参与（与单课表时代的语义一致）
        val toSetCurrent = HashSet<String>()
        val toClearCurrent = HashSet<String>()
        terms.groupBy { it.timetableId }.forEach { (_, group) ->
            val alive = group.filter { it.deletedAt == null }
            val current = alive.filter { it.isCurrent }
            when {
                current.size > 1 -> {
                    val keep = current.maxBy { it.updatedAt }
                    current.forEach { if (it.id != keep.id) toClearCurrent.add(it.id) }
                }

                current.isEmpty() && alive.isNotEmpty() -> {
                    alive.maxBy { it.firstDayEpochDay }.let { toSetCurrent.add(it.id) }
                }
            }
        }
        if (toSetCurrent.isEmpty() && toClearCurrent.isEmpty()) return terms
        return terms.map { term ->
            when (term.id) {
                in toSetCurrent -> term.copy(isCurrent = true, updatedAt = now)
                in toClearCurrent -> term.copy(isCurrent = false, updatedAt = now)
                else -> term
            }
        }
    }
}
