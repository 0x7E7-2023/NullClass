package com.nullclass.importer.shiguang

import com.nullclass.importer.ImportNotice
import com.nullclass.importer.ImportNoticeEntry
import com.nullclass.importer.ImportProvenance
import com.nullclass.importer.ScheduleFileError
import com.nullclass.importer.ScheduleFileException
import com.nullclass.importer.shiguang.ShiguangParser.ShiguangResult
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 样本见 docs/samples/shiguangschedule_sample.json（= test resources 里的同一份）：
 * 连续周 / 单周 / 双周 / 拆成两行的连堂 / 只有自定义时间的课各一例。
 */
class ShiguangParserTest {

    private fun sample(): String =
        javaClass.classLoader!!.getResourceAsStream("shiguang_sample1.json")!!
            .readBytes().toString(Charsets.UTF_8)

    private fun parseSample(): ShiguangResult = ShiguangParser.parse(sample())

    /** 提示里是否出现过某类问题（标识是稳定契约，断言不绑文案）。 */
    private fun List<ImportNoticeEntry>.has(notice: ImportNotice): Boolean =
        any { it.notice == notice }

    private fun List<ImportNoticeEntry>.argsOf(notice: ImportNotice): List<Any> =
        first { it.notice == notice }.args

    @Test
    fun `样本解析 - 学期信息`() {
        val result = parseSample()
        // 学期名带开学日期：不同学期不会被 ImportAligner 按名认成同一个
        assertEquals("拾光课表 2026-03-02", result.term.name)
        // 2026-03-02 本身就是周一
        assertEquals(LocalDate.of(2026, 3, 2).toEpochDay(), result.term.firstDayEpochDay)
        assertEquals(20, result.term.totalWeeks)
    }

    @Test
    fun `学期名 - 开学日期不同则学期名不同，相同则相同`() {
        fun nameOf(startDate: String) = ShiguangParser.parse(
            """
            {"courses":[{"name":"甲","day":1,"startSection":1,"endSection":1,"weeks":[1]}],
             "config":{"semesterStartDate":"$startDate"}}
            """.trimIndent(),
        ).term.name

        assertEquals(nameOf("2026-03-02"), nameOf("2026-03-02")) // 同一学期重复导入 → 对齐刷新
        assertTrue(nameOf("2026-03-02") != nameOf("2026-09-07")) // 春秋两份 → 各归各的
        // 没有开学日期时退回不带日期的默认名
        val noDate = ShiguangParser.parse("""{"courses":[{"name":"甲","day":1,"startSection":1,"endSection":1,"weeks":[1]}]}""")
        assertEquals(ShiguangParser.DEFAULT_TERM_NAME, noDate.term.name)
    }

    @Test
    fun `审计时间戳 - 用真实时钟，重复导入才能更新学期与作息表`() {
        val result = ShiguangParser.parse(
            """{"courses":[{"name":"甲","day":1,"startSection":1,"endSection":1,"weeks":[1]}]}""",
            now = 1_700_000_000_000L,
        )
        assertEquals(1_700_000_000_000L, result.term.updatedAt)
        assertEquals(1_700_000_000_000L, result.courses.single().updatedAt)
        assertEquals(1_700_000_000_000L, result.blocks.single().updatedAt)
        assertTrue(result.periodTimes.all { it.updatedAt == 1_700_000_000_000L })
    }

    @Test
    fun `样本解析 - 作息表照搬导出文件`() {
        val result = parseSample()
        assertEquals(6, result.periodTimes.size)
        val p1 = result.periodTimes.first { it.periodIndex == 1 }
        assertEquals(8 * 60, p1.startMinuteOfDay)
        assertEquals(8 * 60 + 45, p1.endMinuteOfDay)
        assertEquals(0, p1.session) // 上午
        assertEquals(1, result.periodTimes.first { it.periodIndex == 5 }.session) // 下午
    }

    @Test
    fun `样本解析 - 课程按「课名 + 老师」归并`() {
        val result = parseSample()
        assertEquals(listOf("高等数学A", "线性代数", "大学英语", "晨跑打卡"), result.courses.map { it.name })
        assertEquals("张老师", result.courses.first().teacher)
        // 上游 teacher 是空串 → 空着比写「未知」好
        assertNull(result.courses.first { it.name == "晨跑打卡" }.teacher)
        // color 是上游调色板下标（1-based）
        assertEquals(3, result.courses.first { it.name == "高等数学A" }.colorIndex)
    }

