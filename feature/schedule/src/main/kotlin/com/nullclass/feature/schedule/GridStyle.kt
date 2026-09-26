package com.nullclass.feature.schedule

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nullclass.core.model.BlockBorderStyle
import com.nullclass.core.model.BlockTextAlign
import com.nullclass.core.model.PeriodTime
import com.nullclass.core.model.ScheduleAppearance
import com.nullclass.core.model.ScheduleBlock
import com.nullclass.core.model.TimelineGeometry

/** 单节行高的自动下限。课块高度 = 行高 × 跨节数。 */
internal val PeriodCellHeight = 56.dp

/**
 * 周课表画的时候直接用的外观：[ScheduleAppearance] 连同两个更早就有的显示开关
 * （「在课程卡片上显示上课时间」「显示网格线」），解析成 Dp / Color / 开关。
 *
 * 课表页与个性化设置页的预览都从 [gridStyleOf] 取，两边的自动值规则因此一定一致。
 */
@Immutable
internal data class GridStyle(
    /** 24 小时时间轴：一行一小时，卡片按实际上课时间定位。 */
    val timeline: Boolean,
    /** 时间轴每小时的高度（已解析自动值）。 */
    val timelineHourHeight: Dp,
    /** 节次模式用户定的单节行高；null = 自动（按可用高度撑满，不低于 [PeriodCellHeight]）。 */
    val fixedPeriodCellHeight: Dp?,
    /** 侧边栏宽度；表头左侧的留白与它相同，列才对得齐。 */
    val sidebarWidth: Dp,
    /** 表头固定高度；null = 按内容包裹。 */
    val headerHeight: Dp?,
    val showHeaderDates: Boolean,
    /** 节次模式的侧边栏里是否写起止时间（时间轴模式侧边栏是整点刻度，与它无关）。 */
    val showTimesInSidebar: Boolean,
    /** 课程卡片角上是否标起止时间。 */
    val showCornerTimes: Boolean,
    val showGridLines: Boolean,
    /** 表头、侧边栏的文字颜色；null = 跟随主题。 */
    val pageTextColor: Color?,
    /** 课程卡片的文字颜色；null = 跟随主题。 */
    val blockTextColor: Color?,
    val blockTextAlign: BlockTextAlign,
    val blockBorderStyle: BlockBorderStyle,
    val blockTextScale: Float,
    val blockCornerRadius: Dp,
    /** 卡片四周留出的空，默认 1.5dp。 */
    val blockSpacing: Dp,
    /** 卡片底色的不透明度 0..1。 */
    val blockOpacity: Float,
) {

    /** 网格的行数：时间轴 24 行（一小时一行），节次模式一节一行。 */
    fun rowCount(periodCount: Int): Int =
        if (timeline) TimelineGeometry.HOURS_PER_DAY else periodCount.coerceAtLeast(1)

    /**
     * 一行的高度。时间轴 = 每小时高度；节次模式 = 用户定的行高，没定就按 [availableHeight]
     * （顶栏完全展开时的净高）撑满，但不低于 [PeriodCellHeight]。
     */
    fun rowHeight(periodCount: Int, availableHeight: Dp): Dp = when {
        timeline -> timelineHourHeight
        fixedPeriodCellHeight != null -> fixedPeriodCellHeight
        else -> maxOf(PeriodCellHeight, availableHeight / rowCount(periodCount))
    }

    /**
     * 按最小行高排下来是否超出 [availableHeight] —— 超出才让顶栏随滚动收起。
     * 自动行高会撑满，所以拿下限比；用户定的行高与时间轴高度是死的，直接拿它比。
     */
    fun overflows(periodCount: Int, availableHeight: Dp): Boolean {
        val minimumRow = when {
            timeline -> timelineHourHeight
            else -> fixedPeriodCellHeight ?: PeriodCellHeight
        }
        return minimumRow * rowCount(periodCount) > availableHeight
    }
}

