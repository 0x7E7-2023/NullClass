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
        // 节次列在星期列**外侧**——左边是最常见的排法，右边也有（少数系统两侧都标）。
        // 判据不用「页宽的百分之几」：同一张表在「容器坐标」与「整页坐标」下页宽差好几倍，
        // 按比例判会在其中一种坐标里整列落空。
        val firstColumn = colAnchors.first()
        val lastColumn = colAnchors.last()
        val outsideColumns = { box: OcrBox -> box.centerX < firstColumn || box.centerX > lastColumn }
        val periodBoxes = boxes.filter { box ->
            box.top > headerTop && outsideColumns(box) && JwCourseTextParser.parsePeriodLabel(box.text) != null
        }.sortedBy { it.centerY }

        val rowAnchors = mutableListOf<Int>()
        val rowPeriods = mutableListOf<Int>()
        val rowEndPeriods = mutableListOf<Int>()
        // 定行用掉的文本框不再参与归格（它们是行标，不是课表内容）
        val rowLabelBoxes = mutableSetOf<OcrBox>()
        periodBoxes.forEach { box ->
            val range = JwCourseTextParser.parsePeriodLabel(box.text) ?: return@forEach
            if (rowAnchors.isNotEmpty() && box.centerY - rowAnchors.last() < medianHeight) return@forEach
            rowAnchors += box.centerY
            rowPeriods += range.first
            rowEndPeriods += range.last
        }
        rowLabelBoxes += periodBoxes

        // 节次号一个都认不出时退一步：有些教务把行标成上课时间（08:00-08:45）而不是「1」「1-2」。
        // 时间标注只用来**定行**；节次号优先取格子里写的「1-2节」，取不到就按行序推断 —— 推断出来的
        // 节次号未必等于学校的节次编号，所以要在 warnings 里讲清楚，让用户核对那一栏看到。
        if (rowAnchors.size < MIN_PERIOD_ROWS) {
            val timeBoxes = boxes.filter { box ->
                box.top > headerTop && outsideColumns(box) && JwCourseTextParser.looksLikeTimeLabel(box.text)
            }.sortedBy { it.centerY }
            if (timeBoxes.size >= MIN_PERIOD_ROWS) {
                timeBoxes.forEach { box ->
                    if (rowAnchors.isNotEmpty() && box.centerY - rowAnchors.last() < medianHeight) return@forEach
                    rowAnchors += box.centerY
                    rowPeriods += rowAnchors.size
                    rowEndPeriods += rowAnchors.size
                }
                warnings += "节次列是按上课时间认的，节次号按行序推断，请重点核对"
                rowLabelBoxes += timeBoxes
            }
        }
        if (rowAnchors.size < MIN_PERIOD_ROWS) {
            return AlignedTable.unreliable(listOf("没有找到节次列（识别到 ${rowAnchors.size} 行），无法确定课表行"))
        }
        expectedRows?.let { if (it != rowAnchors.size) warnings += "节次行数与预期不符（识别 ${rowAnchors.size}，预期 $it）" }

        val colTolerance = toleranceOf(colAnchors, medianHeight)
        val rowRange = tableRows(rowAnchors, headerTop, medianHeight)
        val rowOf = rowLocator(rowAnchors, rowRange)

        val headerSet = headerGroup.toHashSet()
        val buckets = Array(rowAnchors.size) { Array(colAnchors.size) { mutableListOf<OcrBox>() } }
        val unassigned = mutableListOf<OcrBox>()

        // 表格上下之外的文本框（页头导航、页脚版权）本来就不是课表内容，不算候选 ——
        // 否则它们会按「落不进网格」计入比例，把一张干净课表判成不可靠（真机 OCR 实测：8/24、33%）。
        val candidates = boxes.filterNot { it in headerSet || it in rowLabelBoxes }
            .filter { it.centerY in rowRange }

        candidates.forEach { box ->
            val row = rowOf(box.centerY)
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

        val considered = candidates.size
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

    /** 课表的纵向范围：上沿是表头底边，下沿是末行锚点再加半个行距。 */
    private fun tableRows(rowAnchors: List<Int>, headerTop: Int, medianHeight: Int): IntRange {
        val lastGap = rowAnchors[rowAnchors.lastIndex] - rowAnchors[rowAnchors.lastIndex - 1]
        return headerTop..(rowAnchors.last() + (lastGap / 2).coerceAtLeast(medianHeight / 2))
    }

    /**
     * 行归属：按相邻行锚点的**中线**切段，而不是「离锚点不超过半个行距」。
     *
     * 一格跨多节（rowspan）时格子里的文字是靠顶端排的，离它所在行的锚点可以有整整一格那么远——
     * 卡半径会把课名整片判成「落不进网格」（真实金智课表：113 个文本框里 44 个，直接提取失败）。
     * [bounds] 之外的文本框（页头页脚）直接判为不在表内。
     */
    private fun rowLocator(rowAnchors: List<Int>, bounds: IntRange): (Int) -> Int? {
        val edges = rowAnchors.zipWithNext { a, b -> (a + b) / 2 }
        return { centerY ->
            if (centerY !in bounds) {
                null
            } else {
                edges.indexOfFirst { centerY <= it }.let { if (it < 0) rowAnchors.lastIndex else it }
            }
        }
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
