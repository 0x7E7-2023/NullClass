package com.nullclass.importer.jw

import com.nullclass.core.model.Term
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.mozilla.javascript.Context
import org.mozilla.javascript.ScriptableObject
import java.io.File
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JwUstcAdapterTest {
    private val adapterDir = File(System.getProperty("jwLibraryDir"), "ustc")

    @Test
    fun `排课日期经过适配器和领域模型后保持不变`() {
        listOf("basic", "sunday-week-boundary").forEach { name ->
            val input = File(adapterDir, "fixtures/$name.extracted.json").readText()
            val source = Json.parseToJsonElement(input).jsonObject
            val lessonNames = source.getValue("lessonList").jsonArray.associate { lesson ->
                val obj = lesson.jsonObject
                obj.getValue("id").jsonPrimitive.content to obj.getValue("name").jsonPrimitive.content
            }
            val expected = source.getValue("scheduleList").jsonArray.map { schedule ->
                val obj = schedule.jsonObject
                lessonNames.getValue(obj.getValue("lessonId").jsonPrimitive.content) to
                    LocalDate.parse(obj.getValue("date").jsonPrimitive.content)
            }
            assertEquals(sorted(expected), datesOf(parse(input)), name)
        }
    }

    @Test
    fun `周日起始的学期保留原周次且不重复修正`() {
        val payload = parseInput(
            firstDay = "2026-08-30",
            schedules = listOf(schedule("2026-09-20", 4, 7), schedule("2026-09-21", 4, 1)),
        )
        assertEquals(listOf(4, 4), payload.terms.single().courses.single().blocks.map { it.startWeek })
        assertDates(payload, "2026-09-20", "2026-09-21")
    }

    @Test
    fun `缺少学期日期时从周日排课推算第一周周一`() {
        val payload = parseInput(
            firstDay = null,
            schedules = listOf(schedule("2026-09-20", 4, 7), schedule("2026-09-21", 4, 1)),
        )
        assertEquals("2026-08-31", payload.terms.single().firstDay)
        assertDates(payload, "2026-09-20", "2026-09-21")
        assertTrue(payload.warnings.any { "推算" in it })
    }

    @Test
    fun `实际日期优先于周次和星期字段`() {
        val payload = parseInput(
            schedules = listOf(schedule("2026-09-20", 99, 1), schedule("2026-09-21", null, null)),
        )
        assertDates(payload, "2026-09-20", "2026-09-21")
    }

    @Test
    fun `缺少日期或日期非法时按周日周界换算并提示`() {
        val payload = parseInput(
            schedules = listOf(schedule(null, 4, 7), schedule("2026-02-30", 4, 1)),
        )
        assertDates(payload, "2026-09-20", "2026-09-21")
        assertTrue(payload.warnings.any { "2 条" in it && "日期" in it })

        val sundayTerm = parseInput(
            firstDay = "2026-08-30",
            schedules = listOf(schedule(null, 4, 7)),
        )
        assertDates(sundayTerm, "2026-09-20")
    }

    @Test
    fun `学期首日前和第三十周之后的排课不钳制到其他日期`() {
        val payload = parseInput(
            schedules = listOf(
                schedule("2026-08-30", 1, 7),
                schedule("2026-08-31", 1, 1),
                schedule("2027-03-28", 31, 7),
                schedule("2027-03-29", 31, 1),
            ),
        )
        assertDates(payload, "2026-08-31", "2027-03-28")
        assertEquals(30, payload.terms.single().totalWeeks)
        assertTrue(payload.warnings.any { "早于" in it })
        assertTrue(payload.warnings.any { "30 周" in it })
    }

    private fun schedule(date: String?, week: Int?, day: Int?): JsonObject = buildJsonObject {
        put("lessonId", 1)
        date?.let { put("date", it) }
        week?.let { put("weekIndex", it) }
        day?.let { put("weekday", it) }
        put("startTime", 750)
        put("periods", 2)
    }

    private fun parseInput(
        firstDay: String? = "2026-08-31",
        schedules: List<JsonObject>,
    ): JwSchedulePayload = parse(
        buildJsonObject {
            put("term", buildJsonObject {
                put("name", "测试学期")
                firstDay?.let { put("firstDay", it) }
                put("totalWeeks", 18)
            })
            put("lessonList", buildJsonArray {
                add(buildJsonObject {
                    put("id", 1)
                    put("name", "测试课程")
                })
            })
            put("scheduleList", buildJsonArray { schedules.forEach { add(it) } })
        }.toString(),
    )

    private fun parse(input: String): JwSchedulePayload {
        val context = Context.enter()
        try {
            context.optimizationLevel = -1
            context.languageVersion = Context.VERSION_ES6
            val scope = context.initStandardObjects()
            ScriptableObject.putProperty(scope, JwScriptContract.GLOBAL_INPUT, input)
            val output = context.evaluateString(scope, File(adapterDir, "parse.js").readText(), "parse.js", 1, null)
            return JwPayloadCodec.decode(Context.toString(output))
        } finally {
            Context.exit()
        }
    }

    private fun assertDates(payload: JwSchedulePayload, vararg dates: String) {
        assertEquals(sorted(dates.map { "测试课程" to LocalDate.parse(it) }), datesOf(payload))
    }

    // 用应用实际采用的 Term.epochDayOf 还原日期，不能只验证适配器输出的周次数字。
    private fun datesOf(payload: JwSchedulePayload): List<Pair<String, LocalDate>> {
        val document = JwScheduleNormalizer.normalize(payload, "ustc", now = 0L)
        val importedTerm = document.terms.single()
        val term = Term(
            name = importedTerm.name,
            firstDayEpochDay = importedTerm.firstDayEpochDay,
            totalWeeks = importedTerm.totalWeeks,
        )
        val courses = document.courses.associateBy { it.id }
        return sorted(document.blocks.flatMap { block ->
            (block.startWeek..block.endWeek).filter { week ->
                when (block.weekType) {
                    "ODD" -> week % 2 == 1
                    "EVEN" -> week % 2 == 0
                    else -> true
                }
            }.map { week ->
                courses.getValue(block.courseId).name to LocalDate.ofEpochDay(term.epochDayOf(week, block.dayOfWeek))
            }
        })
    }

    private fun sorted(dates: List<Pair<String, LocalDate>>): List<Pair<String, LocalDate>> =
        dates.sortedWith(compareBy({ it.first }, { it.second }))
}