/** 侧边栏自动宽度：时间轴放整点刻度；节次模式写起止时间时 44dp，只有节次号时收窄。 */
private val TimelineSidebarWidth = 36.dp
private val PeriodSidebarWidthWithTimes = 44.dp
private val PeriodSidebarWidthNumbersOnly = 28.dp

internal fun gridStyleOf(
    appearance: ScheduleAppearance,
    showTimeInCards: Boolean,
    showGridLines: Boolean,
): GridStyle {
    val showTimesInSidebar = !appearance.timelineMode && !appearance.hidePeriodTimes && !showTimeInCards
    val autoSidebar = when {
        appearance.timelineMode -> TimelineSidebarWidth
        showTimesInSidebar -> PeriodSidebarWidthWithTimes
        else -> PeriodSidebarWidthNumbersOnly
    }
    return GridStyle(
        timeline = appearance.timelineMode,
        timelineHourHeight =
            (appearance.timelineHourHeightDp ?: ScheduleAppearance.DEFAULT_TIMELINE_HOUR_HEIGHT_DP).dp,
        fixedPeriodCellHeight = appearance.periodCellHeightDp?.dp,
        sidebarWidth = appearance.sidebarWidthDp?.dp ?: autoSidebar,
        headerHeight = appearance.headerHeightDp?.dp,
        showHeaderDates = !appearance.hideHeaderDates,
        showTimesInSidebar = showTimesInSidebar,
        showCornerTimes = showTimeInCards && !appearance.hidePeriodTimes,
        showGridLines = showGridLines,
        pageTextColor = appearance.pageTextColor?.let(::Color),
        blockTextColor = appearance.blockTextColor?.let(::Color),
        blockTextAlign = appearance.blockTextAlign,
        blockBorderStyle = appearance.blockBorderStyle,
        blockTextScale = appearance.blockTextScalePercent / 100f,
        blockCornerRadius = appearance.blockCornerRadiusDp.dp,
        blockSpacing = appearance.blockSpacingDp.dp,
        blockOpacity = appearance.blockOpacityPercent / 100f,
    )
}

/**
 * 一张卡片在纵轴上的位置，单位是「行」：节次模式一行一节，时间轴一行一小时。
 * 以行为单位，两种纵轴的定位、画线、避让才能共用一套代码。
 */
internal data class RowSpan(val start: Float, val length: Float) {
    val end: Float get() = start + length
}

/**
 * [block] 在纵轴上的位置。节次模式按节次号；时间轴按实际上课时间
 * （首节不在节次表里时没有时间可依，返回 null，不画）。
 */
internal fun rowSpanOf(block: ScheduleBlock, periodTimes: List<PeriodTime>, timeline: Boolean): RowSpan? =
    if (timeline) {
        TimelineGeometry.minuteSpan(block, periodTimes)
            ?.let { RowSpan(it.startMinute / 60f, it.lengthMinutes / 60f) }
    } else {
        RowSpan((block.startPeriod - 1).toFloat(), block.periodCount.toFloat())
    }

/**
 * 第 i 条横线（0..[rowCount]）是否从某张卡片**中间**穿过，是的话这一列上跳过它。
 *
 * 卡片四周留了空，线照画的话会从卡片两侧各露出一小截，看着像卡片被横着划了一刀 ——
 * 比整条线压过去还奇怪；线是给空时段看的参照，不该把一节跨多节的课切成几段。
 * 恰好落在卡片上沿或下沿的不算；首尾两条是网格边框，不参与。
 */
internal fun coveredBoundaries(spans: Collection<RowSpan>, rowCount: Int): BooleanArray {
    val covered = BooleanArray(rowCount + 1)
    for (span in spans) {
        for (boundary in 1 until rowCount) {
            if (boundary > span.start && boundary < span.end) covered[boundary] = true
        }
    }
    return covered
}
