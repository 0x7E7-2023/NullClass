package com.nullclass.feature.schedule

import androidx.compose.ui.unit.dp
import com.nullclass.core.model.DefaultPeriodTimes
import com.nullclass.core.model.ScheduleAppearance
import com.nullclass.core.model.ScheduleBlock
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GridStyleTest {

    private val default = ScheduleAppearance()

    @Test
    fun `默认外观下侧边栏宽度与加设置前一致`() {
        // 节次列内写时间 44dp；时间标在课块上时收窄为 28dp
        assertEquals(44.dp, gridStyleOf(default, showTimeInCards = false, showGridLines = true).sidebarWidth)
        assertEquals(28.dp, gridStyleOf(default, showTimeInCards = true, showGridLines = true).sidebarWidth)
    }

    @Test
    fun `隐藏节次时间时侧边栏同样收窄，角标时间也不显示`() {
        val hidden = default.copy(hidePeriodTimes = true)
        val inColumn = gridStyleOf(hidden, showTimeInCards = false, showGridLines = true)
        assertEquals(28.dp, inColumn.sidebarWidth)
        assertFalse(inColumn.showTimesInSidebar)
        val inCards = gridStyleOf(hidden, showTimeInCards = true, showGridLines = true)
        assertFalse(inCards.showCornerTimes)
    }

    @Test
    fun `时间轴模式侧边栏放整点刻度，隐藏节次时间只影响角标`() {
        val style = gridStyleOf(default.copy(timelineMode = true), showTimeInCards = true, showGridLines = true)
        assertEquals(36.dp, style.sidebarWidth)
        assertFalse(style.showTimesInSidebar)
        assertTrue(style.showCornerTimes)
    }

    @Test
    fun `用户定的侧边栏宽度与表头高度压过自动值`() {
        val style = gridStyleOf(
            default.copy(sidebarWidthDp = 60, headerHeightDp = 30),
            showTimeInCards = false,
            showGridLines = true,
        )
        assertEquals(60.dp, style.sidebarWidth)
        assertEquals(30.dp, style.headerHeight)
        assertNull(gridStyleOf(default, showTimeInCards = false, showGridLines = true).headerHeight)
    }

    @Test
    fun `节次模式自动行高按屏高撑满且不低于 56dp，与加设置前的公式一致`() {
        val style = gridStyleOf(default, showTimeInCards = false, showGridLines = true)
        // 12 节、净高 1200dp → 100dp；净高 400dp → 撑不开，取 56dp 下限
        assertEquals(100.dp, style.rowHeight(12, 1200.dp))
        assertEquals(56.dp, style.rowHeight(12, 400.dp))
        // 可滚判定拿 56dp 下限比
        assertFalse(style.overflows(12, 672.dp))
        assertTrue(style.overflows(12, 671.dp))
    }

    @Test
    fun `节次模式定了行高就用定值，可滚判定也拿定值比`() {
        val style = gridStyleOf(default.copy(periodCellHeightDp = 80), showTimeInCards = false, showGridLines = true)
        assertEquals(80.dp, style.rowHeight(12, 1200.dp))
        assertEquals(80.dp, style.rowHeight(12, 100.dp))
        assertFalse(style.overflows(12, 960.dp))
        assertTrue(style.overflows(12, 959.dp))
    }

    @Test
    fun `时间轴一行一小时，行高与节次模式的行高互不串用`() {
        val appearance = default.copy(timelineMode = true, periodCellHeightDp = 120)
        val auto = gridStyleOf(appearance, showTimeInCards = false, showGridLines = true)
        assertEquals(24, auto.rowCount(12))
        assertEquals(60.dp, auto.rowHeight(12, 2000.dp))
        val fixed = gridStyleOf(appearance.copy(timelineHourHeightDp = 40), showTimeInCards = false, showGridLines = true)
        assertEquals(40.dp, fixed.rowHeight(12, 2000.dp))
        assertTrue(fixed.overflows(12, 959.dp))
        assertFalse(fixed.overflows(12, 960.dp))
    }

    @Test
    fun `卡片样式按百分比换算`() {
        val style = gridStyleOf(
            default.copy(blockTextScalePercent = 120, blockOpacityPercent = 40, blockSpacingDp = 3f, blockCornerRadiusDp = 12),
            showTimeInCards = false,
            showGridLines = true,
        )
        assertEquals(1.2f, style.blockTextScale)
        assertEquals(0.4f, style.blockOpacity)
        assertEquals(3.dp, style.blockSpacing)
        assertEquals(12.dp, style.blockCornerRadius)
        val plain = gridStyleOf(default, showTimeInCards = false, showGridLines = true)
        assertEquals(1f, plain.blockTextScale)
        assertEquals(1f, plain.blockOpacity)
        assertEquals(1.5.dp, plain.blockSpacing)
        assertEquals(8.dp, plain.blockCornerRadius)
        assertNull(plain.pageTextColor)
        assertNull(plain.blockTextColor)
    }

    private fun block(start: Int, end: Int) =
        ScheduleBlock(startWeek = 1, endWeek = 16, dayOfWeek = 1, startPeriod = start, endPeriod = end)

    @Test
    fun `节次模式按节次号定位，时间轴按上课时间定位`() {
        val periods = DefaultPeriodTimes.create("t")
        assertEquals(RowSpan(2f, 2f), rowSpanOf(block(3, 4), periods, timeline = false))
        // 第 1 节 8:00 起、第 2 节 9:40 止 → 8.0 行起、跨 100 分钟
        val span = rowSpanOf(block(1, 2), periods, timeline = true)!!
        assertEquals(8f, span.start)
        assertEquals(100f / 60f, span.length)
        assertNull(rowSpanOf(block(13, 13), periods, timeline = true))
    }

    @Test
    fun `只有从卡片中间穿过的横线才算被盖住，与加设置前的节次口径一致`() {
        // 节次模式 3-4 节：只有第 3、4 节之间那条（边界 3）被盖住
        val period = coveredBoundaries(listOf(RowSpan(2f, 2f)), rowCount = 12)
        assertEquals(listOf(3), period.indices.filter { period[it] })
        // 时间轴 8:00-9:40：9 点那条从中间穿过；8 点正好是上沿，不算
        val timeline = coveredBoundaries(listOf(RowSpan(8f, 100f / 60f)), rowCount = 24)
        assertContentEquals(BooleanArray(25).also { it[9] = true }, timeline)
    }

    @Test
    fun `首尾两条是网格边框，不参与避让`() {
        // 超出节次表的课块（第 11-13 节，表里只有 12 节）不会把最底下那条边框吃掉
        val covered = coveredBoundaries(listOf(RowSpan(10f, 3f)), rowCount = 12)
        assertEquals(listOf(11), covered.indices.filter { covered[it] })
        val allDay = coveredBoundaries(listOf(RowSpan(0f, 24f)), rowCount = 24)
        assertFalse(allDay[0])
        assertFalse(allDay[24])
    }
}
