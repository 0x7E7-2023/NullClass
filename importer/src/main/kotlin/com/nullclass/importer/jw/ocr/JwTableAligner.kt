package com.nullclass.importer.jw.ocr

/**
 * 把 OCR 文本框吸附到「7 列（星期）× N 行（节次）」的语义网格。
 *
 * 为什么不用通用表格结构模型：课表的行列语义是固定的（表头是星期、首列是节次），
 * 用坐标聚类 + 表头锚定比通用 TSR 模型更轻也更准（SLANet 在中文真实表格上自评仅 59.5%）。
 *
 * **可靠性优先**：表头认不出、节次列认不出、或大量文本框落不进网格时，
 * `reliable=false` —— 上层必须放弃自动生成课表，改走人工校对/手动录入。
 */
data class AlignedTable(
    val rowAnchors: List<Int>,
    /** 与 [rowAnchors] 一一对应的起始节次。 */
    val rowPeriods: List<Int>,
    /** 与 [rowAnchors] 一一对应的结束节次（合并节次标注 `3-4` 时 > [rowPeriods]）。 */
    val rowEndPeriods: List<Int>,
    val colAnchors: List<Int>,
    /** 与 [colAnchors] 一一对应的星期（1..7）。 */
    val colDays: List<Int>,
    /** `cells[row][col]`，单元格内文本按原始顺序用换行拼接。 */
    val cells: List<List<String>>,
    val reliable: Boolean,
    val warnings: List<String>,
    val unassigned: List<OcrBox> = emptyList(),
) {
    companion object {
        fun unreliable(warnings: List<String>): AlignedTable = AlignedTable(
            emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), false, warnings,
        )
    }
}

object JwTableAligner {

    /** 落不进网格的文本框占比超过这个值就判定不可靠。 */
    private const val MAX_UNASSIGNED_RATIO = 0.2

    private const val MIN_DAY_COLUMNS = 5
    private const val MAX_DAY_COLUMNS = 7
    private const val MIN_PERIOD_ROWS = 4

    fun align(page: OcrPage, expectedRows: Int? = null, expectedCols: Int? = null): AlignedTable {
        val boxes = page.boxes.filter { it.text.isNotBlank() }
        if (boxes.isEmpty()) return AlignedTable.unreliable(listOf("图片里没有识别到任何文字"))

        val medianHeight = boxes.map { it.height }.filter { it > 0 }.sorted().let { heights ->
            if (heights.isEmpty()) 20 else heights[heights.size / 2]
        }.coerceAtLeast(6)

        val rowGroups = groupIntoRows(boxes, medianHeight)

        val headerGroup = rowGroups.firstOrNull { group ->
            group.count { JwCourseTextParser.parseDayOfWeek(it.text) != null } >= 3
        } ?: return AlignedTable.unreliable(listOf("没有找到星期表头行，无法确定课表列"))

        val headerBoxes = headerGroup
            .filter { JwCourseTextParser.parseDayOfWeek(it.text) != null }
            .sortedBy { it.centerX }
        val colAnchors = mutableListOf<Int>()
        val colDays = mutableListOf<Int>()
        headerBoxes.forEach { box ->
            val day = JwCourseTextParser.parseDayOfWeek(box.text) ?: return@forEach
            if (colAnchors.isNotEmpty() && box.centerX - colAnchors.last() < medianHeight) return@forEach
            colAnchors += box.centerX
            colDays += day
        }

        val warnings = mutableListOf<String>()
        if (colAnchors.size !in MIN_DAY_COLUMNS..MAX_DAY_COLUMNS) {
            return AlignedTable.unreliable(listOf("识别到 ${colAnchors.size} 个星期列（期望 5-7 个），表头可能不完整"))
        }
        expectedCols?.let { if (it != colAnchors.size) warnings += "星期列数与预期不符（识别 ${colAnchors.size}，预期 $it）" }
        if (colDays != colDays.sorted()) warnings += "星期表头顺序异常，请重点核对"

        val headerTop = headerGroup.maxOf { it.top }
        val periodBoxes = boxes.filter { box ->
            box.top > headerTop &&
                box.centerX < page.width * 0.25 &&
                JwCourseTextParser.parsePeriodLabel(box.text) != null
        }.sortedBy { it.centerY }

        val rowAnchors = mutableListOf<Int>()
        val rowPeriods = mutableListOf<Int>()
        val rowEndPeriods = mutableListOf<Int>()
        periodBoxes.forEach { box ->
            val range = JwCourseTextParser.parsePeriodLabel(box.text) ?: return@forEach
            if (rowAnchors.isNotEmpty() && box.centerY - rowAnchors.last() < medianHeight) return@forEach
            rowAnchors += box.centerY
            rowPeriods += range.first
            rowEndPeriods += range.last
        }
        if (rowAnchors.size < MIN_PERIOD_ROWS) {
            return AlignedTable.unreliable(listOf("没有找到节次列（识别到 ${rowAnchors.size} 行），无法确定课表行"))
        }
        expectedRows?.let { if (it != rowAnchors.size) warnings += "节次行数与预期不符（识别 ${rowAnchors.size}，预期 $it）" }

        val rowTolerance = toleranceOf(rowAnchors, medianHeight)
        val colTolerance = toleranceOf(colAnchors, medianHeight)

        val headerSet = headerGroup.toHashSet()
        val periodSet = periodBoxes.toHashSet()
        val buckets = Array(rowAnchors.size) { Array(colAnchors.size) { mutableListOf<OcrBox>() } }
        val unassigned = mutableListOf<OcrBox>()

        boxes.forEach { box ->
            if (box in headerSet || box in periodSet) return@forEach
            val row = nearestIndex(rowAnchors, box.centerY, rowTolerance)
            val col = nearestIndex(colAnchors, box.centerX, colTolerance)
            if (row == null || col == null) {
                unassigned += box
            } else {
                buckets[row][col] += box
            }
        }

        val cells = buckets.map { row ->
            row.map { cell ->
                cell.sortedWith(compareBy({ it.top }, { it.left }))
                    .joinToString("\n") { it.text.trim() }
                    .trim()
            }
        }

        val considered = boxes.size - headerSet.size - periodSet.size
        val assignedRatio = if (considered <= 0) 1.0 else (considered - unassigned.size).toDouble() / considered
        if (unassigned.isNotEmpty()) {
            warnings += "有 ${unassigned.size} 个文本块没能归入网格（占 ${(1 - assignedRatio).times(100).toInt()}%）"
        }
        val reliable = assignedRatio >= 1 - MAX_UNASSIGNED_RATIO

        return AlignedTable(
            rowAnchors = rowAnchors,
            rowPeriods = rowPeriods,
            rowEndPeriods = rowEndPeriods,
            colAnchors = colAnchors,
            colDays = colDays,
            cells = cells,
            reliable = reliable,
            warnings = warnings,
            unassigned = unassigned,
        )
    }