    @Test
    fun `样本解析 - 连续周次切成一段 ALL`() {
        val result = parseSample()
        val course = result.courses.first { it.name == "高等数学A" }
        val blocks = result.blocks.filter { it.courseId == course.id }
        assertEquals(1, blocks.size)
        val block = blocks.single()
        assertEquals(1, block.startWeek)
        assertEquals(16, block.endWeek)
        assertEquals("ALL", block.weekType)
        assertEquals(1, block.dayOfWeek)
        assertEquals(1, block.startPeriod)
        assertEquals(2, block.endPeriod)
        assertEquals("东13-D-124c", block.location)
    }

    @Test
    fun `样本解析 - 单周与双周`() {
        val result = parseSample()
        val odd = result.blocks.single { it.courseId == result.courses.first { c -> c.name == "线性代数" }.id }
        assertEquals("ODD", odd.weekType)
        assertEquals(1, odd.startWeek)
        assertEquals(11, odd.endWeek)

        val even = result.blocks.single { it.courseId == result.courses.first { c -> c.name == "大学英语" }.id }
        assertEquals("EVEN", even.weekType)
        assertEquals(2, even.startWeek)
        assertEquals(10, even.endWeek)
    }

    @Test
    fun `样本解析 - 上游拆成两行的连堂合并成一块`() {
        val result = parseSample()
        val english = result.courses.first { it.name == "大学英语" }
        val blocks = result.blocks.filter { it.courseId == english.id }
        assertEquals(1, blocks.size)
        assertEquals(5, blocks.single().startPeriod)
        assertEquals(6, blocks.single().endPeriod)
    }

    @Test
    fun `样本解析 - 只有自定义时间的课落到最接近的一节并提醒`() {
        val result = parseSample()
        val block = result.blocks.single { it.courseId == result.courses.first { c -> c.name == "晨跑打卡" }.id }
        assertEquals(1, block.startPeriod) // 08:05 最接近第 1 节（08:00）
        assertEquals(1, block.endPeriod)
        assertEquals(5, block.dayOfWeek)
        assertTrue(result.warnings.has(ImportNotice.SHIGUANG_CUSTOM_TIME_NEAREST))
        assertEquals(
            listOf<Any>("晨跑打卡", "08:05", 1),
            result.warnings.argsOf(ImportNotice.SHIGUANG_CUSTOM_TIME_NEAREST),
        )
    }

    // ---- 周次切段（手册 §4.1）----

    @Test
    fun `周次切段 - 连续、隔周、落单`() {
        assertEquals(
            listOf(ShiguangParser.WeekRun(1, 5, "ALL")),
            ShiguangParser.runsOf(listOf(1, 2, 3, 4, 5)),
        )
        assertEquals(
            listOf(ShiguangParser.WeekRun(1, 9, "ODD")),
            ShiguangParser.runsOf(listOf(1, 3, 5, 7, 9)),
        )
        assertEquals(
            listOf(ShiguangParser.WeekRun(2, 8, "EVEN")),
            ShiguangParser.runsOf(listOf(2, 4, 6, 8)),
        )
        assertEquals(
            listOf(ShiguangParser.WeekRun(7, 7, "ALL")),
            ShiguangParser.runsOf(listOf(7)),
        )
    }

    @Test
    fun `周次切段 - 混合形状切成多段`() {
        assertEquals(
            listOf(ShiguangParser.WeekRun(1, 3, "ALL"), ShiguangParser.WeekRun(9, 13, "ODD")),
            ShiguangParser.runsOf(listOf(1, 2, 3, 9, 11, 13)),
        )
        // 段的步长看**段首那两周**：1,2 相邻 → 先切出 ALL 1-3，落下的 5 单独成段
        assertEquals(
            listOf(ShiguangParser.WeekRun(1, 3, "ALL"), ShiguangParser.WeekRun(5, 5, "ALL")),
            ShiguangParser.runsOf(listOf(1, 2, 3, 5)),
        )
    }

