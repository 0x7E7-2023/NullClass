package com.nullclass.importer.wakeup

import com.nullclass.importer.BlockDto
import com.nullclass.importer.CourseDto
import com.nullclass.importer.PeriodTimeDto
import com.nullclass.importer.TermDto
import com.nullclass.importer.ScheduleDocument
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * WakeUp 课表（.wakeup_schedule）解析器。
 *
 * 真实格式为**逐行 JSON**（每行一个独立 JSON 文档）：
 * 版本号字符串 / 学期设置对象 / 节次时间数组 / CourseInfo 数组（课名+颜色）/ Course 数组（时间安排）。
 * **红线：不得依赖行号**——各版本 WakeUp 行数与顺序有差异，逐行尝试解析、按内容特征分类。
 */
object WakeUpParser {

    /** 与 core:ui CoursePalette 同源的 12 色 RGB（纯 JVM 模块不依赖 Compose，复制一份）。 */
    private val PaletteRgb = intArrayOf(
        0xE8595B, 0xE64980, 0x9C36B5, 0x6741D9, 0x3B5BDB, 0x1C7ED6,
        0x0CA678, 0x2F9E44, 0x74B816, 0xF59F00, 0xE8590C, 0x846358,
    )

    /** 默认节次模板（与 core:data DefaultPeriodTimes 同源，前 12 节）。 */
    private val DefaultSlots = listOf(
        480 to 525, 535 to 580, 600 to 645, 655 to 700,     // 上午
        840 to 885, 895 to 940, 960 to 1005, 1015 to 1060,  // 下午
        1110 to 1155, 1165 to 1210, 1220 to 1265, 1275 to 1320, // 晚上
    )

    data class WakeUpResult(
        val term: TermDto,
        val courses: List<CourseDto>,
        val blocks: List<BlockDto>,
        val periodTimes: List<PeriodTimeDto>,
        val warnings: List<String>,
    )

