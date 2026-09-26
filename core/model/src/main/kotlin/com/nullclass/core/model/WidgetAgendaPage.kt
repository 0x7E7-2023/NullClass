package com.nullclass.core.model

import kotlin.math.ceil

/** 页码从 0 开始；空课表也保留一页，避免出现 1/0。 */
data class WidgetAgendaPage(
    val rows: List<TodaySnapshot.TodayEntry>,
    val index: Int,
    val pageCount: Int,
    val totalRows: Int,
) {
    val hasPrevious: Boolean get() = index > 0
    val hasNext: Boolean get() = index + 1 < pageCount
}

fun paginateWidgetAgenda(
    snapshot: TodaySnapshot,
    nowMinuteOfDay: Int,
    rowsPerPage: Int,
    requestedPage: Int,
): WidgetAgendaPage {
    val rows = buildWidgetAgenda(snapshot, nowMinuteOfDay, Int.MAX_VALUE).rows
    val capacity = rowsPerPage.coerceAtLeast(1)
    val pages = if (rows.isEmpty()) 1 else (rows.size - 1) / capacity + 1
    val index = requestedPage.coerceIn(0, pages - 1)
    return WidgetAgendaPage(rows.drop(index * capacity).take(capacity), index, pages, rows.size)
}

/**
 * 今日课程小组件的纵向配额，与 Glance 侧的模板一一对应：
 * 外边距 → 标题栏（星期、日期与翻页按钮同一行）→ 间距 → 课程卡片，卡片之间隔 [rowGapDp]。
 * 改 TodayGlanceWidget 的模板时同步改这里。
 */
data class WidgetPageLayout(
    val rowsPerPage: Int,
    /**
     * 一张课程卡片至少要多高，不含卡片间距。只用来决定每页放几门：
     * Glance 侧按页等分列表区，实际卡片会被拉高到填满小组件（见 TodayWidgetContent）。
     */
    val rowHeightDp: Int,
    val rowGapDp: Int,
    /** 卡片内上下留白：色条和文字都在这之内。 */
    val rowPaddingDp: Int,
    val headerHeightDp: Int,
    val headerGapDp: Int,
    val paddingDp: Int,
    /** 两行卡片（课名 + 地点/状态，时间列带下课时间）；false 时只有一行课名。 */
    val showDetails: Boolean,
)

/** 翻页按钮的边长；标题栏至少这么高。 */
const val WIDGET_PAGE_BUTTON_DP = 28

fun widgetPageLayout(heightDp: Int, fontSize: WidgetFontSize, fontScale: Float = 1f): WidgetPageLayout {
    val text = fontSize.scale * fontScale.coerceAtLeast(0.5f) * LINE_HEIGHT
    // 3×2 这类矮尺寸把边距和间距收紧，给课程多留一点
    val roomy = heightDp >= ROOMY_HEIGHT_DP
    val padding = if (roomy) 14 else 10
    val headerGap = if (roomy) 10 else 6
    val rowGap = if (roomy) 6 else 4
    val header = maxOf(WIDGET_PAGE_BUTTON_DP, ceil(16 * text).toInt())
    val available = heightDp - padding * 2 - header - headerGap
    // 两行卡片：13sp 课名 + 10sp 地点；单行卡片：13sp 课名
    val detailedRow = ceil(23 * text).toInt() + DETAILED_ROW_PADDING * 2
    val compactRow = ceil(13 * text).toInt() + COMPACT_ROW_PADDING * 2
    val detailedCount = cardsThatFit(available, detailedRow, rowGap)
    val compactCount = cardsThatFit(available, compactRow, rowGap)
    // 两行卡片放得下至少两门，或者单行也不能多放，才用两行；否则宁可单行多排几门
    val details = detailedCount >= 2 || compactCount <= detailedCount
    return WidgetPageLayout(
        // RemoteViews 的普通 Column 最多容纳 10 个直接子项。
        rowsPerPage = (if (details) detailedCount else compactCount).coerceIn(1, 10),
        rowHeightDp = if (details) detailedRow else compactRow,
        rowGapDp = rowGap,
        rowPaddingDp = if (details) DETAILED_ROW_PADDING else COMPACT_ROW_PADDING,
        headerHeightDp = header,
        headerGapDp = headerGap,
        paddingDp = padding,
        showDetails = details,
    )
}

/** n 张卡片占 n × row + (n − 1) × gap：间距只夹在卡片之间，最后一张下面不留。 */
private fun cardsThatFit(available: Int, row: Int, gap: Int): Int =
    ((available + gap) / (row + gap)).coerceAtLeast(0)

private const val ROOMY_HEIGHT_DP = 180
private const val DETAILED_ROW_PADDING = 6
private const val COMPACT_ROW_PADDING = 5
