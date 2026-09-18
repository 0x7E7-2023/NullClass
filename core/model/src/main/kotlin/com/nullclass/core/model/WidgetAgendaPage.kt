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

/** 与 Glance 行模板共用高度：分页按钮有固定配额，不再挤占课程或溢出底部。 */
data class WidgetPageLayout(
    val rowsPerPage: Int,
    val rowHeightDp: Int,
    val headerHeightDp: Int,
    val controlsHeightDp: Int,
    val paddingDp: Int,
    val inlinePager: Boolean,
    val showDetails: Boolean,
)

fun widgetPageLayout(heightDp: Int, fontSize: WidgetFontSize, fontScale: Float = 1f): WidgetPageLayout {
    val text = fontSize.scale * fontScale.coerceAtLeast(0.5f) * LINE_HEIGHT
    val inlinePager = heightDp < 180
    val padding = if (inlinePager) 8 else 12
    // 页码下保留一行更新时间（10sp + 9sp），紧凑布局的标题行也要容纳它。
    val controls = maxOf(32, ceil(19 * text + 2).toInt())
    val header = maxOf(32, ceil(13 * text + 8).toInt(), if (inlinePager) controls else 0)
    val footer = if (inlinePager) 0 else controls + 8
    val available = heightDp - padding * 2 - header - 8 - footer - 4
    val detailedRow = ceil(22 * text + 12).toInt()
    val details = available >= detailedRow
    val row = if (details) detailedRow else ceil(12 * text + 8).toInt()
    return WidgetPageLayout(
        // RemoteViews 的普通 Column 最多容纳 10 个直接子项。
        rowsPerPage = (available / row).coerceIn(1, 10),
        rowHeightDp = row,
        headerHeightDp = header,
        controlsHeightDp = controls,
        paddingDp = padding,
        inlinePager = inlinePager,
        showDetails = details,
    )
}