    /** @throws IllegalArgumentException 带用户可读 message */
    fun parse(raw: String): WakeUpResult {
        val warnings = mutableListOf<String>()
        val json = Json { ignoreUnknownKeys = true }

        var settings: JsonObject? = null
        var periodRows: List<JsonObject>? = null
        var courseInfoRows: List<JsonObject>? = null
        var courseRows: List<JsonObject>? = null

        for ((index, line) in raw.lineSequence().map { it.trim() }.withIndex()) {
            if (line.isEmpty()) continue
            val element: JsonElement = try {
                json.parseToJsonElement(line)
            } catch (e: kotlinx.serialization.SerializationException) {
                warnings.add("第 ${index + 1} 行无法解析，已跳过")
                continue
            }
            when (element) {
                is JsonPrimitive -> continue // 版本号行，忽略
                is JsonObject ->
                    // 学期设置：含 courseTableName，或 startTime+maxWeek 组合（内容特征，不看行号）
                    if (element.containsKey("courseTableName") ||
                        (element.containsKey("startTime") && element.containsKey("maxWeek"))
                    ) {
                        settings = element
                    } else {
                        warnings.add("第 ${index + 1} 行是无法识别的对象，已跳过")
                    }

                is JsonArray -> when {
                    element.jsonArray.all { it is JsonObject } -> {
                        val objects = element.jsonArray.map { it.jsonObject }
                        when {
                            objects.any { it.containsKey("courseName") } ->
                                courseInfoRows = (courseInfoRows ?: emptyList()) + objects

                            objects.any { it.containsKey("startNode") } ->
                                courseRows = (courseRows ?: emptyList()) + objects

                            objects.any { it.containsKey("node") && it.containsKey("startTime") } ->
                                periodRows = (periodRows ?: emptyList()) + objects

                            else -> warnings.add("第 ${index + 1} 行是无法识别的数组，已跳过")
                        }
                    }

                    else -> warnings.add("第 ${index + 1} 行是无法识别的数组，已跳过")
                }
            }
        }

        if (courseRows == null) {
            throw IllegalArgumentException("不是有效的 WakeUp 课表文件（未找到课程时间安排数据）")
        }
        if (courseInfoRows == null) {
            throw IllegalArgumentException("WakeUp 文件缺少课程信息（courseName 列表）")
        }

        // ---- 学期 ----
        val settingsObj = settings
        val termName = settingsObj?.get("courseTableName")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: "导入的课表"
        val rawWeeks = settingsObj?.get("maxWeek")?.jsonPrimitive?.intOrNull ?: 20
        val totalWeeks = rawWeeks.coerceIn(1, 25)
        if (totalWeeks != rawWeeks) warnings.add("学期周数 $rawWeeks 超出范围，已截断为 $totalWeeks")
        val firstDayEpochDay = parseFirstDay(settingsObj?.get("startTime")?.jsonPrimitive?.contentOrNull)
        val now = 0L // 导入文档统一 0 时间戳：merge 时同 id 不存在冲突，语义为"来自外部"
        val termId = UUID.randomUUID().toString()
        val term = TermDto(
            id = termId, name = termName, firstDayEpochDay = firstDayEpochDay, totalWeeks = totalWeeks,
            isCurrent = false, createdAt = now, updatedAt = now,
        )

        // ---- 节次表（WakeUp 自带的优先；缺失退化默认模板） ----
        val periodTimes: List<PeriodTimeDto> = periodRows
            ?.mapNotNull { row ->
                val index = row["node"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                val start = parseHm(row["startTime"]?.jsonPrimitive?.contentOrNull) ?: return@mapNotNull null
                val end = parseHm(row["endTime"]?.jsonPrimitive?.contentOrNull) ?: return@mapNotNull null
                PeriodTimeDto(
                    termId = termId, periodIndex = index,
                    startMinuteOfDay = start, endMinuteOfDay = end,
                    session = sessionOf(start), updatedAt = now,
                )
            }
            ?.sortedBy { it.periodIndex }
            ?.takeIf { it.isNotEmpty() }
            ?: run {
                warnings.add("WakeUp 文件无节次时间表，已使用默认模板")
                buildDefaultPeriods(termId, requiredPeriods(courseRows, settingsObj))
            }

        // ---- 课程（CourseInfo × 去重 id）----
        val nameById = linkedMapOf<Int, Pair<String, Int>>() // wakeup id -> (课名, colorIndex)
        for (row in courseInfoRows) {
            val id = row["id"]?.jsonPrimitive?.intOrNull
            val name = row["courseName"]?.jsonPrimitive?.contentOrNull
            if (id == null || name.isNullOrBlank()) continue
            val colorIndex = nearestColor(row["color"]?.jsonPrimitive?.contentOrNull)
            nameById[id] = name to colorIndex
        }
        if (nameById.isEmpty()) throw IllegalArgumentException("WakeUp 文件课程信息为空")

        // Course 行里出现但 CourseInfo 没有的 id → 生成占位课名
        courseRows.forEach { row ->
            val id = row["id"]?.jsonPrimitive?.intOrNull ?: return@forEach
            if (id !in nameById) {
                warnings.add("课程 id=$id 缺少课名信息，已按「课程 $id」导入")
                nameById[id] = "课程 $id" to id.mod(PaletteRgb.size)
            }
        }

        val courses = nameById.map { (wakeupId, nameAndColor) ->
            CourseDto(
                id = UUID.randomUUID().toString(),
                termId = termId,
                name = nameAndColor.first,
                teacher = courseRows.lastOrNull { it["id"]?.jsonPrimitive?.intOrNull == wakeupId }
                    ?.get("teacher")?.jsonPrimitive?.contentOrNull,
                colorIndex = nameAndColor.second,
                createdAt = now, updatedAt = now,
            )
        }

        // ---- 安排（每条 Course 行一块）----
        val courseIdByWakeupId = nameById.keys.zip(courses.map { it.id }).toMap()
        val blocks = courseRows.mapIndexedNotNull { i, row ->
            val wakeupId = row["id"]?.jsonPrimitive?.intOrNull ?: return@mapIndexedNotNull null
            val day = row["day"]?.jsonPrimitive?.intOrNull ?: return@mapIndexedNotNull null
            val startNode = row["startNode"]?.jsonPrimitive?.intOrNull ?: return@mapIndexedNotNull null
            val step = row["step"]?.jsonPrimitive?.intOrNull ?: 1
            val startWeek = (row["startWeek"]?.jsonPrimitive?.intOrNull ?: 1).coerceIn(1, totalWeeks)
            val endWeek = (row["endWeek"]?.jsonPrimitive?.intOrNull ?: totalWeeks).coerceIn(1, totalWeeks)
            if (day !in 1..7) {
                warnings.add("第 ${i + 1} 条安排 day=$day 非法，已跳过")
                return@mapIndexedNotNull null
            }
            BlockDto(
                id = UUID.randomUUID().toString(),
                courseId = courseIdByWakeupId[wakeupId] ?: return@mapIndexedNotNull null,
                termId = termId,
                startWeek = minOf(startWeek, endWeek),
                endWeek = maxOf(startWeek, endWeek),
                weekType = when (row["type"]?.jsonPrimitive?.intOrNull) {
                    1 -> "ODD"
                    2 -> "EVEN"
                    else -> "ALL" // 0 或缺失
                },
                dayOfWeek = day,
                startPeriod = startNode,
                endPeriod = startNode + step - 1,
                location = row["room"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
                createdAt = now, updatedAt = now,
            )
        }

        return WakeUpResult(term, courses, blocks, periodTimes, warnings)
    }

    /** 便捷方法：直接产出可进 ImportPreview 的 ScheduleDocument。 */
    fun parseToDocument(raw: String): ScheduleDocument {
        val result = parse(raw)
        return ScheduleDocument(
            deviceId = "wakeup-import",
            generatedAt = System.currentTimeMillis(),
            terms = listOf(result.term),
            courses = result.courses,
            blocks = result.blocks,
            periodTimes = result.periodTimes,
        )
    }

    // ---- 内部工具 ----

    private fun requiredPeriods(courseRows: List<JsonObject>?, settings: JsonObject?): Int {
        val nodesPerDay = settings?.get("nodesPerDay")?.jsonPrimitive?.intOrNull ?: 0
        val maxEnd = courseRows?.mapNotNull { row ->
            val start = row["startNode"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull 0
            val step = row["step"]?.jsonPrimitive?.intOrNull ?: 1
            start + step - 1
        }?.maxOrNull() ?: 0
        return maxOf(nodesPerDay, maxEnd).coerceAtLeast(1)
    }

    private fun buildDefaultPeriods(termId: String, count: Int): List<PeriodTimeDto> =
        (1..count).map { index ->
            val (start, end) = if (index <= DefaultSlots.size) {
                DefaultSlots[index - 1]
            } else {
                // 超出模板的节次：在最后一节后按 45+10 分钟顺延
                val last = DefaultSlots.last().second
                val extra = index - DefaultSlots.size
                val s = last + extra * 55
                s to s + 45
            }
            PeriodTimeDto(termId, index, start, end, sessionOf(start), updatedAt = 0L)
        }

    /** "HH:mm" → 分钟数；不合法返回 null。 */
    private fun parseHm(text: String?): Int? {
        val m = Regex("""^\s*(\d{1,2}):(\d{2})\s*$""").matchEntire(text ?: return null) ?: return null
        val hour = m.groupValues[1].toIntOrNull() ?: return null
        val minute = m.groupValues[2].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour * 60 + minute
    }

    /** "yyyy-MM-dd" → 回退到所在周周一的 epoch day；缺失用今天的周一。 */
    private fun parseFirstDay(text: String?): Long {
        val date = try {
            text?.let { LocalDate.parse(it, DateTimeFormatter.ISO_LOCAL_DATE) } ?: LocalDate.now()
        } catch (e: java.time.format.DateTimeParseException) {
            LocalDate.now()
        }
        val monday = if (date.dayOfWeek == DayOfWeek.MONDAY) date else date.with(DayOfWeek.MONDAY)
        return monday.toEpochDay()
    }

    /** 与 core:model Session 对齐：0 上午 / 1 下午 / 2 晚上。 */
    private fun sessionOf(startMinuteOfDay: Int): Int = when {
        startMinuteOfDay < 12 * 60 -> 0
        startMinuteOfDay < 18 * 60 -> 1
        else -> 2
    }

    /** "#AARRGGBB"/"AARRGGBB"/"#RRGGBB" → 调色板欧氏最近色下标；解析失败返回 0。 */
    internal fun nearestColor(color: String?): Int {
        val hex = color?.removePrefix("#")?.takeLast(6) ?: return 0
        val rgb = hex.toIntOrNull(16) ?: return 0
        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8) and 0xFF
        val b = rgb and 0xFF
        var best = 0
        var bestDist = Int.MAX_VALUE
        PaletteRgb.forEachIndexed { index, palette ->
            val pr = (palette shr 16) and 0xFF
            val pg = (palette shr 8) and 0xFF
            val pb = palette and 0xFF
            val dist = (r - pr) * (r - pr) + (g - pg) * (g - pg) + (b - pb) * (b - pb)
            if (dist < bestDist) {
                bestDist = dist
                best = index
            }
        }
        return best
    }

}
