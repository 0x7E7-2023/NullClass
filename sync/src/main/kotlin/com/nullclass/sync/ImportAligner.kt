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
 * - 学期按 `name` 对齐，**只在 ID 不同**（说明是新生成的一份）时复用本地 ID，并沿用本地
 *   的 `isCurrent`，免得刷新把当前学期换掉；
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

    fun align(local: ScheduleDocument, incoming: ScheduleDocument, now: Long): Aligned {
        // 只有重铸 ID 的导入才需要（也应该）做替换式对齐；备份/扫码保持纯 LWW
        if (!ImportProvenance.isFreshIdImport(incoming.deviceId)) return Aligned(local, incoming)

        val localByName = local.terms.filter { it.deletedAt == null }.associateBy { it.name }
        if (localByName.isEmpty()) return Aligned(local, incoming)

        // 1) 学期：同名且 ID 不同 → 复用本地 ID
        val termIdMap = HashMap<String, String>()
        val alignedTerms = incoming.terms.map { term ->
            val match = localByName[term.name]?.takeIf { term.deletedAt == null && it.id != term.id }
            if (match == null) {
                term
            } else {
                termIdMap[term.id] = match.id
                term.copy(id = match.id, createdAt = match.createdAt, isCurrent = match.isCurrent)
            }
        }
        if (termIdMap.isEmpty()) return Aligned(local, incoming)

        // 2) 课程：只在被对齐的学期里按 (name, teacher) 复用本地 ID
        val localCourses = local.courses.filter { it.deletedAt == null }
        val courseIdMap = HashMap<String, String>()
        val alignedCourses = incoming.courses.map { course ->
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
        val alignedBlocks = incoming.blocks.map { block ->
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

        // 节次表随学期走：termId 必须跟着重映射，否则 mergePeriodTimes 在新 ID 下取不到
        // 对应节次、又被「学期 updatedAt 较新」判给 remote，结果是整表被清空
        val alignedPeriodTimes = incoming.periodTimes.map { period ->
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
            )
        }

        return Aligned(
            local = alignedLocal,
            incoming = incoming.copy(
                terms = alignedTerms,
                courses = alignedCourses,
                blocks = alignedBlocks,
                periodTimes = alignedPeriodTimes,
            ),
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
