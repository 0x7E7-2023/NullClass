package com.nullclass.importer.jw

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
        assertEquals(0, document.courses[0].colorIndex)
        assertEquals(1, document.courses[1].colorIndex)
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
    fun `颜色下标循环`() {
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
