package com.nullclass.importer.jw.ocr

import com.nullclass.importer.jw.JwBlock
import com.nullclass.importer.jw.JwCourse
import com.nullclass.importer.jw.JwSchedulePayload
import com.nullclass.importer.jw.JwTerm

/**
 * 对齐后的网格 → 课表载荷。
 *
 * 只做「保守」解析：周次认不出就退回整学期并**记录 issue**（校对页必须让用户看见并确认），
 * 绝不静默猜测。
 */
data class JwOcrBuildResult(
    val payload: JwSchedulePayload,
    val issues: List<String>,
)

object JwOcrScheduleBuilder {

    fun build(
        table: AlignedTable,
        termName: String,
        firstDayEpochDay: Long,
        totalWeeks: Int,
    ): JwOcrBuildResult {
        val issues = mutableListOf<String>()
        val byName = LinkedHashMap<String, MutableList<JwBlock>>()
        val teachers = mutableMapOf<String, String>()

        table.cells.forEachIndexed { rowIndex, row ->
            row.forEachIndexed { colIndex, raw ->
                val text = raw.trim()
                if (text.isEmpty()) return@forEachIndexed
                val lines = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
                if (lines.isEmpty()) return@forEachIndexed

                val startPeriod = table.rowPeriods.getOrNull(rowIndex)
                val endPeriod = table.rowEndPeriods.getOrNull(rowIndex) ?: startPeriod
                val day = table.colDays.getOrNull(colIndex)
                if (startPeriod == null || day == null) {
                    issues += "第 ${rowIndex + 1} 行第 ${colIndex + 1} 列：网格坐标缺失"
                    return@forEachIndexed
                }

                val weekSpec = lines.firstNotNullOfOrNull { JwCourseTextParser.parseWeeks(it, totalWeeks) }
                // 课程名 = 第一条既不是周次、也不像教室的文本（教务课表格子里课名通常在第一行）
                val nameIndex = lines.indexOfFirst { candidate ->
                    JwCourseTextParser.parseWeeks(candidate, totalWeeks) == null &&
                        !JwCourseTextParser.looksLikeLocation(candidate)
                }
                val name = if (nameIndex >= 0) lines[nameIndex] else null
                if (name.isNullOrBlank()) {
                    issues += "第 ${rowIndex + 1} 行第 ${colIndex + 1} 列：识别不出课程名（原文「${text.replace('\n', ' ')}」）"
                    return@forEachIndexed
                }
                val rest = lines.filterIndexed { index, _ -> index != nameIndex }
                val location = rest.firstOrNull { JwCourseTextParser.looksLikeLocation(it) }
                val teacher = rest.firstOrNull { candidate ->
                    candidate != location && JwCourseTextParser.looksLikeTeacher(candidate)
                }

                if (weekSpec == null) {
                    issues += "第 ${rowIndex + 1} 行第 ${colIndex + 1} 列「$name」：没识别出周次，已按整学期处理，请核对"
                }

                byName.getOrPut(name) { mutableListOf() } += JwBlock(
                    dayOfWeek = day,
                    startPeriod = startPeriod,
                    endPeriod = endPeriod ?: startPeriod,
                    startWeek = weekSpec?.startWeek ?: 1,
                    endWeek = weekSpec?.endWeek ?: totalWeeks,
                    weekType = weekSpec?.weekType ?: "ALL",
                    location = location,
                )
                if (teacher != null) teachers.putIfAbsent(name, teacher)
            }
        }

        if (byName.isEmpty()) {
            issues += "整张表没有识别出任何课程"
        }

        val courses = byName.map { (name, blocks) ->
            JwCourse(
                name = name,
                teacher = teachers[name],
                note = null,
                blocks = blocks.sortedWith(compareBy({ it.dayOfWeek }, { it.startPeriod })),
            )
        }

        val payload = JwSchedulePayload(
            kind = JwSchedulePayload.KIND_SCHEDULE,
            ocrAssisted = true,
            terms = listOf(
                JwTerm(
                    name = termName,
                    firstDayEpochDay = firstDayEpochDay,
                    totalWeeks = totalWeeks,
                    courses = courses,
                ),
            ),
        )
        return JwOcrBuildResult(payload, issues)
    }
}
