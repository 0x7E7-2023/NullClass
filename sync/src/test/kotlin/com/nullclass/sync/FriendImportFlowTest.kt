package com.nullclass.sync

import com.nullclass.importer.PeriodTimeDto
import com.nullclass.importer.ScheduleDocument
import com.nullclass.importer.TermDto
import com.nullclass.importer.TimetableDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 真机导入流程的整链回归（align → merge → 采纳数）：
 * 导入**别人的** `.nullclass` 备份（沿用原 ID 的来源）应当多出一张课表，
 * 而不是把对方的学期混进我的课表；我的记录一条不被改动。
 */
class FriendImportFlowTest {

    private val now = 3_000_000L

    /** 本地：一张自己建的课表 + 一个学期（12 行节次表）。 */
    private fun localDoc() = ScheduleDocument(
        deviceId = "device-me",
        generatedAt = now,
        timetables = listOf(TimetableDto("tt-mine", "我的课表", createdAt = 1_700_000, updatedAt = 1_700_000)),
        terms = listOf(
            TermDto(
                id = "t-mine", name = "2026 秋", firstDayEpochDay = 20671, totalWeeks = 20,
                isCurrent = true, createdAt = 1_700_000, updatedAt = 1_700_000, timetableId = "tt-mine",
            ),
        ),
        courses = emptyList(),
        blocks = emptyList(),
        periodTimes = (1..12).map {
            PeriodTimeDto("t-mine", it, 480 + it, 525 + it, 0, updatedAt = 1_700_000)
        },
    )

    /** 别人的备份：自己的课表 + 学期，note createdAt 故意早于我的（跨设备时钟很常见）。 */
    private fun friendDoc() = ScheduleDocument(
        deviceId = "friend-device",
        generatedAt = now,
        timetables = listOf(TimetableDto("tt-friend", "FriendTable", createdAt = 1, updatedAt = 1)),
        terms = listOf(
            TermDto(
                id = "t-friend", name = "FriendTerm", firstDayEpochDay = 20671, totalWeeks = 20,
                isCurrent = true, createdAt = 1, updatedAt = 1, timetableId = "tt-friend",
            ),
        ),
        courses = emptyList(),
        blocks = emptyList(),
        periodTimes = emptyList(),
    )

    @Test
    fun `导入别人的备份 - 多出一张课表，归属不串，我的记录原样`() {
        val local = localDoc()
        val incoming = friendDoc()

        val aligned = ImportAligner.align(local, incoming, now, targetTimetableId = "tt-mine")
        val merged = SyncEngine.merge(aligned.local, aligned.incoming, now, activeTimetableId = "tt-mine")

        // 两张课表、各一个学期、归属各归各
        assertEquals(setOf("tt-mine", "tt-friend"), merged.timetables.map { it.id }.toSet())
        assertEquals(
            mapOf("t-mine" to "tt-mine", "t-friend" to "tt-friend"),
            merged.terms.associate { it.id to it.timetableId },
        )
        // 两张课表各自的当前学期都在（归一化按课表分组，不清对方的）
        assertEquals(setOf("t-mine", "t-friend"), merged.terms.filter { it.isCurrent }.map { it.id }.toSet())
        // 我的节次表原样
        assertEquals(12, merged.periodTimes.count { it.termId == "t-mine" })
        // 备份来源不做对齐替换：incoming 原样进合并
        assertEquals(incoming.terms, aligned.incoming.terms)
    }

    @Test
    fun `采纳数只算新进来的两张记录，不会虚报`() {
        val local = localDoc()
        val incoming = friendDoc()
        val aligned = ImportAligner.align(local, incoming, now, targetTimetableId = "tt-mine")
        val merged = SyncEngine.merge(aligned.local, aligned.incoming, now, activeTimetableId = "tt-mine")

        // 采纳 = FriendTable + FriendTerm 两条；我的记录没有被「重新采纳」
        assertEquals(2, countAdoptedRecords(local, merged))
    }

    @Test
    fun `旧格式备份无课表字段 - 全部落当前课表，不新建`() {
        val local = localDoc()
        // 0.8.1 导出的备份：terms 没有 timetableId，document 没有 timetables
        val legacy = ScheduleDocument(
            deviceId = "old-device",
            generatedAt = now,
            terms = listOf(
                TermDto(
                    id = "t-old", name = "旧备份学期", firstDayEpochDay = 20671, totalWeeks = 20,
                    isCurrent = false, createdAt = 1, updatedAt = 1,
                ),
            ),
            courses = emptyList(),
            blocks = emptyList(),
            periodTimes = emptyList(),
        )

        val merged = SyncEngine.merge(local, legacy, now, activeTimetableId = "tt-mine")

        assertEquals("tt-mine", merged.terms.first { it.id == "t-old" }.timetableId, "落当前课表")
        assertEquals(1, merged.timetables.size, "不新建课表")
        assertTrue(merged.terms.first { it.id == "t-mine" }.isCurrent, "我的当前学期不被顶掉")
    }
}
