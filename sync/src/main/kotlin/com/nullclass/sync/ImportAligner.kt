package com.nullclass.sync

import com.nullclass.importer.BlockDto
import com.nullclass.importer.ImportProvenance
import com.nullclass.importer.ScheduleDocument

/**
 * 导入对齐：把「同名学期」认成同一个学期，避免每次重新导入都复制一份课表。
 *
 * **只对「每次导入都重铸 UUID」的来源**（[ImportProvenance.isFreshIdImport]：教务适配器、
 * WakeUp 迁移）生效。这类来源的记录 ID 每次都变，纯按 ID 做 LWW 会让「一键刷新」
 * 变成再追加一份；这里在合并**之前**把新数据对齐到本地已有记录上：
 *
 * - 学期按 `name` 对齐，**只在 ID 不同**（说明是新生成的一份）时复用本地 ID，内容取这次导入
 *   的（纠正过的开学日 / 总周数才写得进去）。名字匹配**限定在 [targetTimetableId] 这张课表内**
 *   ——教务/WakeUp 导入落在当前课表，别的课表里同名学期（不同人的）不该被认成同一个；
 * - **当前学期标记换成新导入的那个学期**（载荷里开学日最新的一个）：教务导入的语义是「开始
 *   用这份新学期的课表」，所以不论它是不是本地已有的同名学期（「一键刷新」同一学期、纠正
 *   开学日），还是新认领的学期，标记都落到这次导入的学期上；**只对教务适配器**（`jw-*`）
 *   —— WakeUp 迁移在 [TransferViewModel] 里按学期 ID 单独激活，走不到这里；
 * - 课程按 `(name, teacher)` 对齐，复用本地 ID，并保留用户改过的 `note` / `colorIndex`；
 * - 课块按内容（星期、节次、周次、类型、地点）对齐，内容没变就复用 ID；
 * - 对齐到的学期里，本地多出来的课程/课块**作废**（打墓碑）——学校改了时间或教室时，
 *   旧课块不会变成幽灵课；节次表的 `termId` 一并重映射（它随学期整体取新）。
 *
 * 其余来源（`.nullclass` 备份、扫码，deviceId = 导出设备）沿用记录原有 ID，
 * 原样返回走纯 LWW —— 导入一份旧备份不会把本地较新的记录作废，WebDAV 同步也不经过这里。
 */
object ImportAligner {

    data class Aligned(
        val local: ScheduleDocument,
        val incoming: ScheduleDocument,
    )