    @Test
    fun `周次切段 - 切出来的段与原集合等价`() {
        val weeks = listOf(1, 2, 3, 4, 6, 8, 10, 15, 17, 19, 20)
        val covered = ShiguangParser.runsOf(weeks).flatMap { run ->
            (run.startWeek..run.endWeek).filter {
                when (run.weekType) {
                    "ODD" -> it % 2 == 1
                    "EVEN" -> it % 2 == 0
                    else -> true
                }
            }
        }.distinct().sorted()
        assertEquals(weeks, covered)
    }

    // ---- 容错 ----

    @Test
    fun `非法输入 - 不是 JSON`() {
        val e = assertFailsWith<ScheduleFileException> { ShiguangParser.parse("这不是 json") }
        assertEquals(ScheduleFileError.SHIGUANG_BAD_JSON, e.error)
    }

    @Test
    fun `非法输入 - 缺少 courses`() {
        val e = assertFailsWith<ScheduleFileException> { ShiguangParser.parse("""{"timeSlots":[]}""") }
        assertEquals(ScheduleFileError.SHIGUANG_NO_COURSES, e.error)
    }

    @Test
    fun `非法输入 - courses 为空`() {
        val e = assertFailsWith<ScheduleFileException> { ShiguangParser.parse("""{"courses":[]}""") }
        assertEquals(ScheduleFileError.SHIGUANG_EMPTY_COURSES, e.error)
    }

    @Test
    fun `容错 - day 非法的课跳过并记进 warnings`() {
        val raw = """
            {"courses":[
              {"name":"甲","day":9,"startSection":1,"endSection":1,"weeks":[1]},
              {"name":"乙","day":2,"startSection":1,"endSection":1,"weeks":[1]}
            ]}
        """.trimIndent()
        val result = ShiguangParser.parse(raw)
        assertEquals(listOf("乙"), result.courses.map { it.name })
        assertTrue(result.warnings.has(ImportNotice.SHIGUANG_ROW_BAD_DAY))
        assertEquals(listOf<Any>("甲", "9"), result.warnings.argsOf(ImportNotice.SHIGUANG_ROW_BAD_DAY))
    }

    @Test
    fun `容错 - 没有作息表时用默认节次并提醒`() {
        val raw = """{"courses":[{"name":"甲","day":1,"startSection":1,"endSection":2,"weeks":[1,2]}]}"""
        val result = ShiguangParser.parse(raw)
        assertEquals(12, result.periodTimes.size)
        assertEquals(8 * 60, result.periodTimes.first().startMinuteOfDay)
        assertTrue(result.warnings.has(ImportNotice.SHIGUANG_NO_PERIOD_TABLE))
    }

    @Test
    fun `容错 - 作息表比实际节次短时按默认课长补齐`() {
        val raw = """
            {"courses":[{"name":"甲","day":1,"startSection":3,"endSection":3,"weeks":[1]}],
             "timeSlots":[{"number":1,"startTime":"08:00","endTime":"08:45"},
                          {"number":2,"startTime":"08:55","endTime":"09:40"}],
             "config":{"defaultClassDuration":45,"defaultBreakDuration":10}}
        """.trimIndent()
        val result = ShiguangParser.parse(raw)
        assertEquals(3, result.periodTimes.size)
        val third = result.periodTimes.first { it.periodIndex == 3 }
        assertEquals(9 * 60 + 50, third.startMinuteOfDay)
        assertEquals(10 * 60 + 35, third.endMinuteOfDay)
        assertTrue(result.warnings.has(ImportNotice.SHIGUANG_PERIODS_EXTENDED))
    }

    @Test
    fun `容错 - 周次超过声明的总周数时按实际周次扩容`() {
        val raw = """
            {"courses":[{"name":"甲","day":1,"startSection":1,"endSection":1,"weeks":[1,22]}],
             "config":{"semesterTotalWeeks":20}}
        """.trimIndent()
        val result = ShiguangParser.parse(raw)
        assertEquals(22, result.term.totalWeeks)
        assertTrue(result.blocks.any { it.endWeek == 22 })
    }

    @Test
    fun `容错 - 上游把 semesterTotalWeeks 写成 totalWeeks 也认`() {
        val raw = """
            {"courses":[{"name":"甲","day":1,"startSection":1,"endSection":1,"weeks":[1]}],
             "config":{"totalWeeks":18,"semesterStartDate":"2026-03-02"}}
        """.trimIndent()
        assertEquals(18, ShiguangParser.parse(raw).term.totalWeeks)
    }

