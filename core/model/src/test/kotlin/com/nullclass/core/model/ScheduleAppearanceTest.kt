package com.nullclass.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ScheduleAppearanceTest {

    @Test
    fun `默认值就是加这项设置之前的样子`() {
        val default = ScheduleAppearance()
        assertEquals(false, default.timelineMode)
        assertEquals(false, default.hidePeriodTimes)
        assertEquals(false, default.hideHeaderDates)
        assertNull(default.pageTextColor)
        assertNull(default.periodCellHeightDp)
        assertNull(default.timelineHourHeightDp)
        assertNull(default.sidebarWidthDp)
        assertNull(default.headerHeightDp)
        assertNull(default.blockTextColor)
        assertEquals(BlockTextAlign.CENTER, default.blockTextAlign)
        assertEquals(BlockBorderStyle.SOLID, default.blockBorderStyle)
        assertEquals(100, default.blockTextScalePercent)
        assertEquals(8, default.blockCornerRadiusDp)
        assertEquals(1.5f, default.blockSpacingDp)
        assertEquals(100, default.blockOpacityPercent)
        // 默认值本身必须是合法值，否则每次读偏好都会被悄悄改掉
        assertEquals(default, default.sanitized())
    }

    @Test
    fun `越界的尺寸收拢到范围边界`() {
        val wild = ScheduleAppearance(
            periodCellHeightDp = 0,
            timelineHourHeightDp = 10_000,
            sidebarWidthDp = -5,
            headerHeightDp = 500,
            blockCornerRadiusDp = 99,
        ).sanitized()
        assertEquals(ScheduleAppearance.PERIOD_CELL_HEIGHT_RANGE.first, wild.periodCellHeightDp)
        assertEquals(ScheduleAppearance.TIMELINE_HOUR_HEIGHT_RANGE.last, wild.timelineHourHeightDp)
        assertEquals(ScheduleAppearance.SIDEBAR_WIDTH_RANGE.first, wild.sidebarWidthDp)
        assertEquals(ScheduleAppearance.HEADER_HEIGHT_RANGE.last, wild.headerHeightDp)
        assertEquals(ScheduleAppearance.CORNER_RADIUS_RANGE.last, wild.blockCornerRadiusDp)
    }

    @Test
    fun `自动（null）不会被收拢成某个固定值`() {
        val auto = ScheduleAppearance().sanitized()
        assertNull(auto.periodCellHeightDp)
        assertNull(auto.timelineHourHeightDp)
        assertNull(auto.sidebarWidthDp)
        assertNull(auto.headerHeightDp)
    }

    @Test
    fun `百分比按步长取整并收拢`() {
        val s = ScheduleAppearance(blockTextScalePercent = 112, blockOpacityPercent = 43).sanitized()
        assertEquals(110, s.blockTextScalePercent)
        assertEquals(45, s.blockOpacityPercent)
        val out = ScheduleAppearance(blockTextScalePercent = 10, blockOpacityPercent = 250).sanitized()
        assertEquals(70, out.blockTextScalePercent)
        assertEquals(100, out.blockOpacityPercent)
    }

    @Test
    fun `外边距按半 dp 取整，NaN 回落默认`() {
        assertEquals(2.5f, ScheduleAppearance(blockSpacingDp = 2.3f).sanitized().blockSpacingDp)
        assertEquals(0f, ScheduleAppearance(blockSpacingDp = -3f).sanitized().blockSpacingDp)
        assertEquals(8f, ScheduleAppearance(blockSpacingDp = 20f).sanitized().blockSpacingDp)
        assertEquals(1.5f, ScheduleAppearance(blockSpacingDp = Float.NaN).sanitized().blockSpacingDp)
    }

    @Test
    fun `自定义颜色补满不透明度，免得字看不见`() {
        val s = ScheduleAppearance(pageTextColor = 0x00FFFFFF, blockTextColor = 0x40123456).sanitized()
        assertEquals(0xFFFFFFFF.toInt(), s.pageTextColor)
        assertEquals(0xFF123456.toInt(), s.blockTextColor)
    }

    @Test
    fun `枚举名称可还原，未知值回落默认`() {
        BlockTextAlign.entries.forEach { assertEquals(it, BlockTextAlign.fromName(it.name)) }
        BlockBorderStyle.entries.forEach { assertEquals(it, BlockBorderStyle.fromName(it.name)) }
        assertEquals(BlockTextAlign.CENTER, BlockTextAlign.fromName(null))
        assertEquals(BlockTextAlign.CENTER, BlockTextAlign.fromName("JUSTIFY"))
        assertEquals(BlockBorderStyle.SOLID, BlockBorderStyle.fromName(null))
        assertEquals(BlockBorderStyle.SOLID, BlockBorderStyle.fromName("DOTTED"))
    }
}