    fun align(
        local: ScheduleDocument,
        incoming: ScheduleDocument,
        now: Long,
        /** 教务/WakeUp 导入的目标课表（= 本地当前课表）；null = 本地还没有课表，无从对齐。 */
        targetTimetableId: String? = null,
    ): Aligned {
        // 只有重铸 ID 的导入才需要（也应该）做替换式对齐；备份/扫码保持纯 LWW
        if (!ImportProvenance.isFreshIdImport(incoming.deviceId)) return Aligned(local, incoming)

        // 教务导入 = 「开始用这份课表」，它带的那个学期（开学日最新者）合并后必须成为当前学期。
        // WakeUp 迁移同样重铸 ID，但它是单学期书包、由 UI 层按学期 ID 单独激活，这里不插手。
        // 先按**改名前**的 id 记下它——同名对齐会把 id 换成本地那份（见下面的 activationId）。
        val activateTermId = if (incoming.deviceId.startsWith(ImportProvenance.JW_PREFIX)) {
            incoming.terms.filter { it.deletedAt == null }.maxByOrNull { it.firstDayEpochDay }?.id
        } else {
            null
        }

        // 这类导入全部落目标课表（对齐到的沿用本地归属，新学期也指过去）
        val scoped = if (targetTimetableId != null) {
            incoming.copy(terms = incoming.terms.map { it.copy(timetableId = targetTimetableId) })
        } else {
            incoming
        }

        val localByName = local.terms
            .filter { it.deletedAt == null && targetTimetableId != null && it.timetableId == targetTimetableId }
            .associateBy { it.name }
        if (localByName.isEmpty()) {
            return activate(local, scoped, activateTermId, now)
        }

        // 1) 学期：同名且 ID 不同 → 复用本地 ID
        val termIdMap = HashMap<String, String>()
        val alignedTerms = scoped.terms.map { term ->
            val match = localByName[term.name]?.takeIf { term.deletedAt == null && it.id != term.id }
            if (match == null) {
                term
            } else {
                termIdMap[term.id] = match.id
                term.copy(id = match.id, createdAt = match.createdAt, isCurrent = match.isCurrent)
            }
        }
        if (termIdMap.isEmpty()) {
            return activate(local, scoped, activateTermId, now)
        }
        // 这次导入的学期被同名对齐换成了本地那份：激活要认对齐后的 id
        val activationId = termIdMap[activateTermId] ?: activateTermId

        // 2) 课程：只在被对齐的学期里按 (name, teacher) 复用本地 ID
        val localCourses = local.courses.filter { it.deletedAt == null }
        val courseIdMap = HashMap<String, String>()
        val alignedCourses = scoped.courses.map { course ->
            val termId = termIdMap[course.termId] ?: course.termId
            val match = if (course.deletedAt == null && termIdMap.containsKey(course.termId)) {
                localCourses.firstOrNull {
                    it.termId == termId && it.name == course.name && it.teacher == course.teacher
                }
            } else {
                null
            }
            if (match == null) {
                course.copy(termId = termId)
            } else {
                courseIdMap[course.id] = match.id
                course.copy(
                    id = match.id,
                    termId = termId,
                    createdAt = match.createdAt,
                    note = match.note,
                    colorIndex = match.colorIndex,
                )
            }
        }

        // 3) 课块：内容一致就复用本地 ID
        val localBlocks = local.blocks.filter { it.deletedAt == null }
        val localBlocksByCourse = localBlocks.groupBy { it.courseId }
        val alignedBlocks = scoped.blocks.map { block ->
            val courseId = courseIdMap[block.courseId] ?: block.courseId
            val termId = termIdMap[block.termId] ?: block.termId
            val match = if (block.deletedAt == null && courseIdMap.containsKey(block.courseId)) {
                localBlocksByCourse[courseId].orEmpty().firstOrNull { sameContent(it, block) }
            } else {
                null
            }
            if (match == null) {
                block.copy(courseId = courseId, termId = termId)
            } else {
                block.copy(id = match.id, courseId = courseId, termId = termId, createdAt = match.createdAt)
            }
        }

        // 考试跟随课程 ID：教务/WakeUp 每次会重铸课程 UUID，考试不能因此脱离原课程。
        val alignedExams = scoped.exams.map { exam ->
            exam.copy(courseId = courseIdMap[exam.courseId] ?: exam.courseId)
        }

        // 节次表随学期走：termId 必须跟着重映射，否则 mergePeriodTimes 在新 ID 下取不到
        // 对应节次、又被「学期 updatedAt 较新」判给 remote，结果是整表被清空
        val alignedPeriodTimes = scoped.periodTimes.map { period ->
            val termId = termIdMap[period.termId]
            if (termId == null) period else period.copy(termId = termId)
        }

        // 4) 作废：对齐到的学期里，本地有而新数据没覆盖到的课程与课块
        val alignedTermIds = termIdMap.values.toSet()
        val keptCourseIds = alignedCourses.map { it.id }.toSet()
        val keptBlockIds = alignedBlocks.map { it.id }.toSet()
        val droppedCourseIds = localCourses
            .filter { it.termId in alignedTermIds && it.id !in keptCourseIds }
            .map { it.id }
            .toSet()
        val droppedBlockIds = localBlocks
            .filter { it.termId in alignedTermIds && it.id !in keptBlockIds }
            .map { it.id }
            .toSet()

        val alignedLocal = if (droppedCourseIds.isEmpty() && droppedBlockIds.isEmpty()) {
            local
        } else {
            local.copy(
                courses = local.courses.map {
                    if (it.id in droppedCourseIds) it.copy(deletedAt = now, updatedAt = now) else it
                },
                blocks = local.blocks.map {
                    if (it.id in droppedBlockIds) it.copy(deletedAt = now, updatedAt = now) else it
                },
                // 课程被教务刷新作废时，挂在这门课上的考试也不能在同步后变成孤儿。
                exams = local.exams.map {
                    if (it.courseId in droppedCourseIds && it.deletedAt == null) {
                        it.copy(deletedAt = now, updatedAt = now)
                    } else {
                        it
                    }
                },
            )
        }

        return activate(
            local = alignedLocal,
            incoming = scoped.copy(
                terms = alignedTerms,
                courses = alignedCourses,
                blocks = alignedBlocks,
                exams = alignedExams,
                periodTimes = alignedPeriodTimes,
            ),
            activationId = activationId,
            now = now,
        )
    }

