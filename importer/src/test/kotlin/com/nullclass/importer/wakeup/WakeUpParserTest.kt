package com.nullclass.importer.wakeup

import com.nullclass.importer.wakeup.WakeUpParser.WakeUpResult
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 全部以 docs/samples 的真实样本断言（6 门课 / 7 条安排，覆盖矩阵见
 * docs/samples/wakeup_schedule_sample.md）。
 */
class WakeUpParserTest {

    private fun sample(): String =
        javaClass.classLoader!!.getResourceAsStream("wakeup_sample1.json")!!
            .readBytes().toString(Charsets.UTF_8)

    private fun parseSample(): WakeUpResult = WakeUpParser.parse(sample())

    @Test
    fun `样本解析 - 学期信息正确`() {
        val result = parseSample()
        assertEquals("2026-2027-1", result.term.name)
        // startTime=2026-09-07 本身就是周一
        assertEquals(LocalDate.of(2026, 9, 7).toEpochDay(), result.term.firstDayEpochDay)
        assertEquals(20, result.term.totalWeeks)
    }

    @Test
    fun `样本解析 - 节次时间来自 WakeUp 行2（12 节，含晚上）`() {
        val result = parseSample()
        assertEquals(12, result.periodTimes.size)
        val p1 = result.periodTimes.first { it.periodIndex == 1 }
        assertEquals(480, p1.startMinuteOfDay) // 08:00
        assertEquals(525, p1.endMinuteOfDay)   // 08:45
        assertEquals(0, p1.session)            // 上午
        val p5 = result.periodTimes.first { it.periodIndex == 5 }
        assertEquals(900, p5.startMinuteOfDay) // 15:00
        assertEquals(1, p5.session)            // 下午
        val p9 = result.periodTimes.first { it.periodIndex == 9 }
        assertEquals(1130, p9.startMinuteOfDay) // 18:50
        assertEquals(2, p9.session)             // 晚上
    }

    @Test
    fun `样本解析 - 课程数去重为 6，安排数 7`() {
        val result = parseSample()
        assertEquals(6, result.courses.size)
        assertEquals(7, result.blocks.size)
        // 同课多安排归并：高等数学(上) 有 2 条安排共享同一 courseId
        val math = result.courses.first { it.name == "高等数学(上)" }
        assertEquals(2, result.blocks.count { it.courseId == math.id })
        assertEquals("张三", math.teacher)
    }

    @Test
    fun `样本解析 - 连堂映射 startNode+step`() {
        val result = parseSample()
        val physics = result.blocks.first { it.location == "C303" }
        assertEquals(9, physics.startPeriod) // startNode=9
        assertEquals(10, physics.endPeriod)  // step=2
        assertEquals(7, physics.dayOfWeek)   // 周日
        assertEquals(3, physics.startWeek)
        assertEquals(18, physics.endWeek)
        assertEquals("EVEN", physics.weekType) // type=2 双周
    }

    @Test
    fun `样本解析 - 单双周 type 映射`() {
        val result = parseSample()
        val english = result.blocks.first { it.location == "B202" }
        assertEquals("ODD", english.weekType) // type=1
        val ds = result.blocks.first { it.location == "实验楼404" }
        assertEquals("EVEN", ds.weekType)     // type=2
        val math = result.blocks.first { it.location == "A101" }
        assertEquals("ALL", math.weekType)    // type=0
    }

    @Test
    fun `样本解析 - 颜色欧氏最近映射`() {
        val result = parseSample()
        // #FFEF5350 → RGB EF5350 与调色板红 0xE8595B 最近
        val math = result.courses.first { it.name == "高等数学(上)" }
        assertEquals(0, math.colorIndex)
        // #FF42A5F5 与蓝 0x1C7ED6 最近
        val english = result.courses.first { it.name == "大学英语(一)" }
        assertEquals(5, english.colorIndex)
    }

    @Test
    fun `首周缺课课的 startWeek 保留`() {
        val result = parseSample()
        val pe = result.blocks.first { it.location == "东区操场" }
        assertEquals(2, pe.startWeek) // startWeek=2（首周缺课）
        assertEquals(20, pe.endWeek)
    }

    @Test
    fun `行序打乱 - 仍可解析（不依赖行号）`() {
        val lines = sample().lineSequence().filter { it.isNotBlank() }.toMutableList()
        lines.shuffle(java.util.Random(42))
        val result = WakeUpParser.parse(lines.joinToString("\n"))
        assertEquals(6, result.courses.size)
        assertEquals(7, result.blocks.size)
        assertEquals("2026-2027-1", result.term.name)
    }

    @Test
    fun `非 WakeUp 内容 - 抛可读错误`() {
        assertFailsWith<IllegalArgumentException> {
            WakeUpParser.parse("{\"hello\":\"world\"}")
        }
        assertFailsWith<IllegalArgumentException> {
            WakeUpParser.parse("[{\"node\":1,\"startTime\":\"08:00\"}]") // 只有节次表，无课程安排
        }
    }

    @Test
    fun `type 缺失按每周处理`() {
        val raw = """
            "3"
            {"courseTableName":"t","startTime":"2026-09-07","maxWeek":20}
            [{"endTime":"08:45","node":1,"startTime":"08:00"}]
            [{"color":"#FFEF5350","courseName":"课","id":1}]
            [{"day":1,"endWeek":20,"id":1,"room":"A","startNode":1,"startWeek":1,"step":2}]
        """.trimIndent()
        val result = WakeUpParser.parse(raw)
        assertEquals("ALL", result.blocks.single().weekType)
    }

    @Test
    fun `节次表缺失 - 退化默认模板并覆盖所需节数`() {
        val raw = """
            {"courseTableName":"t","startTime":"2026-09-07","maxWeek":20,"nodesPerDay":14}
            [{"color":"#FFEF5350","courseName":"课","id":1}]
            [{"day":1,"endWeek":20,"id":1,"room":"A","startNode":13,"startWeek":1,"step":2}]
        """.trimIndent()
        val result = WakeUpParser.parse(raw)
        assertEquals(14, result.periodTimes.size) // max(nodesPerDay=14, startNode+step-1=14)
        assertTrue("默认模板" in result.warnings.single())
        assertEquals(13, result.blocks.single().startPeriod)
    }

    @Test
    fun `开学日非周一 - 回退所在周周一`() {
        val raw = """
            {"courseTableName":"t","startTime":"2026-09-09","maxWeek":20}
            [{"endTime":"08:45","node":1,"startTime":"08:00"}]
            [{"color":"#FFEF5350","courseName":"课","id":1}]
            [{"day":1,"endWeek":20,"id":1,"room":"A","startNode":1,"startWeek":1,"step":2}]
        """.trimIndent()
        val result = WakeUpParser.parse(raw)
        assertEquals(LocalDate.of(2026, 9, 7).toEpochDay(), result.term.firstDayEpochDay) // 09-09(三) → 09-07(一)
    }

    @Test
    fun `parseToDocument - 产出可导入的 ScheduleDocument`() {
        val doc = WakeUpParser.parseToDocument(sample())
        assertEquals(1, doc.terms.size)
        assertEquals(6, doc.courses.size)
        assertEquals(7, doc.blocks.size)
        assertEquals(12, doc.periodTimes.size)
        assertEquals(2, doc.formatVersion)
    }
}
