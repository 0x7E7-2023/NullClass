package com.nullclass.importer.jw.ocr

import com.nullclass.importer.jw.JwPayloadCodec
import com.nullclass.importer.jw.JwScheduleNormalizer
import com.nullclass.importer.jw.JwSchedulePayload
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 真实教务页面的回归（金智 jwapp 学生课表，2026-09-12 真机抓取）。
 *
 * fixture 的**几何是原样的**，只有课程名/教师/教室被换成了同形的假数据（见生成脚本）；
 * 文字形状（课号前缀、教学班后缀、周次/节次写法）没有动，所以它锁住的正是真机上踩到的那些坑：
 *  - 一格跨多节 → 文字横跨相邻两个行锚点，按「离锚点不超过半个行距」判会把课名整片丢掉
 *  - 好几门周次错开的课叠在同一个格子里 → 按格子分组会把它们糊成一门，按列分组才分得开
 *  - 一格写好几段不连续的周次（`2-5周,9-17周`）→ 只取第一段会静默少上半学期的课
 *  - 节次写在格子里（`1-2节`）→ 拿行锚点当节次会把连堂记成单节
 *  - 教室跟在周次同一行（`…,1-2节,教学楼105`）→ 逐行找整行是教室的写法会整条丢掉教室
 *  - 课号与教学班号（`3010020 课程A[05]` / `[05S01]`）→ 不剥掉会变成两门课、名字也没法看
 *  - 跨 4 节的课被页面在两个格子里各画一遍 → 不去重会多出一倍的课块
 */
class JwRealPageTest {

    private fun payload(): JwSchedulePayload {
        val text = checkNotNull(javaClass.classLoader?.getResourceAsStream("jw/real-xskcb-boxes.json")) {
            "缺少测试资源 jw/real-xskcb-boxes.json"
        }.readBytes().toString(Charsets.UTF_8)
        return JwPayloadCodec.decode(text)
    }

    @Test
    fun `真实教务页面的文本框能还原出整张课表`() {
        val payload = payload()
        assertEquals(JwSchedulePayload.KIND_BOXES, payload.kind)

        val table = JwTableAligner.align(JwBoxes.toOcrPage(payload))
        assertTrue(table.reliable, table.warnings.toString())
        assertEquals((1..7).toList(), table.colDays)
        assertEquals((1..12).toList(), table.rowPeriods)
        // 落不进网格的只剩节次列左边那三个「上午/下午/晚上」分组标签
        assertEquals(3, table.unassigned.size, table.unassigned.toString())

        val built = JwOcrScheduleBuilder.build(
            table = table,
            termName = "通用适配器（自动识别）",
            firstDayEpochDay = 20_000,
            totalWeeks = JwOcrScheduleBuilder.inferTotalWeeks(table),
            ocrAssisted = false,
        )
        assertTrue(built.issues.isEmpty(), built.issues.toString())

        val term = built.payload.terms.single()
        assertEquals(22, term.totalWeeks, "表里出现 21-22 周，总周数要跟着抬上去")
        assertEquals(12, term.courses.size)
        assertEquals(50, term.courses.sumOf { it.blocks.size })

        // 课号 + 教学班号被剥掉，同一门课的两个教学班（[05] 与 [05S01]）并成一门
        val courseA = term.courses.first { it.name == "课程A" }
        assertEquals("教师甲", courseA.teacher)
        assertEquals(8, courseA.blocks.size)
        assertTrue(
            courseA.blocks.any {
                it.dayOfWeek == 1 && it.startPeriod == 1 && it.endPeriod == 2 &&
                    it.startWeek == 2 && it.endWeek == 5 && it.location == "教学楼105"
            },
            courseA.blocks.toString(),
        )
        assertTrue(
            courseA.blocks.any {
                it.dayOfWeek == 3 && it.startPeriod == 1 && it.endPeriod == 2 &&
                    it.startWeek == 3 && it.endWeek == 6 && it.location == "综合楼407"
            },
            courseA.blocks.toString(),
        )

        // 一行里好几段周次：2-6 周单周（双）、10 周、12-17 周各成一块
        val courseB = term.courses.first { it.name == "课程B" }
        assertEquals(5, courseB.blocks.size, courseB.blocks.toString())
        assertEquals(
            listOf("EVEN" to (2 to 6), "ALL" to (10 to 10), "ALL" to (12 to 17)),
            courseB.blocks.filter { it.dayOfWeek == 4 }
                .map { it.weekType to (it.startWeek to it.endWeek) },
        )

        // 页面把跨 4 节的课画在两个格子里，重复的课块要去掉（重复的那份是「周三 1-4 节 8 周」）
        val courseD = term.courses.first { it.name == "课程D" }
        assertEquals(listOf(3, 3, 5), courseD.blocks.map { it.dayOfWeek })
        assertEquals(listOf(3, 3, 1), courseD.blocks.map { it.startPeriod })
        assertTrue(
            courseD.blocks.any { it.dayOfWeek == 5 && it.startPeriod == 1 && it.endPeriod == 8 && it.startWeek == 6 },
            courseD.blocks.toString(),
        )

        // 归一化必须能通过（周次不越界、节次合法）
        val document = JwScheduleNormalizer.normalize(built.payload, schoolKey = "universal", now = 0L)
        assertEquals(12, document.courses.size)
        assertEquals("jw-universal", document.deviceId)
    }
}
