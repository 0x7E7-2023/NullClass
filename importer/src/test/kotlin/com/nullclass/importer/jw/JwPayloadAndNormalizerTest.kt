package com.nullclass.importer.jw

import com.nullclass.core.model.CourseColorKeywords
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JwPayloadAndNormalizerTest {

    private val validPayload = """
        {"specVersion":1,"kind":"schedule","terms":[{
          "name":"2026-2027 学年第一学期","firstDay":"2026-09-07","totalWeeks":20,
          "courses":[
            {"name":"高等数学A(一)","teacher":"张三","blocks":[
              {"dayOfWeek":1,"startPeriod":1,"endPeriod":2,"startWeek":1,"endWeek":16,"weekType":"ALL","location":"教1-101"}]},
            {"name":"大学英语","blocks":[
              {"dayOfWeek":3,"startPeriod":3,"endPeriod":4,"startWeek":1,"endWeek":16,"weekType":"ODD"}]}
          ]}]}
    """.trimIndent()

    @Test
    fun `合法载荷解析通过`() {
        val payload = JwPayloadCodec.decode(validPayload)
        assertEquals(1, payload.terms.size)
        assertEquals(2, payload.terms[0].courses.size)
    }

    @Test
    fun `载荷错误带定位信息`() {
        val bad = validPayload.replace("\"dayOfWeek\":1", "\"dayOfWeek\":9")
        val error = assertFailsWith<JwPackageException> { JwPayloadCodec.decode(bad) }
        assertTrue(error.message!!.contains("第 1 个学期"), error.message)
        assertTrue(error.message!!.contains("第 1 门课程"), error.message)
        assertTrue(error.message!!.contains("dayOfWeek"), error.message)
    }

    @Test
    fun `周次超出学期总周数被拒绝`() {
        val bad = validPayload.replace("\"endWeek\":16,\"weekType\":\"ALL\"", "\"endWeek\":30,\"weekType\":\"ALL\"")
        val error = assertFailsWith<JwPackageException> { JwPayloadCodec.decode(bad) }
        assertTrue(error.message!!.contains("超出学期总周数"), error.message)
    }

    @Test
    fun `weekType 只接受三种取值`() {
        val bad = validPayload.replace("\"weekType\":\"ODD\"", "\"weekType\":\"SOMETIMES\"")
        assertFailsWith<JwPackageException> { JwPayloadCodec.decode(bad) }
    }

    @Test
    fun `图片载荷需要 url 或 data`() {
        val ok = JwPayloadCodec.decode("""{"specVersion":1,"kind":"image","images":[{"url":"https://x.cn/a.png"}]}""")
        assertEquals(JwSchedulePayload.KIND_IMAGE, ok.kind)
        val error = assertFailsWith<JwPackageException> {
            JwPayloadCodec.decode("""{"specVersion":1,"kind":"image","images":[{}]}""")
        }
        assertTrue(error.message!!.contains("url"), error.message)
    }

    @Test
    fun `文本块载荷需要文字与合法的区域尺寸`() {
        val payload = JwPayloadCodec.decode(
            """{"specVersion":1,"kind":"boxes","pageWidth":900,"pageHeight":460,
               "boxes":[{"text":"周一","x":110,"y":6,"w":32,"h":20}]}""".trimIndent(),
        )
        assertEquals(JwSchedulePayload.KIND_BOXES, payload.kind)
        assertEquals(1, payload.boxes.size)
        assertEquals("周一", payload.boxes.single().text)

        val noBoxes = assertFailsWith<JwPackageException> {
            JwPayloadCodec.decode("""{"kind":"boxes"}""")
        }
        assertTrue(noBoxes.message!!.contains("文本块"), noBoxes.message)
        val emptyText = assertFailsWith<JwPackageException> {
            JwPayloadCodec.decode("""{"kind":"boxes","boxes":[{"text":"  ","x":1,"y":1}]}""")
        }
        assertTrue(emptyText.message!!.contains("text"), emptyText.message)
        val badSize = assertFailsWith<JwPackageException> {
            JwPayloadCodec.decode("""{"kind":"boxes","pageWidth":0,"boxes":[{"text":"周一","x":1,"y":1}]}""")
        }
        assertTrue(badSize.message!!.contains("尺寸"), badSize.message)
        val badBox = assertFailsWith<JwPackageException> {
            JwPayloadCodec.decode("""{"kind":"boxes","boxes":[{"text":"周一","x":1,"y":1,"w":-2}]}""")
        }
        assertTrue(badBox.message!!.contains("负"), badBox.message)
    }

    // ---- 核对提示（规范 §4）----

    @Test
    fun `适配器写的核对提示原样保留`() {
        val payload = JwPayloadCodec.decode(
            """
            {"specVersion":1,"kind":"schedule",
             "warnings":["开学日期无法从教务获取，已按最近的周一推算，请核对","教室名可能含校区后缀"],
             "terms":[{"name":"t","firstDay":"2026-09-07","courses":[]}]}
            """.trimIndent(),
        )
        assertEquals(2, payload.warnings.size)
        assertEquals(payload.warnings, payload.reviewNotes, "没有 ocrAssisted 时提示就是适配器写的那几条")
    }

    @Test
    fun `ocrAssisted 会真的变成一条提示`() {
        // 这条曾经只是文档里的承诺：字段解析得出来、传得下去，但没有任何界面读它。
        val payload = JwPayloadCodec.decode(
            """
            {"specVersion":1,"kind":"schedule","ocrAssisted":true,
             "warnings":["周次是从图片里认的"],
             "terms":[{"name":"t","firstDay":"2026-09-07","courses":[]}]}
            """.trimIndent(),
        )
        assertEquals(listOf(OCR_REVIEW_NOTE, "周次是从图片里认的"), payload.reviewNotes)
        assertTrue(OCR_REVIEW_NOTE.isNotBlank())
    }

    @Test
    fun `不给提示时没有任何核对条目`() {
        assertTrue(JwPayloadCodec.decode(validPayload).reviewNotes.isEmpty())
    }

    @Test
    fun `核对提示的条数与长度都有上限`() {
        val many = (1..JwSchedulePayload.MAX_WARNINGS + 1).joinToString(",") { "\"提示$it\"" }
        assertFailsWith<JwPackageException> {
            JwPayloadCodec.decode(
                """{"specVersion":1,"terms":[{"name":"t","firstDay":"2026-09-07"}],"warnings":[$many]}""",
            )
        }
        val long = "提".repeat(JwSchedulePayload.MAX_WARNING_TEXT + 1)
        assertFailsWith<JwPackageException> {
            JwPayloadCodec.decode(
                """{"specVersion":1,"terms":[{"name":"t","firstDay":"2026-09-07"}],"warnings":["$long"]}""",
            )
        }
        assertFailsWith<JwPackageException> {
            JwPayloadCodec.decode(
                """{"specVersion":1,"terms":[{"name":"t","firstDay":"2026-09-07"}],"warnings":["   "]}""",
            )
        }
    }

    @Test
    fun `不认识的载荷类型会被点名`() {
        val error = assertFailsWith<JwPackageException> { JwPayloadCodec.decode("""{"kind":"nope"}""") }
        assertTrue(error.message!!.contains("nope"), error.message)
    }

    @Test
    fun `归一化补齐 id 时间戳与默认节次表`() {
        val document = JwScheduleNormalizer.normalize(JwPayloadCodec.decode(validPayload), "demo-univ", now = 1234L)

        assertEquals("jw-demo-univ", document.deviceId)
        assertEquals(1234L, document.generatedAt)
        assertEquals(1, document.terms.size)
        val term = document.terms.single()
        assertEquals(2, document.courses.size)
        assertEquals(2, document.blocks.size)
        assertEquals(12, document.periodTimes.size)
        assertEquals(term.id, document.courses[0].termId)
        assertEquals(document.courses[0].id, document.blocks[0].courseId)
        assertEquals(CourseColorKeywords.MATH, document.courses[0].colorIndex)
        assertEquals(CourseColorKeywords.LANGUAGE, document.courses[1].colorIndex)
        assertEquals("张三", document.courses[0].teacher)
        assertNull(document.courses[1].teacher)
        assertEquals("ODD", document.blocks[1].weekType)
        assertNull(document.blocks[1].location)
        assertTrue(document.periodTimes.all { it.termId == term.id })
    }

    @Test
    fun `载荷自带节次表时优先使用`() {
        val payload = JwPayloadCodec.decode(
            """
            {"specVersion":1,"kind":"schedule","terms":[{
              "name":"T","firstDay":"2026-09-07","totalWeeks":10,
              "periodTimes":[{"periodIndex":1,"start":"09:00","end":"09:45"}],
              "courses":[]}]}
            """.trimIndent(),
        )
        val document = JwScheduleNormalizer.normalize(payload, "x", now = 0L)
        assertEquals(1, document.periodTimes.size)
        assertEquals(9 * 60, document.periodTimes[0].startMinuteOfDay)
        assertEquals(0, document.periodTimes[0].session)
    }

    @Test
    fun `图片载荷不能直接归一化成课表`() {
        val payload = JwPayloadCodec.decode("""{"specVersion":1,"kind":"image","images":[{"data":"data:image/png;base64,AA"}]}""")
        assertFailsWith<JwPackageException> { JwScheduleNormalizer.normalize(payload, "x", now = 0L) }
    }

    @Test
    fun `未命中课名颜色下标循环`() {
        val many = buildString {
            append("""{"specVersion":1,"kind":"schedule","terms":[{"name":"T","firstDay":"2026-09-07","totalWeeks":30,"courses":[""")
            repeat(15) { index ->
                if (index > 0) append(',')
                append("""{"name":"C$index","blocks":[{"dayOfWeek":1,"startPeriod":1,"endPeriod":1}]}""")
            }
            append("""]}]}""")
        }
        val document = JwScheduleNormalizer.normalize(JwPayloadCodec.decode(many), "x", now = 0L, colorCount = 12)
        assertEquals(0, document.courses[12].colorIndex)
        assertEquals(2, document.courses[14].colorIndex)
    }
}