    /** 按纵向重叠把文本框分组成行。 */
    private fun groupIntoRows(boxes: List<OcrBox>, medianHeight: Int): List<List<OcrBox>> {
        val sorted = boxes.sortedBy { it.centerY }
        val groups = mutableListOf<MutableList<OcrBox>>()
        val threshold = medianHeight * 0.7
        var current = mutableListOf<OcrBox>()
        var currentSum = 0L
        sorted.forEach { box ->
            if (current.isEmpty()) {
                current = mutableListOf(box)
                currentSum = box.centerY.toLong()
                return@forEach
            }
            val mean = currentSum.toDouble() / current.size
            if (kotlin.math.abs(box.centerY - mean) <= threshold) {
                current += box
                currentSum += box.centerY
            } else {
                groups += current
                current = mutableListOf(box)
                currentSum = box.centerY.toLong()
            }
        }
        if (current.isNotEmpty()) groups += current
        return groups
    }

    private fun nearestIndex(anchors: List<Int>, value: Int, tolerance: Int): Int? {
        var best = -1
        var bestDistance = Int.MAX_VALUE
        anchors.forEachIndexed { index, anchor ->
            val distance = kotlin.math.abs(anchor - value)
            if (distance < bestDistance) {
                bestDistance = distance
                best = index
            }
        }
        return if (best >= 0 && bestDistance <= tolerance) best else null
    }

    private fun minGap(anchors: List<Int>): Int =
        anchors.sorted().zipWithNext().minOfOrNull { (a, b) -> b - a } ?: Int.MAX_VALUE

    /**
     * 吸附容差 = 相邻锚点间距的一半（单个锚点时放宽到 3 倍字高）。
     *
     * 注意**不能**用「按 centerY 分组」来定行：同一个单元格里的多行文字（课名/周次/教室）
     * 纵向间距与行距同量级，会被切成多行，导致整列错位。行锚点必须来自节次列。
     */
    private fun toleranceOf(anchors: List<Int>, medianHeight: Int): Int {
        val gap = minGap(anchors)
        return if (gap == Int.MAX_VALUE) medianHeight * 3 else (gap / 2).coerceAtLeast(medianHeight / 2)
    }
}