    /**
     * 把这次导入的学期标成当前学期（[activationId]；null = 不动标记）。
     *
     * 两边都改，但各自只做一件最小的事：
     *  - incoming 侧：该学期标成当前、`updatedAt` 推到 [now]——它因此赢下 LWW，**内容也一并
     *    取自这次导入**（「一键刷新」纠正的开学日 / 总周数才写得进去）；并发导入时这也是
     *    「谁后导入谁作数」的确定性裁决；
     *  - local 侧：同一张课表里**别的**当前学期让位（清标记 + bump 时间戳）。不让位的话合并
     *    结果里会有两个 `isCurrent`，归一化按 `updatedAt` 挑「最后动过的一个」，而那正是被
     *    清掉的那个，标记会反着落回旧学期。
     *
     * [activationId] 在两边是同一个值（同名对齐时它已经是本地那份的 id），所以 local 侧那份
     * **一个字节都不许动**：`SyncEngine.mergeByKey` 相等取本地，一旦 bump 了本地时间戳，
     * 导入的内容就永远进不来。
     */
    private fun activate(
        local: ScheduleDocument,
        incoming: ScheduleDocument,
        activationId: String?,
        now: Long,
    ): Aligned {
        if (activationId == null) return Aligned(local, incoming)

        val localActive = local.terms.firstOrNull { it.id == activationId }
        val incomingActive = incoming.terms.firstOrNull { it.id == activationId }
        // 名字对齐时该学期在 local 侧不存在（本地的同名学期另有 id），只能从 incoming 取归属
        val scope = (localActive ?: incomingActive)?.timetableId.orEmpty()
        val localTerms = local.terms.map { term ->
            if (term.isCurrent && term.deletedAt == null && term.timetableId == scope &&
                term.id != activationId
            ) {
                term.copy(isCurrent = false, updatedAt = now)
            } else {
                term
            }
        }
        val incomingTerms = incoming.terms.map { term ->
            if (term.id == activationId) {
                term.copy(isCurrent = true, updatedAt = now)
            } else if (term.isCurrent && term.timetableId == scope) {
                term.copy(isCurrent = false, updatedAt = now) // 见 KDoc：这张课表只留一个当前
            } else {
                term
            }
        }

        return Aligned(
            local = local.copy(terms = localTerms),
            incoming = incoming.copy(terms = incomingTerms),
        )
    }

    private fun sameContent(a: BlockDto, b: BlockDto): Boolean =
        a.dayOfWeek == b.dayOfWeek &&
            a.startPeriod == b.startPeriod &&
            a.endPeriod == b.endPeriod &&
            a.startWeek == b.startWeek &&
            a.endWeek == b.endWeek &&
            a.weekType == b.weekType &&
            a.location == b.location
}
