package com.nullclass.importer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class QrPayloadTest {

    private fun smallDocument() = ScheduleDocument(
        deviceId = "qr-test",
        generatedAt = 42L,
        terms = listOf(
            TermDto("t1", "2026-2027-1", 20671, 20, true, 1, 1),
        ),
        courses = listOf(
            CourseDto("c1", "t1", "高数", teacher = "张三", colorIndex = 0, createdAt = 1, updatedAt = 1),
        ),
        blocks = listOf(
            BlockDto("b1", "c1", "t1", 1, 20, "ALL", 1, 1, 2, "A101", 1, 1),
        ),
        periodTimes = listOf(
            PeriodTimeDto("t1", 1, 480, 525, 0, 1),
        ),
    )

    @Test
    fun `round-trip - 小课表编码解码保真`() {
        val payload = QrPayload.encode(smallDocument())
        assertTrue(payload.startsWith("NULLCLASS1:"))
        val decoded = QrPayload.decode(payload)
        assertEquals(smallDocument(), decoded)
    }

    @Test
    fun `压缩有效 - 负载明显小于原始 JSON`() {
        val doc = smallDocument()
        val payload = QrPayload.encode(doc)
        assertTrue(payload.length < NullClassCodec.encode(doc).length, "gzip 后应更小: ${payload.length}")
    }

    @Test
    fun `错误前缀 - 拒绝`() {
        assertFailsWith<IllegalArgumentException> {
            QrPayload.decode("WAKEUP:xxxxx")
        }
        assertTrue(QrPayload.isNullClassPayload("NULLCLASS1:x"))
        assertTrue(!QrPayload.isNullClassPayload("其他内容"))
    }

    @Test
    fun `损坏的 base64 - 拒绝`() {
        assertFailsWith<IllegalArgumentException> {
            QrPayload.decode("NULLCLASS1:!!!not-base64!!!")
        }
    }

    @Test
    fun `损坏的 gzip 数据 - 拒绝`() {
        val fakeGzip = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(ByteArray(64) { it.toByte() })
        assertFailsWith<IllegalArgumentException> {
            QrPayload.decode("NULLCLASS1:$fakeGzip")
        }
    }

    @Test
    fun `超限课表 - 抛 PayloadTooLargeException`() {
        // 500 门课 × 2 安排 → gzip 后仍超 2953B
        val big = ScheduleDocument(
            deviceId = "big",
            generatedAt = 1L,
            terms = listOf(TermDto("t1", "x", 20671, 25, true, 1, 1)),
            courses = (1..500).map {
                CourseDto("c$it", "t1", "课程$it", teacher = "老师$it", colorIndex = it, createdAt = 1, updatedAt = 1)
            },
            blocks = (1..1000).map {
                BlockDto("b$it", "c${it / 2 + 1}", "t1", 1, 25, "ALL", it % 7 + 1, 1, 2, "教室$it", 1, 1)
            },
            periodTimes = (1..12).map {
                PeriodTimeDto("t1", it, 480 + it, 525 + it, 0, 1)
            },
        )
        val e = assertFailsWith<QrPayload.PayloadTooLargeException> { QrPayload.encode(big) }
        assertTrue(e.size > QrPayload.MAX_PAYLOAD_BYTES)
    }
}
