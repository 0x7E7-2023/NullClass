package com.nullclass.core.model

/**
 * 今日课程小组件的可见列表：丢掉已上完的、同课连堂合并、按行数配额截断。
 *
 * 应用内今日 Tab 仍用完整 [TodaySnapshot.blocks]；桌面只有固定像素，必须主动取舍。
 */
data class WidgetAgenda(
    val rows: List<TodaySnapshot.TodayEntry>,
    /** 配额之外还未上的课（合并后计）。0 表示全部可见。 */
    val hiddenUpcoming: Int,
)

/**
 * @param maxRows 最多展示几门（含上课中 / 下一节）。小于 1 时按 1 计。
 */
fun buildWidgetAgenda(
    snapshot: TodaySnapshot,
    nowMinuteOfDay: Int,
    maxRows: Int,
): WidgetAgenda {
    val cap = maxRows.coerceAtLeast(1)
    val upcoming = snapshot.blocks.filter { nowMinuteOfDay < it.endMinuteOfDay }
    val merged = mergeConsecutiveSameCourse(upcoming)
    if (merged.size <= cap) {
        return WidgetAgenda(rows = merged, hiddenUpcoming = 0)
    }
    return WidgetAgenda(
        rows = merged.take(cap),
        hiddenUpcoming = merged.size - cap,
    )
}

/** 节次相邻（含重叠）的同一门课合成一行，教务拆成 1-2 / 3-4 的连堂会并掉。 */
internal fun mergeConsecutiveSameCourse(
    entries: List<TodaySnapshot.TodayEntry>,
): List<TodaySnapshot.TodayEntry> {
    if (entries.size <= 1) return entries
    val out = ArrayList<TodaySnapshot.TodayEntry>(entries.size)
    var acc = entries[0]
    for (i in 1 until entries.size) {
        val next = entries[i]
        if (canMerge(acc, next)) {
            acc = acc.mergeEnd(next)
        } else {
            out.add(acc)
            acc = next
        }
    }
    out.add(acc)
    return out
}

private fun canMerge(a: TodaySnapshot.TodayEntry, b: TodaySnapshot.TodayEntry): Boolean =
    a.placed.course.id == b.placed.course.id &&
        b.placed.block.startPeriod <= a.placed.block.endPeriod + 1

private fun TodaySnapshot.TodayEntry.mergeEnd(next: TodaySnapshot.TodayEntry): TodaySnapshot.TodayEntry {
    val loc = placed.block.location?.takeIf { it.isNotBlank() } ?: next.placed.block.location
    return copy(
        endMinuteOfDay = next.endMinuteOfDay,
        endTime = next.endTime,
        placed = placed.copy(
            block = placed.block.copy(
                endPeriod = next.placed.block.endPeriod,
                location = loc,
            ),
        ),
    )
}

/**
 * 今日小组件按高度和字号能放下几门课。只按课行高度配额；
 * 算完后剩下的空白给「还剩 n 节」另起一行，不为此少排一门课。
 *
 * Glance Text 是 sp：实际 dp ≈ sp × [WidgetFontSize.scale] × 系统 [fontScale] × [LINE_HEIGHT]。
 * [LINE_HEIGHT] 覆盖 RemoteViews 默认 includeFontPadding 和中文字体行盒，按 1sp=1dp 会少算约四成。
 * 常数与 Glance 侧 padding 12 / 标题 13sp+4+1+4 / 行 12+10sp+竖向 8 / 脚注 11sp+4 对齐。改行模板时同步改这里。
 *
 * @param fontScale [android.content.res.Configuration.fontScale]，默认 1。
 */
fun widgetCourseRowBudget(
    widgetHeightDp: Int,
    fontSize: WidgetFontSize,
    fontScale: Float = 1f,
): Int {
    val metrics = WidgetChrome(fontSize, fontScale)
    val available = widgetHeightDp - WIDGET_PAD_V - SAFETY - metrics.header
    return (available / metrics.row).toInt().coerceAtLeast(1)
}

/**
 * [rowCount] 门课排完后，剩余高度够不够在列表下另起一行放下 11sp 的「还剩 n 节」（不是一门课的行高）。
 * 列表是否另起一行由 UI 决定；本函数只判断余量，不为脚注少排一门课。
 */
fun widgetFooterFits(
    widgetHeightDp: Int,
    fontSize: WidgetFontSize,
    fontScale: Float,
    rowCount: Int,
): Boolean {
    val metrics = WidgetChrome(fontSize, fontScale)
    val used = WIDGET_PAD_V + SAFETY + metrics.header + metrics.row * rowCount + metrics.footer
    return used <= widgetHeightDp
}

private class WidgetChrome(fontSize: WidgetFontSize, fontScale: Float) {
    private val text = fontSize.scale * fontScale.coerceAtLeast(0.5f) * LINE_HEIGHT
    val header = HEADER_TEXT * text + HEADER_CHROME
    val row = ROW_TEXT * text + ROW_CHROME
    val footer = FOOTER_TEXT * text + FOOTER_CHROME
}

private const val WIDGET_PAD_V = 24f
private const val SAFETY = 8f
/** RemoteViews TextView 行盒相对字号的倍数（含 includeFontPadding + CJK）。 */
internal const val LINE_HEIGHT = 1.6f
private const val HEADER_TEXT = 13f
private const val HEADER_CHROME = 9f
private const val ROW_TEXT = 22f
private const val ROW_CHROME = 8f
private const val FOOTER_TEXT = 11f
private const val FOOTER_CHROME = 4f
