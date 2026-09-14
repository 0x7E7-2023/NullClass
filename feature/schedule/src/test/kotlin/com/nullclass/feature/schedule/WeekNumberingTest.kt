package com.nullclass.feature.schedule

import com.nullclass.core.model.Term
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 「翻到的周次」跟着周次编号走：教务导入新学期之后它必须作废，否则周视图会停在
 * 上一个学期翻到的周次上——那一周不上的课块（下半学期开课、单双周）整片消失，
 * 看着像课没渲染出来；今日页按今天现算周次，所以只有周视图不对。
 */
class WeekNumberingTest {

    private fun term(id: String, firstDay: Long = 20_000, weeks: Int = 20) =
        Term(id = id, name = "term-$id", firstDayEpochDay = firstDay, totalWeeks = weeks)

    private val lastTerm = WeekNumbering.of(term("t-old", firstDay = 19_800))

    @Test
    fun `同一个学期里翻到的周次照常作数`() {
        val selected = SelectedWeek(lastTerm, week = 5)

        assertEquals(5, selected.weekIn(lastTerm))
    }

    @Test
    fun `导入新学期 - 上一个学期翻到的第 5 周作废`() {
        // 教务导入把当前学期换成新的（ImportAligner 激活开学日最新的那个）
        val selected = SelectedWeek(lastTerm, week = 5)

        assertNull(
            selected.weekIn(WeekNumbering.of(term("t-new", firstDay = 20_696))),
            "上学期翻到的第 5 周带进了新学期：那一周不上的课块会整片消失",
        )
    }

    @Test
    fun `开学日被教务纠正 - 同一学期的翻页选择也算作废`() {
        // 「一键刷新」纠正开学日 3 天：同一个学期 id，但第 5 周落在了另一批日期上
        val selected = SelectedWeek(WeekNumbering.of(term("t1", firstDay = 20_693)), week = 5)

        assertNull(selected.weekIn(WeekNumbering.of(term("t1", firstDay = 20_696))))
    }

    @Test
    fun `没翻过时没有选择可作废`() {
        assertNull(null.weekIn(lastTerm))
    }

    @Test
    fun `作废之后落到今天所在的周`() {
        val numbering = WeekNumbering.of(term("t-new", firstDay = 20_696))
        val selected = SelectedWeek(lastTerm, week = 5).weekIn(numbering)

        // 今天在新学期第 2 周：显示第 2 周，而不是上学期翻到的第 5 周
        assertEquals(2, resolveVisibleWeek(selected, todayWeek = 2, totalWeeks = 20))
    }

    @Test
    fun `显示的周次夹在学期范围内 - 上一个学期翻到的第 25 周遇到 18 周的新学期`() {
        assertEquals(18, resolveVisibleWeek(selected = 25, todayWeek = 3, totalWeeks = 18))
        assertEquals(5, resolveVisibleWeek(selected = 5, todayWeek = 3, totalWeeks = 18))
    }

    @Test
    fun `没翻过时跟随今天，今天不在学期内落第 1 周`() {
        assertEquals(7, resolveVisibleWeek(selected = null, todayWeek = 7, totalWeeks = 20))
        assertEquals(1, resolveVisibleWeek(selected = null, todayWeek = null, totalWeeks = 20))
    }

    @Test
    fun `单周学期不越界`() {
        assertEquals(1, resolveVisibleWeek(selected = 9, todayWeek = null, totalWeeks = 1))
    }
}
