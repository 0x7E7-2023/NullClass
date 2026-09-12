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

    /** 网格里的一行文字，附带它落在哪一行（行锚点由节次列给出）。 */
    private data class CellLine(val row: Int, val text: String)

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
                    JwCourseTextParser.parseWeekSpecs(line, maxWeeks).forEach { spec ->
                        if (spec.endWeek > best) best = spec.endWeek
                    }
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
        /**
         * 适配器自己要用户核对的说明，原样带进重建后的载荷。
         *
         * 这一步是重建载荷，**不显式带就是丢**：适配器说的「开学日期是推算的」到不了用户眼前，
         * 而推算出来的日期跟真的一样会用（今日页/提醒/小组件/周视图全按它算）。
         */
        warnings: List<String> = emptyList(),
    ): JwOcrBuildResult {
        // 结构层要求用户核对的话（表头顺序异常、节次号是推断的……）一并带到校对页上，
        // 不然它们只留在 warnings 里，用户根本看不到。
        val issues = table.warnings.filter { "核对" in it }.toMutableList()
        val byName = LinkedHashMap<String, MutableList<JwBlock>>()
        val teachers = mutableMapOf<String, String>()

        // 一段文字怎么切成「一门课」：
        //
        // 有周次行时**按列**拼起来切段，而不是按格子分组：课表格子跨多节是常态（连堂），格子里的
        // 文字是靠顶端排的，课名可能落在上一行锚点、教师与周次落在下一行——按格子分组会把同一门课
        // 切碎；金智那类页面还会把几门周次错开的课并排画在同一个格子里，按格子分组又会把几门课糊成
        // 一门。列是可靠的（格子里的文字在列内居中），行优先取格子里写明的「1-2节」，取不到才退回行锚点。
        //
        // 整页格子里一个「周」字都没有时（「本周课表」那类视图）没什么可切的，退回按格子分组：
        // 每格一门课，跟 OCR 那条路一直以来的做法一致。
        val weekAnchored = table.cells.any { row ->
            row.any { cell -> cell.split('\n').any { isWeekLine(it, totalWeeks) } }
        }
        if (!weekAnchored) issues += "格子里没有周次信息，已逐格还原课表，请核对"

        table.colDays.forEachIndexed { colIndex, columnDay ->
            val entries: List<List<CellLine>> = if (weekAnchored) {
                splitEntries(columnLines(table, colIndex), totalWeeks)
            } else {
                table.cells.indices.mapNotNull { rowIndex ->
                    cellLines(table, rowIndex, colIndex).takeIf { it.isNotEmpty() }
                }
            }
            if (entries.isEmpty()) return@forEachIndexed
            var lastName: String? = null

            entries.forEach { entry ->
                val first = entry.first()
                val nameLine = entry.firstOrNull { isNameCandidate(it.text, totalWeeks) }
                val name = JwCourseTextParser.cleanCourseName(nameLine?.text)
                val location = entry.firstNotNullOfOrNull { JwCourseTextParser.locationInLine(it.text) }
                // 星期优先信格子里写的：一格横跨几个星期列时，列会指错日子
                val day = entry.firstNotNullOfOrNull { JwCourseTextParser.parseDayInLine(it.text) } ?: columnDay
                val ranges = entry.associateWith { rowRange(table, it.row) }
                val parsed = entry.flatMap { blocksOf(it, ranges[it], day, totalWeeks, location) }.distinct()

                if (name.isBlank()) {
                    // 没有课名的小段：并进上一门课（一门课写了好几个时间段的写法）；上一门也没有就只能跳过。
                    // 逐格分组时不做这个接续——那是跨格子猜，不如老实说这一格没认出课名。
                    val host = lastName.takeIf { weekAnchored }
                    if (host == null) {
                        if (parsed.isNotEmpty()) {
                            issues += "第 ${colIndex + 1} 列：有 ${entry.size} 行文字没归到任何课程上，已跳过"
                        }
                        return@forEach
                    }
                    addBlocks(byName, host, parsed)
                    return@forEach
                }

                // 教师跟课块走：兜底那条路（周次认不出）同样要记下来
                entry.firstOrNull {
                    it.text != nameLine?.text && it.text != location &&
                        JwCourseTextParser.looksLikeTeacher(it.text)
                }?.let { teachers.putIfAbsent(name, it.text) }

                // 页面会把跨多节的课在相邻几个格子里各画一遍（同一段文字出现两次），同一个课块只留一份
                val fresh = freshBlocks(byName, name, parsed)
                if (fresh.isNotEmpty()) {
                    addBlocks(byName, name, fresh)
                    lastName = name
                    return@forEach
                }
                if (parsed.isNotEmpty()) return@forEach // 全是画重的那一份

                // 一行周次都没认出来：按整学期兜底，并让用户核对
                val range = rowRange(table, first.row)
                if (range == null) {
                    issues += "第 ${first.row + 1} 行第 ${colIndex + 1} 列：网格坐标缺失"
                    return@forEach
                }
                val fallback = JwBlock(day, range.first, range.second, 1, totalWeeks, "ALL", location)
                if (freshBlocks(byName, name, listOf(fallback)).isEmpty()) return@forEach
                issues += "第 ${first.row + 1} 行第 ${colIndex + 1} 列「$name」：没识别出周次，已按整学期处理，请核对"
                addBlocks(byName, name, listOf(fallback))
                lastName = name
            }
        }

        val named = byName
        if (named.isEmpty()) {
            issues += "整张表没有识别出任何课程"
        }

        val courses = named.map { (name, blocks) ->
            JwCourse(
                name = name,
                teacher = teachers[name],
                note = null,
                blocks = blocks.sortedWith(compareBy({ it.dayOfWeek }, { it.startPeriod }, { it.startWeek })),
            )
        }

        val payload = JwSchedulePayload(
            kind = JwSchedulePayload.KIND_SCHEDULE,
            ocrAssisted = ocrAssisted,
            warnings = warnings,
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

    /** 已经收过的课块不再收第二遍（页面会把一门课画在好几个格子里）。 */
    private fun freshBlocks(
        collected: Map<String, MutableList<JwBlock>>,
        name: String,
        blocks: List<JwBlock>,
    ): List<JwBlock> {
        val existing = collected[name] ?: return blocks
        return blocks.filterNot { it in existing }
    }

    private fun addBlocks(
        collected: MutableMap<String, MutableList<JwBlock>>,
        name: String,
        blocks: List<JwBlock>,
    ) {
        if (blocks.isEmpty()) return
        collected.getOrPut(name) { mutableListOf() } += blocks
    }

    /** 某一格里的文本行。 */
    private fun cellLines(table: AlignedTable, rowIndex: Int, colIndex: Int): List<CellLine> =
        table.cells.getOrNull(rowIndex)?.getOrNull(colIndex)
            ?.split('\n')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.map { CellLine(rowIndex, it) }
            .orEmpty()

    /** 一列（星期）里的所有文本行，按行序、行内自上而下。 */
    private fun columnLines(table: AlignedTable, colIndex: Int): List<CellLine> =
        table.cells.indices.flatMap { rowIndex -> cellLines(table, rowIndex, colIndex) }

    /**
     * 把一列文字切成若干「课名 / 教师 / 周次 / 教室」小段：上一段已经收尾（有周次行）时，
     * 后面第一条**课名样**的文字就开新的一段。
     *
     * 「课名样」要排掉教室行：教室写在周次后面的页面很常见（「课名/周次/教1-101」），
     * 拿它当新段的开头，整条教室行会被切出去，课块就丢了教室。
     */
    private fun splitEntries(lines: List<CellLine>, totalWeeks: Int): List<List<CellLine>> {
        val entries = mutableListOf<MutableList<CellLine>>()
        lines.forEach { line ->
            val current = entries.lastOrNull()
            val closed = current != null && current.any { isWeekLine(it.text, totalWeeks) }
            if (current == null || (closed && isNameCandidate(line.text, totalWeeks))) {
                entries += mutableListOf(line)
            } else {
                current += line
            }
        }
        return entries
    }

    /** 一行文字 → 若干课块（一行里可以写好几个不连续的周次段）。 */
    private fun blocksOf(
        line: CellLine,
        fallbackRange: Pair<Int, Int>?,
        day: Int,
        totalWeeks: Int,
        entryLocation: String?,
    ): List<JwBlock> {
        val specs = JwCourseTextParser.parseWeekSpecs(line.text, totalWeeks)
        if (specs.isEmpty()) return emptyList()
        // 格子写明了节次就用它：跨多节的课，行锚点只知道它从哪一行开始
        val range = JwCourseTextParser.parsePeriodsInLine(line.text) ?: fallbackRange ?: return emptyList()
        val location = JwCourseTextParser.locationInLine(line.text) ?: entryLocation
        return specs.map { spec ->
            JwBlock(
                dayOfWeek = day,
                startPeriod = range.first,
                endPeriod = range.second,
                startWeek = spec.startWeek,
                endWeek = spec.endWeek,
                weekType = spec.weekType,
                location = location,
            )
        }
    }

    private fun rowRange(table: AlignedTable, rowIndex: Int): Pair<Int, Int>? {
        val start = table.rowPeriods.getOrNull(rowIndex) ?: return null
        return start to (table.rowEndPeriods.getOrNull(rowIndex) ?: start)
    }

    private fun isWeekLine(text: String, totalWeeks: Int): Boolean =
        JwCourseTextParser.parseWeekSpecs(text, totalWeeks).isNotEmpty()

    /**
     * 课名候选：既不是周次行、也不像教室。
     *
     * 判教室之前先剥掉课号与教学班装饰——「6120011 职业生涯与发展规划[03S01]」里的 "S01"
     * 会被教室规则匹配上，不剥的话整条课名行会被当成教室，课名就落到教师名上去了。
     */
    private fun isNameCandidate(text: String, totalWeeks: Int): Boolean {
        if (isWeekLine(text, totalWeeks)) return false
        val cleaned = JwCourseTextParser.cleanCourseName(text)
        return cleaned.isNotEmpty() && !JwCourseTextParser.looksLikeLocation(cleaned)
    }
}