    @Test
    fun `开学日 - 回退到每周起始日那一天`() {
        // 2026-03-04 是周三，每周起始日为周一 → 回退到 03-02
        val raw = """
            {"courses":[{"name":"甲","day":1,"startSection":1,"endSection":1,"weeks":[1]}],
             "config":{"semesterStartDate":"2026-03-04","semesterTotalWeeks":20}}
        """.trimIndent()
        assertEquals(LocalDate.of(2026, 3, 2).toEpochDay(), ShiguangParser.parse(raw).term.firstDayEpochDay)
    }

    @Test
    fun `开学日 - firstDayOfWeek 是周日时回退到周日`() {
        val raw = """
            {"courses":[{"name":"甲","day":1,"startSection":1,"endSection":1,"weeks":[1]}],
             "config":{"semesterStartDate":"2026-03-04","firstDayOfWeek":7}}
        """.trimIndent()
        assertEquals(LocalDate.of(2026, 3, 1).toEpochDay(), ShiguangParser.parse(raw).term.firstDayEpochDay)
    }

    @Test
    fun `开学日 - 缺失时按基准日推算并提醒`() {
        val raw = """{"courses":[{"name":"甲","day":1,"startSection":1,"endSection":1,"weeks":[1]}]}"""
        val result = ShiguangParser.parse(raw, today = LocalDate.of(2026, 9, 20)) // 周日
        assertEquals(LocalDate.of(2026, 9, 14).toEpochDay(), result.term.firstDayEpochDay)
        assertTrue(result.warnings.has(ImportNotice.SHIGUANG_NO_START_DATE))
    }

    @Test
    fun `自定义时间 - 带节次时按节次导入并提醒未保留`() {
        val raw = """
            {"courses":[{"name":"甲","day":1,"startSection":2,"endSection":2,"weeks":[1],
                         "isCustomTime":true,"customStartTime":"07:00","customEndTime":"07:40"}]}
        """.trimIndent()
        val result = ShiguangParser.parse(raw)
        assertEquals(2, result.blocks.single().startPeriod)
        assertTrue(result.warnings.has(ImportNotice.SHIGUANG_CUSTOM_TIME_DROPPED))
    }

    @Test
    fun `自定义时间 - 差得太远就跳过而不是硬塞`() {
        val raw = """
            {"courses":[{"name":"夜跑","day":1,"weeks":[1],
                         "isCustomTime":true,"customStartTime":"05:00","customEndTime":"05:40"}],
             "timeSlots":[{"number":1,"startTime":"08:00","endTime":"08:45"}]}
        """.trimIndent()
        val e = assertFailsWith<ScheduleFileException> { ShiguangParser.parse(raw) }
        assertEquals(ScheduleFileError.SHIGUANG_NO_PLACEABLE, e.error)
    }

    @Test
    fun `节次上限 - 离谱的 startSection 被挡住`() {
        val raw = """
            {"courses":[{"name":"甲","day":1,"startSection":1,"endSection":1,"weeks":[1]},
                        {"name":"炸弹","day":1,"startSection":99999,"endSection":99999,"weeks":[1]}]}
        """.trimIndent()
        val result = ShiguangParser.parse(raw)
        assertEquals(listOf("甲"), result.courses.map { it.name })
        assertTrue(result.periodTimes.size <= 60)
        assertTrue(result.warnings.has(ImportNotice.SHIGUANG_COURSE_BEYOND_LAST_PERIOD))
        assertEquals(
            "炸弹",
            result.warnings.argsOf(ImportNotice.SHIGUANG_COURSE_BEYOND_LAST_PERIOD).first(),
        )
    }

    @Test
    fun `节次上限 - 离谱的 timeSlots number 也被挡住（自定义时间那条路不能绕过上限）`() {
        val raw = """
            {"courses":[{"name":"x","day":1,"weeks":[1],"isCustomTime":true,"customStartTime":"08:00"}],
             "timeSlots":[{"number":999999,"startTime":"08:00","endTime":"08:45"}]}
        """.trimIndent()
        val result = ShiguangParser.parse(raw)
        assertTrue(result.periodTimes.size <= 60, "不该按 number 造出上百万条节次")
        assertTrue(result.blocks.all { it.endPeriod <= 60 })
        assertTrue(result.warnings.has(ImportNotice.SHIGUANG_PERIOD_OUT_OF_RANGE))
    }

