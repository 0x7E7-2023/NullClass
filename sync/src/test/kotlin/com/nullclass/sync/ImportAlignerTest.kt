package com.nullclass.sync

import com.nullclass.importer.BlockDto
import com.nullclass.importer.CourseDto
import com.nullclass.importer.PeriodTimeDto
import com.nullclass.importer.ScheduleDocument
import com.nullclass.importer.TermDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ImportAlignerTest {

    private val now = 2_000_000L

    private fun term(
        id: String,
        name: String = "2026-2027学年1学期",
        isCurrent: Boolean = false,
        updatedAt: Long = 1,
        deletedAt: Long? = null,
    ) = TermDto(
        id = id, name = name, firstDayEpochDay = 20696, totalWeeks = 22,
        isCurrent = isCurrent, createdAt = 1, updatedAt = updatedAt, deletedAt = deletedAt,
    )

    private fun course(
        id: String,
        termId: String,
        name: String = "高等数学A（一）",
        teacher: String? = "么会丽",
        note: String? = null,
        colorIndex: Int = 0,
        updatedAt: Long = 1,
        deletedAt: Long? = null,
    ) = CourseDto(
        id = id, termId = termId, name = name, teacher = teacher, note = note,
        colorIndex = colorIndex, createdAt = 1, updatedAt = updatedAt, deletedAt = deletedAt,
    )

    private fun block(
        id: String,
        courseId: String,
        termId: String,
        dayOfWeek: Int = 1,
        startPeriod: Int = 1,
        endPeriod: Int = 2,
        startWeek: Int = 1,
        endWeek: Int = 16,
        weekType: String = "ALL",
        location: String? = "晨曦楼103",
        updatedAt: Long = 1,
        deletedAt: Long? = null,
    ) = BlockDto(
        id = id, courseId = courseId, termId = termId, startWeek = startWeek, endWeek = endWeek,
        weekType = weekType, dayOfWeek = dayOfWeek, startPeriod = startPeriod, endPeriod = endPeriod,
        location = location, createdAt = 1, updatedAt = updatedAt, deletedAt = deletedAt,
    )

    private fun period(termId: String, periodIndex: Int = 1, updatedAt: Long = 1) = PeriodTimeDto(
        termId = termId, periodIndex = periodIndex, startMinuteOfDay = 490, endMinuteOfDay = 535,
        session = 0, updatedAt = updatedAt,
    )

    private fun doc(
        terms: List<TermDto>,
        courses: List<CourseDto> = emptyList(),
        blocks: List<BlockDto> = emptyList(),
        periodTimes: List<PeriodTimeDto> = emptyList(),
        deviceId: String = "jw-dlutci",
    ) = ScheduleDocument(
        deviceId = deviceId, generatedAt = now, terms = terms, courses = courses, blocks = blocks,
        periodTimes = periodTimes,
    )

    @Test
    fun `同名学期且 ID 不同时复用本地 ID，合并后不再复制一份`() {
        val local = doc(
            terms = listOf(term("t1", isCurrent = true)),
            courses = listOf(course("c1", "t1")),
            blocks = listOf(block("b1", "c1", "t1")),
        )
        val incoming = doc(
            terms = listOf(term("t2")),
            courses = listOf(course("c2", "t2")),
            blocks = listOf(block("b2", "c2", "t2")),
        )

        val aligned = ImportAligner.align(local, incoming, now)

        assertEquals("t1", aligned.incoming.terms.single().id)
        assertEquals("c1", aligned.incoming.courses.single().id)
        assertEquals("b1", aligned.incoming.blocks.single().id)
        // 刷新不该把当前学期标记弄丢
        assertEquals(true, aligned.incoming.terms.single().isCurrent)

        val merged = SyncEngine.merge(aligned.local, aligned.incoming, now)
        assertEquals(1, merged.terms.size, "学期被复制了：${merged.terms.map { it.id }}")
        assertEquals(1, merged.courses.size, "课程被复制了")
        assertEquals(1, merged.blocks.size, "课块被复制了")
    }

    @Test
    fun `节次表的 termId 随学期重映射，刷新不清空节次时间`() {
        val local = doc(
            terms = listOf(term("t1", updatedAt = 1)),
            periodTimes = listOf(period("t1"), period("t1", periodIndex = 2)),
        )
        val incoming = doc(
            // 导入时刻比本地新，mergePeriodTimes 会判给 remote 侧
            terms = listOf(term("t2", updatedAt = now)),
            periodTimes = listOf(period("t2"), period("t2", periodIndex = 2)),
        )

        val aligned = ImportAligner.align(local, incoming, now)

        assertEquals("t1", aligned.incoming.periodTimes[0].termId)
        assertEquals("t1", aligned.incoming.periodTimes[1].termId)

        val merged = SyncEngine.merge(aligned.local, aligned.incoming, now)
        assertEquals(2, merged.periodTimes.count { it.termId == "t1" }, "对齐学期的节次时间被清空")
    }

    @Test
    fun `学校改了教室：旧课块作废，课程 ID 与用户改过的备注颜色保留`() {
        val local = doc(
            terms = listOf(term("t1")),
            courses = listOf(course("c1", "t1", note = "带计算器", colorIndex = 7)),
            blocks = listOf(block("b1", "c1", "t1", location = "晨曦楼103")),
        )
        val incoming = doc(
            terms = listOf(term("t2")),
            courses = listOf(course("c2", "t2")),
            blocks = listOf(block("b2", "c2", "t2", location = "汇智楼307")),
        )

        val aligned = ImportAligner.align(local, incoming, now)

        val keptCourse = aligned.incoming.courses.single()
        assertEquals("c1", keptCourse.id)
        assertEquals("带计算器", keptCourse.note)
        assertEquals(7, keptCourse.colorIndex)
        // 旧课块打墓碑，新课块用自己的 ID
        assertEquals(now, aligned.local.blocks.single { it.id == "b1" }.deletedAt)
        assertEquals("b2", aligned.incoming.blocks.single().id)

        val merged = SyncEngine.merge(aligned.local, aligned.incoming, now)
        assertEquals(1, merged.blocks.count { it.deletedAt == null }, "应只剩一条有效课块")
    }

    @Test
    fun `新数据里没有的课程会被作废`() {
        val local = doc(
            terms = listOf(term("t1")),
            courses = listOf(course("c1", "t1"), course("c2", "t1", name = "离散数学")),
            blocks = listOf(block("b1", "c1", "t1"), block("b2", "c2", "t1")),
        )
        val incoming = doc(
            terms = listOf(term("t2")),
            courses = listOf(course("c3", "t2")),
            blocks = listOf(block("b3", "c3", "t2")),
        )

        val aligned = ImportAligner.align(local, incoming, now)

        assertNull(aligned.local.courses.single { it.id == "c1" }.deletedAt, "对齐到的课程不该被作废")
        assertNotNull(aligned.local.courses.single { it.id == "c2" }.deletedAt, "消失的课程应作废")
        assertNotNull(aligned.local.blocks.single { it.id == "b2" }.deletedAt, "消失的课块应作废")
    }

    @Test
    fun `ID 相同的导入原样返回，不动本地记录`() {
        val local = doc(
            terms = listOf(term("t1")),
            courses = listOf(course("c1", "t1")),
            blocks = listOf(block("b1", "c1", "t1")),
        )
        val incoming = doc(
            terms = listOf(term("t1", updatedAt = now)),
            courses = listOf(course("c1", "t1", updatedAt = now)),
            blocks = listOf(block("b1", "c1", "t1", updatedAt = now)),
        )

        val aligned = ImportAligner.align(local, incoming, now)

        assertEquals(local, aligned.local)
        assertEquals(incoming, aligned.incoming)
    }

    @Test
    fun `没有同名学期时原样返回`() {
        val local = doc(terms = listOf(term("t1", name = "2025-2026学年2学期")))
        val incoming = doc(terms = listOf(term("t2")))

        val aligned = ImportAligner.align(local, incoming, now)

        assertEquals(local, aligned.local)
        assertEquals(incoming, aligned.incoming)
    }

    @Test
    fun `未被对齐的学期不受影响`() {
        val local = doc(
            terms = listOf(term("t1"), term("t9", name = "2025-2026学年2学期")),
            courses = listOf(course("c9", "t9", name = "大学物理")),
            blocks = listOf(block("b9", "c9", "t9")),
        )
        val incoming = doc(
            terms = listOf(term("t2")),
            courses = listOf(course("c2", "t2")),
            blocks = listOf(block("b2", "c2", "t2")),
        )

        val aligned = ImportAligner.align(local, incoming, now)

        assertNull(aligned.local.courses.single { it.id == "c9" }.deletedAt, "别的学期的课程不该被作废")
        assertNull(aligned.local.blocks.single { it.id == "b9" }.deletedAt)
    }

    @Test
    fun `备份等沿用原 ID 的来源不做对齐，本地多出的记录不会被作废`() {
        // 跨设备备份：学期同名但 ID 不同（各自导入时生成的），导入它不该删本地多出的记录
        val local = doc(
            terms = listOf(term("t1")),
            courses = listOf(course("c1", "t1"), course("c2", "t1", name = "离散数学")),
            blocks = listOf(block("b1", "c1", "t1"), block("b2", "c2", "t1")),
        )
        val incoming = doc(
            deviceId = "device-000",
            terms = listOf(term("t2")),
            courses = listOf(course("c3", "t2")),
            blocks = listOf(block("b3", "c3", "t2")),
        )

        val aligned = ImportAligner.align(local, incoming, now)

        assertEquals(local, aligned.local)
        assertEquals(incoming, aligned.incoming)
        assertNull(local.courses.single { it.id == "c2" }.deletedAt, "备份导入不该作废本地记录")
    }
}
