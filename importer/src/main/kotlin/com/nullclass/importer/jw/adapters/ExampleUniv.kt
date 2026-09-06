package com.nullclass.importer.jw.adapters

import com.nullclass.importer.BlockDto
import com.nullclass.importer.CourseDto
import com.nullclass.importer.PeriodTimeDto
import com.nullclass.importer.ScheduleDocument
import com.nullclass.importer.TermDto
import com.nullclass.importer.jw.JwAdapter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate
import java.util.UUID

/**
 * 示例适配器：演示 JwAdapter 的完整用法与可测性，**不对应真实学校**。
 *
 * - extractScript：通用表格抓取（所有 table 的行列文本）
 * - parseExtracted：把「单元格文本表」按启发式映射为课表文档（真实适配器按各校 DOM 定制）
 */
object ExampleUniv : JwAdapter {

    override val schoolName = "示例大学（演示适配器）"
    override val schoolKey = "example-univ"
    override val loginUrl = "https://jw.example.edu.cn/"

    /** 通用表格抓取：{url, title, rows: [[cell,...],...]}。保守 ES5。 */
    override val extractScript: String = """
        (function() {
            var rows = [];
            var tables = document.querySelectorAll('table');
            for (var i = 0; i < tables.length; i++) {
                var trs = tables[i].querySelectorAll('tr');
                for (var j = 0; j < trs.length; j++) {
                    var cells = [];
                    var tds = trs[j].querySelectorAll('td,th');
                    for (var k = 0; k < tds.length; k++) {
                        cells.push(tds[k].innerText.replace(/\s+/g, ' ').trim());
                    }
                    if (cells.length > 0) rows.push(cells);
                }
            }
            return JSON.stringify({url: location.href, title: document.title, rows: rows});
        })()
    """.trimIndent()

    /**
     * 提取结果 → 课表文档。约定提取 JSON 里每个 table 行是：
     * `[课名, 周次文本, 星期, 节次, 教室]`（示例启发式：真实适配器按各校课表结构定制）。
     */
    override fun parseExtracted(extractedJson: String): ScheduleDocument {
        val root = Json.parseToJsonElement(extractedJson).jsonObject
        val rows = (root["rows"] as? JsonArray)?.map { it.jsonArray.mapNotNull { c -> c.jsonPrimitive.contentOrNull } }
            ?: throw IllegalArgumentException("提取数据缺少 rows")

        val termId = UUID.randomUUID().toString()
        val now = 0L
        // 学期起点：本周周一（真实适配器从教务学期信息读取）
        val today = LocalDate.now()
        val monday = today.minusDays((today.dayOfWeek.value - 1).toLong())
        val term = TermDto(
            id = termId,
            name = root["title"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: "教务导入",
            firstDayEpochDay = monday.toEpochDay(),
            totalWeeks = 20,
            isCurrent = false,
            createdAt = now,
            updatedAt = now,
        )

        val courses = mutableListOf<CourseDto>()
        val blocks = mutableListOf<BlockDto>()
        for (row in rows) {
            if (row.size < 4) continue
            val name = row[0].trim()
            if (name.isEmpty() || name == "课程名称") continue // 表头
            val course = CourseDto(
                id = UUID.randomUUID().toString(),
                termId = termId,
                name = name,
                colorIndex = courses.size,
                createdAt = now,
                updatedAt = now,
            )
            courses.add(course)
            blocks.add(
                BlockDto(
                    id = UUID.randomUUID().toString(),
                    courseId = course.id,
                    termId = termId,
                    startWeek = parseWeeks(row.getOrNull(1))?.first ?: 1,
                    endWeek = parseWeeks(row.getOrNull(1))?.second ?: 20,
                    weekType = if (row.getOrNull(1)?.contains("单") == true) "ODD" else "ALL",
                    dayOfWeek = row.getOrNull(2)?.trim()?.toIntOrNull()?.coerceIn(1, 7) ?: 1,
                    startPeriod = row.getOrNull(3)?.toIntOrNull()?.coerceAtLeast(1) ?: 1,
                    endPeriod = row.getOrNull(3)?.toIntOrNull()?.plus(1)?.coerceAtLeast(2) ?: 2,
                    location = row.getOrNull(4)?.takeIf { it.isNotBlank() },
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }
        if (courses.isEmpty()) throw IllegalArgumentException("页面上没有识别到课程表格")

        return ScheduleDocument(
            deviceId = "jw-$schoolKey",
            generatedAt = System.currentTimeMillis(),
            terms = listOf(term),
            courses = courses,
            blocks = blocks,
            periodTimes = defaultPeriods(termId),
        )
    }

    // ---- 内部 ----

    /** "1-16周" / "1,3-8周" → (min, max)；解析失败 null。 */
    private fun parseWeeks(text: String?): Pair<Int, Int>? {
        val weeks = Regex("""(\d{1,2})""").findAll(text ?: return null)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .filter { it in 1..25 }
            .toList()
        if (weeks.isEmpty()) return null
        return weeks.min() to weeks.max()
    }

    /** 默认节次表（与 core:data DefaultPeriodTimes 同源；真实适配器从教务读取）。 */
    private fun defaultPeriods(termId: String): List<PeriodTimeDto> = listOf(
        480 to 525, 535 to 580, 600 to 645, 655 to 700,
        840 to 885, 895 to 940, 960 to 1005, 1015 to 1060,
        1110 to 1155, 1165 to 1210, 1220 to 1265, 1275 to 1320,
    ).mapIndexed { index, (start, end) ->
        PeriodTimeDto(
            termId = termId, periodIndex = index + 1,
            startMinuteOfDay = start, endMinuteOfDay = end,
            session = when {
                start < 12 * 60 -> 0
                start < 18 * 60 -> 1
                else -> 2
            },
            updatedAt = 0L,
        )
    }
}
