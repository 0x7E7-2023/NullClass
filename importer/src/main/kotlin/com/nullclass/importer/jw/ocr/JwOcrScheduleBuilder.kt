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

    /** 学期总周数的默认值（识别不出更长的周次时用它）。 */
    const val DEFAULT_TOTAL_WEEKS = 20

    /**
     * 从整张表的文本里推断学期总周数：取所有能解析出的周次里的最大结束周
     * （不低于 [fallback]，不高于 [maxWeeks]）。
     *
     * 为什么要推断：总周数给小了，`1-18周` 这种课会因为「超出总周数」被判为认不出周次，
     * 静默退回整学期。给大了只是课表尾部多几周空白，代价小得多。
     */
    fun inferTotalWeeks(
        table: AlignedTable,
        fallback: Int = DEFAULT_TOTAL_WEEKS,
        maxWeeks: Int = 30,
    ): Int {
        var best = fallback.coerceIn(1, maxWeeks)
        table.cells.forEach { row ->
            row.forEach { cell ->
                cell.split('\n').forEach { line ->
                    val spec = JwCourseTextParser.parseWeeks(line, maxWeeks) ?: return@forEach
                    if (spec.endWeek > best) best = spec.endWeek
                }
            }
        }
        return best
    }

    fun build(
        table: AlignedTable,
        termName: String,
        firstDayEpochDay: Long,
        totalWeeks: Int,
        /** 文本框来自 OCR 时置 true（导入预览会追加重核提示）；来自页面文本块时为 false。 */
        ocrAssisted: Boolean = true,
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
            ocrAssisted = ocrAssisted,
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
