package com.nullclass.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DefaultPeriodTimesTest {

    @Test
    fun `模板为 12 节且编号连续`() {
        val times = DefaultPeriodTimes.create("t1")

        assertEquals(12, times.size)
        assertEquals((1..12).toList(), times.map { it.periodIndex })
    }

    @Test
    fun `会话分组为上午下午晚上各 4 节`() {
        val times = DefaultPeriodTimes.create("t1")

        assertEquals(List(4) { Session.MORNING } + List(4) { Session.AFTERNOON } + List(4) { Session.EVENING }, times.map { it.session })
    }

    @Test
    fun `时间合法且逐节递增`() {
        val times = DefaultPeriodTimes.create("t1")

        times.forEach { time ->
            assertTrue(time.startMinuteOfDay < time.endMinuteOfDay, "$time")
        }
        times.zipWithNext().forEach { (cur, next) ->
            assertTrue(cur.endMinuteOfDay < next.startMinuteOfDay, "$cur 应早于 $next")
        }
    }

    @Test
    fun `模板即领域模型 节次与大节换算吻合`() {
        val times = DefaultPeriodTimes.create("t1")

        // 12 节 = 6 大节；每大节两小节共享同一会话
        repeat(6) { section ->
            val pair = times.filter { SectionMath.sectionIndex(it.periodIndex) == section + 1 }
            assertEquals(2, pair.size)
            assertEquals(pair[0].session, pair[1].session)
        }
    }
}
