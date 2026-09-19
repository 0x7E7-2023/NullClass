package com.nullclass.importer

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.UUID
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class QrPayloadTest {

    private fun uuid() = UUID.randomUUID().toString()

    private fun smallDocument() = ScheduleDocument(
        deviceId = "qr-test",
        generatedAt = 42L,
        timetables = listOf(TimetableDto("tt1", "我的课表", 1, 1)),
        terms = listOf(
            TermDto("t1", "2026-2027-1", 20671, 20, true, 1, 1, timetableId = "tt1"),
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

    /** 接近真实学期规模：16 门课 × 2 安排 + 12 节次 + 2 场考试，生产形态 random UUID。 */
    private fun typicalTermDocument(
        extraTerms: Int = 0,
        withTombstones: Boolean = false,
    ): ScheduleDocument {
        val ttId = uuid()
        val termId = uuid()
        val courses = (1..16).map { i ->
            CourseDto(
                id = uuid(),
                termId = termId,
                name = "大学课程名称$i",
                teacher = "教师$i",
                note = null,
                colorIndex = i % 12,
                createdAt = 1_700_000_000_000 + i,
                updatedAt = 1_700_000_000_000 + i,
            )
        }
        val blocks = courses.flatMapIndexed { index, course ->
            (0..1).map { slot ->
                BlockDto(
                    id = uuid(),
                    courseId = course.id,
                    termId = termId,
                    startWeek = 1,
                    endWeek = 16,
                    weekType = "ALL",
                    dayOfWeek = (index + slot) % 5 + 1,
                    startPeriod = slot * 2 + 1,
                    endPeriod = slot * 2 + 2,
                    location = "综合楼A${100 + index}",
                    createdAt = course.createdAt,
                    updatedAt = course.updatedAt,
                )
            }
        }
        val periodTimes = (1..12).map { i ->
            PeriodTimeDto(termId, i, 480 + (i - 1) * 55, 525 + (i - 1) * 55, (i - 1) / 4, 1)
        }
        val exams = listOf(1, 8).map { i ->
            ExamDto(
                id = uuid(),
                courseId = courses[i].id,
                title = "期末考试",
                dateEpochDay = 20700L + i,
                startMinuteOfDay = 540,
                endMinuteOfDay = 660,
                location = "教学楼B${i}01",
                createdAt = 1,
                updatedAt = 1,
            )
        }
        val extraTermDtos = (1..extraTerms).map { n ->
            TermDto(uuid(), "历史学期$n", 20600L - n * 140, 20, false, 1, 1, timetableId = ttId)
        }
        val extraCourses = extraTermDtos.flatMap { term ->
            (1..16).map { i ->
                CourseDto(uuid(), term.id, "旧课$i", colorIndex = 0, createdAt = 1, updatedAt = 1)
            }
        }
        val extraBlocks = extraCourses.flatMapIndexed { index, course ->
            (0..1).map { slot ->
                BlockDto(
                    id = uuid(),
                    courseId = course.id,
                    termId = course.termId,
                    startWeek = 1,
                    endWeek = 16,
                    weekType = "ALL",
                    dayOfWeek = (index + slot) % 5 + 1,
                    startPeriod = slot * 2 + 1,
                    endPeriod = slot * 2 + 2,
                    location = "旧教室$index",
                    createdAt = 1,
                    updatedAt = 1,
                )
            }
        }
        val extraPeriods = extraTermDtos.flatMap { term ->
            (1..12).map { i ->
                PeriodTimeDto(term.id, i, 480 + (i - 1) * 55, 525 + (i - 1) * 55, (i - 1) / 4, 1)
            }
        }
        val tombstoneCourses = if (withTombstones) {
            listOf(
                CourseDto(uuid(), termId, "已删", colorIndex = 0, createdAt = 1, updatedAt = 2, deletedAt = 2),
            )
        } else {
            emptyList()
        }
        return ScheduleDocument(
            deviceId = uuid(),
            generatedAt = 1_700_000_000_000,
            timetables = listOf(TimetableDto(ttId, "本科课表", 1, 1)),
            terms = listOf(
                TermDto(termId, "2026-2027学年第一学期", 20671, 20, true, 1, 1, timetableId = ttId),
            ) + extraTermDtos,
            courses = courses + extraCourses + tombstoneCourses,
            blocks = blocks + extraBlocks,
            periodTimes = periodTimes + extraPeriods,
            exams = exams,
        )
    }

    /** 抹掉身份字段后的内容视图：v3 解码重铸 UUID，比对内容是否保真。 */
    private fun contentOf(doc: ScheduleDocument) = doc.copy(
        deviceId = "",
        timetables = doc.timetables.map { it.copy(id = "x") },
        terms = doc.terms.map { it.copy(id = "x", timetableId = "x") },
        courses = doc.courses.map { it.copy(id = "x", termId = "x") },
        blocks = doc.blocks.map { it.copy(id = "x", courseId = "x", termId = "x") },
        periodTimes = doc.periodTimes.map { it.copy(termId = "x") },
        exams = doc.exams.map { it.copy(id = "x", courseId = "x") },
    )

    @Test
    fun `v3 解码 - 重铸 ID 且内容保真`() {
        val original = smallDocument()
        val payload = QrPayload.encode(original)
        assertTrue(payload.startsWith(QrPayload.PREFIX_V3))
        val decoded = QrPayload.decode(payload)
        assertEquals(contentOf(original), contentOf(decoded))
        assertEquals(ImportProvenance.QR_IMPORT, decoded.deviceId, "要盖上扫码分享来源章")
        // 全部 id 现铸：与原 id 不同、彼此不重、引用不悬空
        assertTrue(decoded.terms.single().id != "t1" && decoded.terms.single().id.isNotEmpty())
        val courseIds = decoded.courses.map { it.id }
        assertTrue(decoded.blocks.all { it.courseId in courseIds })
        assertEquals(decoded.courses.map { it.id }.distinct().size, decoded.courses.size)
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
        assertTrue(QrPayload.isNullClassPayload("NULLCLASS2:x"))
        assertTrue(QrPayload.isNullClassPayload("NULLCLASS3:x"))
        assertTrue(!QrPayload.isNullClassPayload("其他内容"))
    }

    @Test
    fun `损坏的 v3 gzip 数据 - 拒绝`() {
        assertFailsWith<IllegalArgumentException> {
            QrPayload.decode("NULLCLASS3:" + String(ByteArray(64) { it.toByte() }, Charsets.ISO_8859_1))
        }
    }

    @Test
    fun `损坏的 v3 JSON - 拒绝`() {
        // 合法 gzip 包着一坨不是 JSON 的字节
        val badJson = ByteArrayOutputStream().use { out ->
            GZIPOutputStream(out).use { it.write("not json at all".toByteArray()) }
            out.toByteArray()
        }
        assertFailsWith<IllegalArgumentException> {
            QrPayload.decode("NULLCLASS3:" + String(badJson, Charsets.ISO_8859_1))
        }
    }

    @Test
    fun `v1 旧码仍可解码`() {
        val doc = smallDocument()
        val json = NullClassCodec.encode(doc)
        val gz = ByteArrayOutputStream().use { out ->
            GZIPOutputStream(out).use { it.write(json.toByteArray(Charsets.UTF_8)) }
            out.toByteArray()
        }
        val v1 = QrPayload.PREFIX_V1 + Base64.getUrlEncoder().withoutPadding().encodeToString(gz)
        assertEquals(doc, QrPayload.decode(v1))
    }

    @Test
    fun `v2 旧码仍可解码`() {
        // 复刻 v2 信封：ids 索引表（非 UUID 的 id 原样进表）+ 正文里全是指针
        val interned = ScheduleDocument(
            deviceId = "0",
            generatedAt = 42L,
            timetables = listOf(TimetableDto("1", "我的课表", 1, 1)),
            terms = listOf(
                TermDto("2", "2026-2027-1", 20671, 20, true, 1, 1, timetableId = "1"),
            ),
            courses = listOf(
                CourseDto("3", "2", "高数", teacher = "张三", colorIndex = 0, createdAt = 1, updatedAt = 1),
            ),
            blocks = listOf(
                BlockDto("4", "3", "2", 1, 20, "ALL", 1, 1, 2, "A101", 1, 1),
            ),
            periodTimes = listOf(
                PeriodTimeDto("2", 1, 480, 525, 0, 1),
            ),
        )
        val ids = listOf("qr-test", "tt1", "t1", "c1", "b1")
        val envelope = """{"ids":[""" + ids.joinToString(",") { "\"$it\"" } + """],"doc":""" +
            kotlinx.serialization.json.Json {
                prettyPrint = false
                ignoreUnknownKeys = true
                encodeDefaults = true
                explicitNulls = false
            }.encodeToString(ScheduleDocument.serializer(), interned) + "}"
        val gz = ByteArrayOutputStream().use { out ->
            GZIPOutputStream(out).use { it.write(envelope.toByteArray(Charsets.UTF_8)) }
            out.toByteArray()
        }
        val v2 = QrPayload.PREFIX_V2 + String(gz, Charsets.ISO_8859_1)
        assertEquals(smallDocument(), QrPayload.decode(v2))
    }

    @Test
    fun `超限课表 - 抛 PayloadTooLargeException`() {
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

    @Test
    fun `sliceForShare - 丢掉其他学期和墓碑`() {
        val full = typicalTermDocument(extraTerms = 2, withTombstones = true)
        val currentId = full.terms.first { it.isCurrent }.id
        val sliced = QrPayload.sliceForShare(full, currentId)
        assertEquals(1, sliced.terms.size)
        assertEquals(currentId, sliced.terms.single().id)
        assertEquals(1, sliced.timetables.size)
        assertEquals(16, sliced.courses.size)
        assertTrue(sliced.courses.none { it.deletedAt != null })
        assertEquals(32, sliced.blocks.size)
        assertEquals(12, sliced.periodTimes.size)
        assertEquals(2, sliced.exams.size)
    }

    @Test
    fun `sliceForShare - 学期不存在或已删则抛`() {
        val live = typicalTermDocument()
        assertFailsWith<IllegalArgumentException> { QrPayload.sliceForShare(live, "no-such") }
        val tombstoned = live.copy(terms = live.terms.map { it.copy(deletedAt = 9) })
        assertFailsWith<IllegalArgumentException> {
            QrPayload.sliceForShare(tombstoned, live.terms.single().id)
        }
    }

    @Test
    fun `典型学期随机 UUID 切片后负载约 1_4KB`() {
        repeat(10) { i ->
            val full = typicalTermDocument(extraTerms = 3, withTombstones = true)
            val sliced = QrPayload.sliceForShare(full, full.terms.first { it.isCurrent }.id)
            val payload = QrPayload.encode(sliced)
            // 带.UUID 表时实测 2319B / QR version 36；重铸后 ~1379B / version 27
            assertTrue(
                payload.length <= 1450,
                "典型学期切片后仍超限: ${payload.length} B (iteration $i)",
            )
            assertEquals(contentOf(sliced), contentOf(QrPayload.decode(payload)))
        }
    }

    @Test
    fun `全量三学期 dump 超限或明显大于切片`() {
        val full = typicalTermDocument(extraTerms = 2)
        val sliced = QrPayload.sliceForShare(full, full.terms.first { it.isCurrent }.id)
        val slicedLen = QrPayload.encode(sliced).length
        try {
            val fullLen = QrPayload.encode(full).length
            assertTrue(fullLen > slicedLen, "全量 $fullLen B 应大于切片 $slicedLen B")
        } catch (_: QrPayload.PayloadTooLargeException) {
            // 超限也说明必须切片
        }
    }

    @Test
    fun `zxing 能编码典型学期负载`() {
        val full = typicalTermDocument()
        val sliced = QrPayload.sliceForShare(full, full.terms.single().id)
        val payload = QrPayload.encode(sliced)
        val qr = com.google.zxing.qrcode.encoder.Encoder.encode(
            payload,
            ErrorCorrectionLevel.L,
            mapOf(EncodeHintType.CHARACTER_SET to "ISO-8859-1"),
        )
        // version 27 = 129×129 模块（此前带 UUID 表是 36 = 165×165）
        assertTrue(qr.version.versionNumber <= 28, "version=${qr.version.versionNumber}")
        val matrix = QRCodeWriter().encode(
            payload,
            BarcodeFormat.QR_CODE,
            256,
            256,
            mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
                EncodeHintType.CHARACTER_SET to "ISO-8859-1",
            ),
        )
        assertTrue(matrix.width > 0 && matrix.height > 0)
    }
}
