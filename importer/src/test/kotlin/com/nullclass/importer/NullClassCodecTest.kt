package com.nullclass.importer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NullClassCodecTest {

    private fun document(
        deviceId: String = "device-A",
        tombstone: Long? = null,
        formatVersion: Int = ScheduleDocument.FORMAT_VERSION,
    ) = ScheduleDocument(
        formatVersion = formatVersion,
        deviceId = deviceId,
        generatedAt = 1_000L,
        terms = listOf(
            TermDto(
                id = "t1", name = "2026-2027-1", firstDayEpochDay = 20671, totalWeeks = 20,
                isCurrent = true, createdAt = 1L, updatedAt = 2L, deletedAt = tombstone,
            ),
        ),
        courses = listOf(
            CourseDto(
                id = "c1", termId = "t1", name = "高数", teacher = "张三", note = null,
                colorIndex = 3, createdAt = 1L, updatedAt = 2L, deletedAt = tombstone,
            ),
        ),
        blocks = listOf(
            BlockDto(
                id = "b1", courseId = "c1", termId = "t1", startWeek = 1, endWeek = 20,
                weekType = "ODD", dayOfWeek = 3, startPeriod = 1, endPeriod = 2,
                location = "A101", createdAt = 1L, updatedAt = 2L, deletedAt = tombstone,
            ),
        ),
        periodTimes = listOf(
            PeriodTimeDto("t1", 1, 480, 525, 0, updatedAt = 2L),
        ),
    )

    @Test
    fun `round-trip 保真（含墓碑与审计字段）`() {
        val doc = document(tombstone = 99L)
        val decoded = NullClassCodec.decode(NullClassCodec.encode(doc))
        assertEquals(doc, decoded)
        assertEquals(99L, decoded.terms[0].deletedAt)
        assertEquals(99L, decoded.courses[0].deletedAt)
        assertEquals(99L, decoded.blocks[0].deletedAt)
    }

    @Test
    fun `encodeDefaults - 默认字段显式写出（旧版本可读、字节级稳定）`() {
        val raw = NullClassCodec.encode(document())
        // deletedAt=null、note=null、teacher 由 encodeDefaults 显式写出
        assertTrue("\"deletedAt\":null" in raw, "deletedAt 应显式为 null：$raw")
        assertTrue("\"note\":null" in raw)
    }

    @Test
    fun `formatVersion 高于本版本 - 拒绝并给出可读错误`() {
        val raw = NullClassCodec.encode(document(formatVersion = 99))
        val e = assertFailsWith<NullClassCodec.FutureVersionException> { NullClassCodec.decode(raw) }
        assertEquals(99, e.fileVersion)
    }

    @Test
    fun `formatVersion 低于本版本 - 接受（向后兼容）`() {
        val raw = NullClassCodec.encode(document(formatVersion = 1))
        assertEquals(1, NullClassCodec.decode(raw).formatVersion)
    }

    @Test
    fun `未知字段 - 忽略不报错（向前兼容）`() {
        val raw = NullClassCodec.encode(document()).let {
            // 在 JSON 顶层插入一个未来版本可能新增的字段
            it.replaceFirst("{", """{"futureField":"x",""")
        }
        assertEquals("device-A", NullClassCodec.decode(raw).deviceId)
    }

    @Test
    fun `课表字段 round-trip 保真`() {
        val doc = document().copy(
            timetables = listOf(
                TimetableDto(id = "tt-1", name = "我的课表", createdAt = 1, updatedAt = 2),
                TimetableDto(id = "tt-2", name = "弟弟的课表", createdAt = 3, updatedAt = 4, deletedAt = 5),
            ),
            terms = document().terms.map { it.copy(timetableId = "tt-1") },
        )
        val decoded = NullClassCodec.decode(NullClassCodec.encode(doc))
        assertEquals(doc, decoded)
        assertEquals("tt-1", decoded.terms[0].timetableId)
    }

    @Test
    fun `旧版本快照没有课表字段 - 照常读入，归属为空`() {
        // 0.8.1 及更早写出的文件：没有 timetables、没有 timetableId（SyncEngine 负责解析归属）
        val legacy = NullClassCodec.encode(document())
            .replace("\"timetables\":[],", "")
            .replace("\"timetableId\":\"\",", "")
        assertEquals(2, NullClassCodec.decode(legacy).formatVersion)
        val decoded = NullClassCodec.decode(legacy)
        assertEquals(emptyList(), decoded.timetables)
        assertEquals("", decoded.terms[0].timetableId)
    }

    @Test
    fun `非 JSON 内容 - 抛出可读错误`() {
        assertFailsWith<IllegalArgumentException> { NullClassCodec.decode("not a json") }
        assertFailsWith<IllegalArgumentException> { NullClassCodec.decode("{\"hello\":1}") }
    }
}
