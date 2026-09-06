package com.nullclass.sync

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
    ) = TermDto(
        id = id, name = "term-$id", firstDayEpochDay = firstDayEpochDay, totalWeeks = 20,
        isCurrent = isCurrent, createdAt = 1, updatedAt = updatedAt, deletedAt = deletedAt,
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
    ) = SnapshotDto(
        deviceId = deviceId, generatedAt = now,
        terms = terms, courses = courses, blocks = blocks, periodTimes = periodTimes,
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
    fun `节次时间按 termId-periodIndex 合并`() {
        val local = snapshot(
            periodTimes = listOf(
                PeriodTimeDto("t1", 1, 480, 525, 0, updatedAt = 10),
            ),
        )
        val remote = snapshot(
            deviceId = "r",
            periodTimes = listOf(
                PeriodTimeDto("t1", 1, 480, 530, 0, updatedAt = 20), // 更新
                PeriodTimeDto("t1", 2, 535, 580, 0, updatedAt = 5),  // 新增
            ),
        )

        val merged = SyncEngine.merge(local, remote, now)

        assertEquals(2, merged.periodTimes.size)
        assertEquals(530, merged.periodTimes.first { it.periodIndex == 1 }.endMinuteOfDay)
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
