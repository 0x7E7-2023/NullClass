package com.nullclass.sync

import com.nullclass.importer.BlockDto
import com.nullclass.importer.CourseDto
import com.nullclass.importer.ManifestDto
import com.nullclass.importer.PeriodTimeDto
import com.nullclass.core.model.DefaultTimetable
import com.nullclass.importer.ScheduleDocument
import com.nullclass.importer.TermDto
import com.nullclass.importer.TimetableDto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncEngineTest {

    private val now = 1_000_000L

    private fun term(
        id: String,
        updatedAt: Long,
        isCurrent: Boolean = false,
        deletedAt: Long? = null,
        firstDayEpochDay: Long = 20000,
        timetableId: String = "",
    ) = TermDto(
        id = id, name = "term-$id", firstDayEpochDay = firstDayEpochDay, totalWeeks = 20,
        isCurrent = isCurrent, createdAt = 1, updatedAt = updatedAt, deletedAt = deletedAt,
        timetableId = timetableId,
    )

    private fun course(id: String, updatedAt: Long, deletedAt: Long? = null, name: String = "c-$id") =
        CourseDto(
            id = id, termId = "t1", name = name, colorIndex = 0,
            createdAt = 1, updatedAt = updatedAt, deletedAt = deletedAt,
        )

    private fun snapshot(
        deviceId: String = "local",
        terms: List<TermDto> = emptyList(),
        courses: List<CourseDto> = emptyList(),
        blocks: List<BlockDto> = emptyList(),
        periodTimes: List<PeriodTimeDto> = emptyList(),
        timetables: List<TimetableDto> = emptyList(),
    ) = ScheduleDocument(
        deviceId = deviceId, generatedAt = now,
        timetables = timetables, terms = terms, courses = courses, blocks = blocks,
        periodTimes = periodTimes,
    )

    private fun timetable(
        id: String,
        name: String = "tt-" + id,
        updatedAt: Long = 1,
        deletedAt: Long? = null,
        createdAt: Long = 1,
    ) = TimetableDto(
        id = id, name = name, createdAt = createdAt, updatedAt = updatedAt, deletedAt = deletedAt,
    )

    @Test
    fun `只存在一侧的记录被采用`() {
        val local = snapshot(courses = listOf(course("a", 10)))
        val remote = snapshot(deviceId = "remote", courses = listOf(course("b", 10)))

        val merged = SyncEngine.merge(local, remote, now)

        assertEquals(setOf("a", "b"), merged.courses.map { it.id }.toSet())
    }

    @Test
    fun `updatedAt 新者胜 双向`() {
        val oldLocal = course("a", 10, name = "old")
        val newRemote = course("a", 20, name = "new")
        val merged1 = SyncEngine.merge(snapshot(courses = listOf(oldLocal)), snapshot(deviceId = "r", courses = listOf(newRemote)), now)
        assertEquals("new", merged1.courses.single().name)

        val newLocal = course("a", 30, name = "newest")
        val merged2 = SyncEngine.merge(snapshot(courses = listOf(newLocal)), snapshot(deviceId = "r", courses = listOf(newRemote)), now)
        assertEquals("newest", merged2.courses.single().name)
    }

    @Test
    fun `updatedAt 相等取本地 保证确定性`() {
        val local = course("a", 10, name = "local-ver")
        val remote = course("a", 10, name = "remote-ver")

        val merged = SyncEngine.merge(snapshot(courses = listOf(local)), snapshot(deviceId = "r", courses = listOf(remote)), now)

        assertEquals("local-ver", merged.courses.single().name)
    }

    @Test
    fun `删除传播 远端墓碑覆盖本地旧记录`() {
        val alive = course("a", 10)
        val tombstone = course("a", 20, deletedAt = 20)

        val merged = SyncEngine.merge(snapshot(courses = listOf(alive)), snapshot(deviceId = "r", courses = listOf(tombstone)), now)

        assertNotNull(merged.courses.single().deletedAt)
    }

    @Test
    fun `删除传播 本地更新的记录复活覆盖旧墓碑`() {
        val local = course("a", 30, name = "restored")
        val remote = course("a", 20, deletedAt = 20)

        val merged = SyncEngine.merge(snapshot(courses = listOf(local)), snapshot(deviceId = "r", courses = listOf(remote)), now)

        assertNull(merged.courses.single().deletedAt)
        assertEquals("restored", merged.courses.single().name)
    }

    @Test
    fun `节次表随学期整体取新 学期新的一方整组胜出含删除`() {
        val local = snapshot(
            terms = listOf(term("t1", 10)),
            periodTimes = listOf(
                PeriodTimeDto("t1", 1, 480, 525, 0, 10),
                PeriodTimeDto("t1", 2, 535, 580, 0, 10),
            ),
        )
        // 远端编辑过学期（updatedAt 更新）且节次删到只剩 1 节
        val remote = snapshot(
            deviceId = "r",
            terms = listOf(term("t1", 20)),
            periodTimes = listOf(PeriodTimeDto("t1", 1, 480, 530, 0, 20)),
        )

        val merged = SyncEngine.merge(local, remote, now)

        assertEquals(1, merged.periodTimes.size)
        assertEquals(530, merged.periodTimes.single().endMinuteOfDay)
    }

    @Test
    fun `学期旧的一方其节次表保留本地`() {
        val local = snapshot(
            terms = listOf(term("t1", 30)),
            periodTimes = listOf(PeriodTimeDto("t1", 1, 480, 525, 0, 30)),
        )
        val remote = snapshot(
            deviceId = "r",
            terms = listOf(term("t1", 20)),
            periodTimes = listOf(
                PeriodTimeDto("t1", 1, 480, 530, 0, 20),
                PeriodTimeDto("t1", 2, 535, 580, 0, 20),
            ),
        )

        val merged = SyncEngine.merge(local, remote, now)

        assertEquals(1, merged.periodTimes.size)
        assertEquals(525, merged.periodTimes.single().endMinuteOfDay)
    }

    @Test
    fun `学期只在一侧时节次表跟随该侧`() {
        val local = snapshot(
            terms = listOf(term("t1", 10)),
            periodTimes = listOf(PeriodTimeDto("t1", 1, 480, 525, 0, 10)),
        )
        val remote = snapshot(deviceId = "r") // 远端完全没有该学期

        val merged = SyncEngine.merge(local, remote, now)

        assertEquals(1, merged.periodTimes.size)
        assertEquals("t1", merged.periodTimes.single().termId)
    }

    @Test
    fun `多个当前学期只保留最新且清除者传播`() {
        val local = snapshot(terms = listOf(term("a", 100, isCurrent = true)))
        val remote = snapshot(
            deviceId = "r",
            terms = listOf(term("b", 200, isCurrent = true), term("a", 100, isCurrent = true)),
        )

        val merged = SyncEngine.merge(local, remote, now)

        val currents = merged.terms.filter { it.isCurrent }
        assertEquals(1, currents.size)
        assertEquals("b", currents.single().id)
        // 被清除的 a 应 bump updatedAt 让其他设备收敛
        assertTrue(merged.terms.first { it.id == "a" }.updatedAt == now)
    }

    @Test
    fun `无当前学期时激活开学日最新`() {
        val terms = listOf(term("old", 10, firstDayEpochDay = 19000), term("new", 10, firstDayEpochDay = 20000))

        val merged = SyncEngine.merge(snapshot(terms = terms), snapshot(deviceId = "r"), now)

        val current = merged.terms.single { it.isCurrent }
        assertEquals("new", current.id)
    }

    @Test
    fun `课表记录 LWW - 只在一侧采用，删除传播`() {
        val local = snapshot(timetables = listOf(timetable("A", updatedAt = 10)))
        val remote = snapshot(deviceId = "r", timetables = listOf(timetable("B", updatedAt = 10)))

        val merged = SyncEngine.merge(local, remote, now)
        assertEquals(setOf("A", "B"), merged.timetables.map { it.id }.toSet())

        // 远端墓碑更新 → 删除传播
        val tombstoned = snapshot(
            deviceId = "r",
            timetables = listOf(timetable("A", updatedAt = 20, deletedAt = 20)),
        )
        val merged2 = SyncEngine.merge(snapshot(timetables = listOf(timetable("A", updatedAt = 10))), tombstoned, now)
        assertNotNull(merged2.timetables.single { it.id == "A" }.deletedAt)
    }

    @Test
    fun `当前学期归一化按课表分组 - 各课表各留一个`() {
        val local = snapshot(
            timetables = listOf(timetable("A"), timetable("B")),
            terms = listOf(term("a1", 100, isCurrent = true, timetableId = "A")),
        )
        val remote = snapshot(
            deviceId = "r",
            timetables = listOf(timetable("A"), timetable("B")),
            terms = listOf(
                // B 课表自己的当前学期不该被 A 课表的归一化清掉
                term("b1", 200, isCurrent = true, timetableId = "B"),
                // A 课表出现第二个当前学期（updatedAt 更新）
                term("a2", 300, isCurrent = true, timetableId = "A"),
            ),
        )

        val merged = SyncEngine.merge(local, remote, now)

        val currentByTimetable = merged.terms.filter { it.isCurrent }.associate { it.timetableId to it.id }
        assertEquals(mapOf("A" to "a2", "B" to "b1"), currentByTimetable)
        // 被清掉的 a1 bump updatedAt 收敛到其他设备
        assertEquals(now, merged.terms.first { it.id == "a1" }.updatedAt)
    }

    @Test
    fun `旧版本快照的空归属沿用本地同 id 学期的课表`() {
        // 旧版本（不认识课表）写回的学期记录：timetableId 为空
        val legacyRemoteTerm = TermDto(
            id = "t1", name = "term-t1", firstDayEpochDay = 20000, totalWeeks = 20,
            isCurrent = true, createdAt = 1, updatedAt = 50,
        )
        val local = snapshot(
            timetables = listOf(timetable("A"), timetable("B")),
            terms = listOf(term("t1", 10, timetableId = "B")),
        )
        val remote = snapshot(deviceId = "r", terms = listOf(legacyRemoteTerm))

        val merged = SyncEngine.merge(local, remote, now, activeTimetableId = "A")

        // 远端记录更新（50 > 10）赢了内容，但归属沿用本地的 B，而不是落到当前课表 A
        assertEquals("B", merged.terms.single { it.id == "t1" }.timetableId)
    }

    @Test
    fun `旧版本快照的新学期落到当前课表`() {
        val legacyRemoteTerm = TermDto(
            id = "t9", name = "term-t9", firstDayEpochDay = 20000, totalWeeks = 20,
            isCurrent = false, createdAt = 1, updatedAt = 1,
        )
        val local = snapshot(
            timetables = listOf(timetable("A"), timetable("B")),
            terms = listOf(term("t1", 10, timetableId = "A")),
        )
        val remote = snapshot(deviceId = "r", terms = listOf(legacyRemoteTerm))

        val merged = SyncEngine.merge(local, remote, now, activeTimetableId = "A")

        assertEquals("A", merged.terms.single { it.id == "t9" }.timetableId)
    }

    @Test
    fun `孤儿学期改挂目标课表 - 数据不能无家可归`() {
        // 对端删了课表 B，但 B 里 t1 的记录本地更新过（活着的）
        val local = snapshot(
            timetables = listOf(timetable("A"), timetable("B")),
            terms = listOf(term("t1", 50, timetableId = "B")),
        )
        val remote = snapshot(
            deviceId = "r",
            timetables = listOf(timetable("A"), timetable("B", updatedAt = 100, deletedAt = 100)),
            terms = listOf(term("t1", 10, timetableId = "B")),
        )

        val merged = SyncEngine.merge(local, remote, now, activeTimetableId = "A")

        // B 已删：活的 t1 改挂当前课表 A
        assertEquals("A", merged.terms.single { it.id == "t1" }.timetableId)
    }

    @Test
    fun `双方都没有课表却有活学期时造默认课表兜底`() {
        // 旧版本快照直接落进一个还没建过课表的库（升级后同步任务先于用户打开应用跑起来）
        val legacyRemoteTerm = TermDto(
            id = "t1", name = "term-t1", firstDayEpochDay = 20000, totalWeeks = 20,
            isCurrent = false, createdAt = 1, updatedAt = 1,
        )
        val local = snapshot()
        val remote = snapshot(deviceId = "r", terms = listOf(legacyRemoteTerm))

        val merged = SyncEngine.merge(local, remote, now)

        val fallback = merged.timetables.single { it.id == DefaultTimetable.ID }
        assertNull(fallback.deletedAt)
        assertEquals(DefaultTimetable.NAME, fallback.name)
        assertEquals(DefaultTimetable.ID, merged.terms.single().timetableId)
    }

    @Test
    fun `当前课表被对端删除时归属回落到最早创建的课表`() {
        val local = snapshot(
            timetables = listOf(timetable("old", createdAt = 1), timetable("new", createdAt = 2)),
            terms = listOf(term("t1", 10, timetableId = "old")),
        )
        val legacyRemoteTerm = TermDto(
            id = "t9", name = "term-t9", firstDayEpochDay = 20000, totalWeeks = 20,
            isCurrent = false, createdAt = 1, updatedAt = 1,
        )
        val remote = snapshot(
            deviceId = "r",
            timetables = listOf(
                timetable("old", createdAt = 1),
                timetable("new", updatedAt = 100, deletedAt = 100, createdAt = 2),
            ),
            terms = listOf(legacyRemoteTerm),
        )

        // 传入的当前课表 new 刚被对端删掉 → 回落到创建最早的 old
        val merged = SyncEngine.merge(local, remote, now, activeTimetableId = "new")
        assertEquals("old", merged.terms.single { it.id == "t9" }.timetableId)
    }

    @Test
    fun `合并幂等 重复同步结果一致`() {
        val local = snapshot(
            courses = listOf(course("a", 10), course("b", 30, deletedAt = 30)),
        )
        val remote = snapshot(
            deviceId = "r",
            courses = listOf(course("a", 20, name = "updated"), course("b", 20)),
        )

        val once = SyncEngine.merge(local, remote, now)
        val twice = SyncEngine.merge(once, remote, now)

        assertEquals(once.courses, twice.courses)
        assertFalse(once.courses.first { it.id == "b" }.deletedAt == null)
    }
}
