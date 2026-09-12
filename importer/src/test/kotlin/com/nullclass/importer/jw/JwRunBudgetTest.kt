package com.nullclass.importer.jw

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 脚本执行的时间预算。
 *
 * 这套账要同时成立两件相反的事：
 * - 用户在弹窗上想多久都**不算**脚本时间（不然超时会把人的选择打断）；
 * - 等待本身仍有上限（不然界面一异常就永远挂着）。
 */
class JwRunBudgetTest {

    @Test
    fun `不等待时到点就超时`() {
        val budget = JwRunBudget(timeoutMs = 30_000)
        assertEquals(JwRunVerdict.RUNNING, budget.tick(1_000, waiting = false))
        assertEquals(JwRunVerdict.RUNNING, budget.tick(30_999, waiting = false))
        assertEquals(JwRunVerdict.SCRIPT_TIMEOUT, budget.tick(31_001, waiting = false))
    }

    @Test
    fun `等待期间脚本时间不走 弹窗挂着多久都不超时`() {
        val budget = JwRunBudget(timeoutMs = 30_000)
        budget.tick(0, waiting = false)
        // 用户在弹窗上想了 4 分钟
        for (now in 1_000..240_000 step 1_000) {
            assertEquals(JwRunVerdict.RUNNING, budget.tick(now.toLong(), waiting = true), "第 ${now}ms")
        }
    }

    @Test
    fun `回答之后脚本时间从上次停下的地方接着算`() {
        val budget = JwRunBudget(timeoutMs = 30_000)
        budget.tick(0, waiting = false)
        budget.tick(10_000, waiting = false)   // 脚本已经跑了 10 秒
        budget.tick(20_000, waiting = true)    // 开始等
        budget.tick(100_000, waiting = true)   // 等了 80 秒
        budget.tick(100_040, waiting = false)  // 答完：脚本时间停在 20 秒
        // 从 20 秒接着走，再过 9.96 秒还没到 30 秒
        assertEquals(JwRunVerdict.RUNNING, budget.tick(110_000, waiting = false))
        // 再过 60ms 就满 30 秒了
        assertEquals(JwRunVerdict.SCRIPT_TIMEOUT, budget.tick(110_100, waiting = false))
    }

    @Test
    fun `多段等待累计计入等待上限`() {
        val budget = JwRunBudget(timeoutMs = 30_000, maxWaitMs = 60_000)
        budget.tick(0, waiting = false)
        budget.tick(0, waiting = true)
        budget.tick(40_000, waiting = true)    // 第一段等 40 秒
        budget.tick(40_100, waiting = false)
        budget.tick(40_200, waiting = true)    // 第二段开始
        assertEquals(JwRunVerdict.RUNNING, budget.tick(59_900, waiting = true))
        assertEquals(JwRunVerdict.WAIT_TIMEOUT, budget.tick(60_200, waiting = true))
    }

    @Test
    fun `等待超时是等待自己的上限 不受脚本超时影响`() {
        val budget = JwRunBudget(timeoutMs = 1_000, maxWaitMs = 5_000)
        budget.tick(0, waiting = true)
        // 脚本超时才 1 秒，但用户还没答完 —— 不该按脚本超时判死
        assertEquals(JwRunVerdict.RUNNING, budget.tick(4_999, waiting = true))
        assertEquals(JwRunVerdict.WAIT_TIMEOUT, budget.tick(5_001, waiting = true))
    }

    @Test
    fun `边界是严格大于 正好用满不算超时`() {
        val budget = JwRunBudget(timeoutMs = 30_000)
        assertEquals(JwRunVerdict.RUNNING, budget.tick(0, waiting = false))
        assertEquals(JwRunVerdict.RUNNING, budget.tick(30_000, waiting = false))
        assertEquals(JwRunVerdict.SCRIPT_TIMEOUT, budget.tick(30_001, waiting = false))
    }
}