    @Test
    fun `周次越界 - 全都在学期之外的安排被丢弃，而不是铺成整学期`() {
        // 第 35 周超过 MAX_TOTAL_WEEKS(30)，截断后这门课一周都不剩
        val raw = """
            {"courses":[{"name":"甲","day":1,"startSection":1,"endSection":1,"weeks":[1,2]},
                        {"name":"越界","day":2,"startSection":1,"endSection":1,"weeks":[35]}]}
        """.trimIndent()
        val result = ShiguangParser.parse(raw)
        assertEquals(listOf("甲"), result.courses.map { it.name })
        assertTrue(result.blocks.none { it.dayOfWeek == 2 })
        assertTrue(result.warnings.has(ImportNotice.SHIGUANG_WEEKS_ALL_OUT_OF_RANGE))
        assertEquals(
            "越界",
            result.warnings.argsOf(ImportNotice.SHIGUANG_WEEKS_ALL_OUT_OF_RANGE).first(),
        )
    }

    @Test
    fun `周次越界 - 全部课程都越界时报错而不是产出空壳课表`() {
        val raw = """{"courses":[{"name":"越界","day":1,"startSection":1,"endSection":1,"weeks":[35]}]}"""
        val e = assertFailsWith<ScheduleFileException> { ShiguangParser.parse(raw) }
        assertEquals(ScheduleFileError.SHIGUANG_WEEKS_OUT_OF_RANGE, e.error)
    }

    @Test
    fun `作息表缺口 - 补出来的节次时间随节次单调不减`() {
        // 3、4 号因结束时间不晚于开始时间被跳过，留下中间缺口
        val raw = """
            {"courses":[{"name":"甲","day":1,"startSection":1,"endSection":5,"weeks":[1]}],
             "timeSlots":[{"number":1,"startTime":"08:00","endTime":"08:45"},
                          {"number":2,"startTime":"08:55","endTime":"09:40"},
                          {"number":3,"startTime":"11:40","endTime":"10:55"},
                          {"number":4,"startTime":"12:40","endTime":"11:55"},
                          {"number":5,"startTime":"11:00","endTime":"11:45"}]}
        """.trimIndent()
        val result = ShiguangParser.parse(raw)
        val ordered = result.periodTimes.sortedBy { it.periodIndex }
        assertEquals(listOf(1, 2, 3, 4, 5), ordered.map { it.periodIndex })
        ordered.zipWithNext { a, b ->
            assertTrue(a.endMinuteOfDay <= b.startMinuteOfDay, "第 ${a.periodIndex} 节结束(${a.endMinuteOfDay}) 晚于第 ${b.periodIndex} 节开始(${b.startMinuteOfDay})")
        }
        ordered.forEach {
            assertTrue(it.startMinuteOfDay in 0..1439 && it.endMinuteOfDay in 1..1440 && it.startMinuteOfDay < it.endMinuteOfDay)
        }
    }

    @Test
    fun `畸形字段 - isCustomTime 不是布尔值时只当没设，不让整份文件失败`() {
        val raw = """
            {"courses":[{"name":"甲","day":1,"startSection":1,"endSection":1,"weeks":[1],"isCustomTime":{}},
                        {"name":"乙","day":2,"startSection":1,"endSection":1,"weeks":[1],"isCustomTime":"true"}]}
        """.trimIndent()
        val result = ShiguangParser.parse(raw)
        assertEquals(listOf("甲", "乙"), result.courses.map { it.name })
    }

    @Test
    fun `文档产出 - deviceId 是拾光来源且参与同名学期对齐`() {
        val document = ShiguangParser.parseToDocument(sample())
        assertEquals(ImportProvenance.SHIGUANG_IMPORT, document.deviceId)
        assertTrue(ImportProvenance.isFreshIdImport(document.deviceId))
        assertNotNull(document.terms.singleOrNull())
    }

    @Test
    fun `格式识别 - 认得出拾光文件，认不出自家快照`() {
        assertTrue(ShiguangParser.looksLikeShiguang(sample()))
        assertTrue(!ShiguangParser.looksLikeShiguang("""{"formatVersion":2,"courses":[],"timeSlots":[]}"""))
        assertTrue(!ShiguangParser.looksLikeShiguang("""{"courses":[]}"""))
        assertTrue(!ShiguangParser.looksLikeShiguang("不是 json"))
    }
}
