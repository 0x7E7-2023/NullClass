package com.nullclass.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PeriodRetimingTest {

    private fun hm(hour: Int, minute: Int) = hour * 60 + minute

    private fun periods(vararg times: Pair<Int, Int>) = times.mapIndexed { index, (start, end) ->
        PeriodTime(periodIndex = index + 1, startMinuteOfDay = start, endMinuteOfDay = end)
    }

    private fun ok(result: RetimeResult): List<PeriodTime> {
        assertTrue(result is RetimeResult.Ok, "expected Ok, was $result")
        return result.periods
    }

    @Test
    fun `默认模板套 45 加 10 是恒等变换`() {
        // 默认模板本来就是「45 分钟一节 + 大节内课间 10 分钟」，套用不该把它改坏
        val template = DefaultPeriodTimes.create("t")
        assertEquals(template, ok(retimeSections(template, 45, 10)))
    }

    @Test
    fun `只重排大节内部，大节开课时刻不动`() {
        val template = DefaultPeriodTimes.create("t")
        val result = ok(retimeSections(template, 40, 5))

        // 第 1、3、5…节的开始时间 = 大节开课时刻，一个都不能变
        template.indices.filter { it % 2 == 0 }.forEach { i ->
            assertEquals(template[i].startMinuteOfDay, result[i].startMinuteOfDay, "第 ${i + 1} 节")
        }
        assertEquals(hm(8, 0) to hm(8, 40), result[0].startMinuteOfDay to result[0].endMinuteOfDay)
        assertEquals(hm(8, 45) to hm(9, 25), result[1].startMinuteOfDay to result[1].endMinuteOfDay)
        // 大节之间的休息由锚点决定：9:25 到 10:00 反而更长，快速设定不管它
        assertEquals(hm(10, 0), result[2].startMinuteOfDay)
    }

    @Test
    fun `奇数节次时最后一个大节只有一节`() {
        val odd = periods(
            hm(8, 0) to hm(8, 45),
            hm(8, 55) to hm(9, 40),
            hm(10, 0) to hm(10, 45),
        )

        val result = ok(retimeSections(odd, 50, 5))

        assertEquals(3, result.size)
        assertEquals(hm(10, 0) to hm(10, 50), result[2].startMinuteOfDay to result[2].endMinuteOfDay)
    }

    @Test
    fun `节次号与会话分组原样保留`() {
        val mixed = listOf(
            PeriodTime(periodIndex = 1, startMinuteOfDay = hm(8, 0), endMinuteOfDay = hm(8, 45), session = Session.MORNING),
            PeriodTime(periodIndex = 2, startMinuteOfDay = hm(8, 55), endMinuteOfDay = hm(9, 40), session = Session.MORNING),
            PeriodTime(periodIndex = 3, startMinuteOfDay = hm(18, 30), endMinuteOfDay = hm(19, 15), session = Session.EVENING),
        )

        val result = ok(retimeSections(mixed, 45, 10))

        assertEquals(listOf(1, 2, 3), result.map { it.periodIndex })
        assertEquals(listOf(Session.MORNING, Session.MORNING, Session.EVENING), result.map { it.session })
    }

    @Test
    fun `大节间距放不下时返回 Overflow，不给半截结果`() {
        // 两个大节只隔 95 分钟（8:00 → 9:35），而 45 + 10 + 45 = 100 分钟排不下
        val tight = periods(
            hm(8, 0) to hm(8, 45),
            hm(8, 50) to hm(9, 35),
            hm(9, 35) to hm(10, 20),
        )

        val result = retimeSections(tight, 45, 10)

        assertTrue(result is RetimeResult.Overflow, "expected Overflow, was $result")
        assertEquals(1, result.section)
        assertEquals(hm(9, 40), result.endMinuteOfDay)
        assertEquals(hm(9, 35), result.nextStartMinuteOfDay)
    }

    @Test
    fun `排到第二天时返回 OutOfDay，不抛异常`() {
        // 晚上开课的作息（删过行或教务导入的都长这样）：最后一个大节后面没有锚点可比，
        // 不放它过去就会算出 endMinuteOfDay = 1460，撞在 PeriodTime 的 init 上抛异常
        val late = periods(
            hm(8, 0) to hm(8, 45),
            hm(8, 55) to hm(9, 40),
            hm(20, 20) to hm(21, 5),
            hm(21, 15) to hm(22, 0),
        )

        val result = retimeSections(late, 120, 0)

        assertTrue(result is RetimeResult.OutOfDay, "expected OutOfDay, was $result")
        assertEquals(2, result.section)
        assertEquals(hm(20, 20) + 240, result.endMinuteOfDay)
    }

    @Test
    fun `排到当天 24 点整算排得下，再晚一分钟就不行`() {
        val evening = periods(hm(20, 20) to hm(21, 5), hm(21, 15) to hm(22, 0))

        val fits = ok(retimeSections(evening, 110, 0))
        assertEquals(MINUTES_PER_DAY, fits[1].endMinuteOfDay) // 22:10–24:00

        val over = retimeSections(evening, 111, 0)
        assertTrue(over is RetimeResult.OutOfDay, "expected OutOfDay, was $over")
        assertEquals(MINUTES_PER_DAY + 2, over.endMinuteOfDay)
    }

    @Test
    fun `空表返回空`() {
        assertEquals(emptyList(), ok(retimeSections(emptyList(), 45, 10)))
    }
}
