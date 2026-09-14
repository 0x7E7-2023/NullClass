package com.nullclass.sync

import com.nullclass.importer.BlockDto
import com.nullclass.importer.CourseDto
import com.nullclass.importer.PeriodTimeDto
import com.nullclass.importer.ScheduleDocument
import com.nullclass.importer.TermDto
import com.nullclass.importer.TimetableDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImportAlignerTest {

    private val now = 2_000_000L

    private fun term(
        id: String,
        name: String = "2026-2027学年1学期",
        isCurrent: Boolean = false,
        updatedAt: Long = 1,
        deletedAt: Long? = null,
        timetableId: String = "tt",
        firstDayEpochDay: Long = 20696,
        totalWeeks: Int = 22,
    ) = TermDto(
        id = id, name = name, firstDayEpochDay = firstDayEpochDay, totalWeeks = totalWeeks,
        isCurrent = isCurrent, createdAt = 1, updatedAt = updatedAt, deletedAt = deletedAt,
        timetableId = timetableId,
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
        // 本地库 dump 出来的文档一定带课表；教务导入落在当前课表 tt 上
        timetables: List<TimetableDto> = listOf(TimetableDto("tt", "我的课表", createdAt = 1, updatedAt = 1)),
    ) = ScheduleDocument(
        deviceId = deviceId, generatedAt = now,
        timetables = timetables,
        terms = terms, courses = courses, blocks = blocks,
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

        val aligned = ImportAligner.align(local, incoming, now, targetTimetableId = "tt")

        assertEquals("t1", aligned.incoming.terms.single().id)
        assertEquals("c1", aligned.incoming.courses.single().id)
        assertEquals("b1", aligned.incoming.blocks.single().id)
        // 教务导入 = 开始用这份课表：同一学期的「一键刷新」也把当前学期标记落在这个学期上
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

        val aligned = ImportAligner.align(local, incoming, now, targetTimetableId = "tt")

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

        val aligned = ImportAligner.align(local, incoming, now, targetTimetableId = "tt")

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

        val aligned = ImportAligner.align(local, incoming, now, targetTimetableId = "tt")

        assertNull(aligned.local.courses.single { it.id == "c1" }.deletedAt, "对齐到的课程不该被作废")
        assertNotNull(aligned.local.courses.single { it.id == "c2" }.deletedAt, "消失的课程应作废")
        assertNotNull(aligned.local.blocks.single { it.id == "b2" }.deletedAt, "消失的课块应作废")
    }

    @Test
    fun `ID 相同的导入 - 课程课块原样，学期内容取这次导入的`() {
        val local = doc(
            terms = listOf(term("t1")),
            courses = listOf(course("c1", "t1")),
            blocks = listOf(block("b1", "c1", "t1")),
        )
        val incoming = doc(
            terms = listOf(term("t1", updatedAt = now, totalWeeks = 20)),
            courses = listOf(course("c1", "t1", updatedAt = now)),
            blocks = listOf(block("b1", "c1", "t1", updatedAt = now)),
        )

        val aligned = ImportAligner.align(local, incoming, now, targetTimetableId = "tt")

        // 课程、课块一个字节不改
        assertEquals(local.courses, aligned.local.courses)
        assertEquals(local.blocks, aligned.local.blocks)
        assertEquals(incoming.courses, aligned.incoming.courses)
        assertEquals(incoming.blocks, aligned.incoming.blocks)
        // 学期原地激活：本地那份不动（内容取导入侧），导入那份标成当前并推到 now
        assertEquals(local.terms, aligned.local.terms)
        assertEquals(
            incoming.terms.single().copy(isCurrent = true, updatedAt = now),
            aligned.incoming.terms.single(),
        )
    }

    @Test
    fun `没有同名学期时不复制一份 - 新学期成为当前学期，同课表的旧学期让位`() {
        val local = doc(terms = listOf(term("t1", name = "2025-2026学年2学期", isCurrent = true)))
        val incoming = doc(terms = listOf(term("t2")))

        val aligned = ImportAligner.align(local, incoming, now, targetTimetableId = "tt")
        val merged = SyncEngine.merge(aligned.local, aligned.incoming, now, activeTimetableId = "tt")

        assertEquals(2, merged.terms.count { it.deletedAt == null }, "没有同名学期，不该被合并掉")
        assertEquals(listOf("t2"), merged.terms.filter { it.isCurrent }.map { it.id })
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

        val aligned = ImportAligner.align(local, incoming, now, targetTimetableId = "tt")

        assertNull(aligned.local.courses.single { it.id == "c9" }.deletedAt, "别的学期的课程不该被作废")
        assertNull(aligned.local.blocks.single { it.id == "b9" }.deletedAt)
    }

    @Test
    fun `同名学期不跨课表对齐 - 别的课表里的同名学期不是同一个`() {
        // 本地「弟弟的课表」里有个同名学期；教务导入目标是当前课表 tt，不该被认成同一个
        val local = doc(
            terms = listOf(term("t9", timetableId = "sibling")),
            courses = listOf(course("c9", "t9")),
            blocks = listOf(block("b9", "c9", "t9")),
        )
        val incoming = doc(
            terms = listOf(term("t2")),
            courses = listOf(course("c2", "t2")),
            blocks = listOf(block("b2", "c2", "t2")),
        )

        val aligned = ImportAligner.align(local, incoming, now, targetTimetableId = "tt")

        // 没有对齐：新学期用自己的 ID，落在目标课表；别的课表的记录一个没动
        assertEquals("t2", aligned.incoming.terms.single().id)
        assertEquals("tt", aligned.incoming.terms.single().timetableId)
        assertEquals(local, aligned.local)
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

        val aligned = ImportAligner.align(local, incoming, now, targetTimetableId = "tt")

        assertEquals(local, aligned.local)
        assertEquals(incoming, aligned.incoming)
        assertNull(local.courses.single { it.id == "c2" }.deletedAt, "备份导入不该作废本地记录")
    }

    @Test
    fun `教务导入新学期 - 合并后新导入的学期成为当前学期`() {
        // 上一学期导入的学期还挂着当前标记，这次导入的是重新命名的下一学期
        val local = doc(terms = listOf(term("t1", isCurrent = true)))
        val incoming = doc(terms = listOf(term("t2", name = "2026-2027学年2学期", firstDayEpochDay = 20800)))

        val aligned = ImportAligner.align(local, incoming, now, targetTimetableId = "tt")
        val merged = SyncEngine.merge(aligned.local, aligned.incoming, now, activeTimetableId = "tt")

        assertEquals(
            listOf("t2"),
            merged.terms.filter { it.isCurrent }.map { it.id },
            "导入新学期后当前学期还是旧的，课表页会继续显示上一学期",
        )
    }

    @Test
    fun `教务导入多个学期 - 开学日最晚的那个成为当前学期`() {
        // 适配器一次带回多个学期（补历史学期 / 混合学期）：不能按数组顺序赌
        val local = doc(terms = listOf(term("t1", isCurrent = true, firstDayEpochDay = 19000)))
        val incoming = doc(
            terms = listOf(
                term("tNew", name = "2026-2027学年2学期", firstDayEpochDay = 20800),
                term("tOld", name = "2025-2026学年2学期", firstDayEpochDay = 19800),
            ),
        )

        val aligned = ImportAligner.align(local, incoming, now, targetTimetableId = "tt")
        val merged = SyncEngine.merge(aligned.local, aligned.incoming, now, activeTimetableId = "tt")

        val current = merged.terms.filter { it.isCurrent }.map { it.id }
        assertEquals(listOf("tNew"), current)
    }

    @Test
    fun `教务刷新当前学期 - 标记留在同一学期，教务纠正的开学日写得进去`() {
        // 「一键刷新」：新数据认领的就是本地这个学期，开学日被教务纠正（同一周里差 3 天）。
        // 本地那份是上次导入写的（时间戳更旧），导入侧提到 now 才有资格赢下 LWW。
        val local = doc(terms = listOf(term("t1", isCurrent = true, firstDayEpochDay = 20693, updatedAt = 1000)))
        val incoming = doc(terms = listOf(term("t2", firstDayEpochDay = 20696, updatedAt = now - 5000)))

        val aligned = ImportAligner.align(local, incoming, now, targetTimetableId = "tt")
        val merged = SyncEngine.merge(aligned.local, aligned.incoming, now, activeTimetableId = "tt")

        assertEquals(1, merged.terms.size, "同一学期被复制了：${merged.terms.map { it.id }}")
        assertTrue(merged.terms.single().isCurrent, "刷新后当前学期标记丢了")
        assertEquals(20696, merged.terms.single().firstDayEpochDay, "教务纠正的开学日没写进本地")
        // 本地那份一个字节不动：一 bump 时间戳，mergeByKey 相等取本地就把导入内容挡在外面
        assertEquals(local, aligned.local)
    }

    @Test
    fun `教务导入只动当前课表的当前学期 - 别的课表的标记不碰`() {
        val local = doc(
            terms = listOf(
                // mine 的时间戳与导入同刻：让位只能靠 activate 按课表清，不能靠归一化的时间戳
                term("mine", isCurrent = true, name = "2025-2026学年2学期", updatedAt = now),
                term("sis", isCurrent = true, name = "弟弟的学期", timetableId = "sibling"),
            ),
            timetables = listOf(
                TimetableDto("tt", "我的课表", createdAt = 1, updatedAt = 1),
                TimetableDto("sibling", "弟弟的课表", createdAt = 2, updatedAt = 2),
            ),
        )
        val incoming = doc(terms = listOf(term("t2", name = "2026-2027学年1学期", firstDayEpochDay = 20800)))

        val aligned = ImportAligner.align(local, incoming, now, targetTimetableId = "tt")

        // 让位必须是 activate 里按课表做的：此时本地旧学期的时间戳与导入同刻，归一化帮不上忙
        assertFalse(
            aligned.local.terms.single { it.id == "mine" }.isCurrent,
            "同课表里上一次导入的学期没让位",
        )
        assertEquals(true, aligned.local.terms.single { it.id == "sis" }.isCurrent, "别的课表的不该动")

        val merged = SyncEngine.merge(aligned.local, aligned.incoming, now, activeTimetableId = "tt")

        assertEquals(setOf("t2", "sis"), merged.terms.filter { it.isCurrent }.map { it.id }.toSet())
    }

    @Test
    fun `WakeUp 不是教务 - 当前学期标记不由导入对齐决定`() {
        // WakeUp 走 UI 层按学期 ID 单独激活；ImportAligner 不该替它清掉旧学期的标记
        val local = doc(terms = listOf(term("t1", isCurrent = true)))
        val incoming = doc(
            deviceId = "wakeup-import",
            terms = listOf(term("t2", name = "2026-2027学年2学期", firstDayEpochDay = 20800)),
        )

        val aligned = ImportAligner.align(local, incoming, now, targetTimetableId = "tt")

        assertEquals(true, aligned.local.terms.single { it.id == "t1" }.isCurrent)
        assertEquals(false, aligned.incoming.terms.single().isCurrent)
    }

    @Test
    fun `本地还没有学期时导入教务课表 - 新学期直接成为当前学期`() {
        // 新建的空课表：没有可对齐的同名学期，也不能靠归一化（本地一个学期都没有）
        val local = doc(terms = emptyList())
        val incoming = doc(terms = listOf(term("t2", name = "2026-2027学年1学期")))

        val aligned = ImportAligner.align(local, incoming, now, targetTimetableId = "tt")
        val merged = SyncEngine.merge(aligned.local, aligned.incoming, now, activeTimetableId = "tt")

        assertEquals(listOf("t2"), merged.terms.filter { it.isCurrent }.map { it.id })
    }
}
