package com.nullclass.core.ui.i18n

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.nullclass.core.model.ScheduleBlock
import com.nullclass.core.model.WeekType
import com.nullclass.core.ui.R

/**
 * 课表在各界面共用的文案（周次、星期、节次、倒计时）。
 *
 * 这些文案原先由纯 Kotlin 模块 `:core:model` 的 `ScheduleFormat` 直接拼中文，无法翻译。
 * 现在 `:core:model` 只保留与语言无关的部分（如 `minuteLabel` 的「8:00」），
 * 需要文字的一律经由本对象从资源取。
 *
 * 非 Compose 场景（通知、日历导出、Glance 小组件）调用带 [Context] 的方法；
 * Compose 场景用本文件末尾的同名 `@Composable` 包装，省去手动取 Context。
 */
object ScheduleText {

    /** ISO 星期几（1=周一）→「周一」。越界返回「?」。 */
    fun dayOfWeek(context: Context, dayOfWeek: Int): String =
        context.resources.getStringArray(R.array.fmt_day_of_week)
            .getOrElse(dayOfWeek - 1) { context.getString(R.string.fmt_day_of_week_unknown) }

    /** 单字星期「一」。周视图表头、每周起始日选择器等一排七格的窄处用。 */
    fun dayOfWeekShort(context: Context, dayOfWeek: Int): String =
        context.resources.getStringArray(R.array.fmt_day_of_week_short)
            .getOrElse(dayOfWeek - 1) { context.getString(R.string.fmt_day_of_week_unknown) }

    /** 「第1-16周」；恰好一周时「第3周」。 */
    fun weekRange(context: Context, block: ScheduleBlock): String =
        if (block.startWeek == block.endWeek) {
            context.getString(R.string.fmt_week_single, block.startWeek)
        } else {
            context.getString(R.string.fmt_week_range, block.startWeek, block.endWeek)
        }

    /** 「单周」/「双周」；[WeekType.ALL] 返回空串。 */
    fun weekType(context: Context, weekType: WeekType): String = when (weekType) {
        WeekType.ALL -> ""
        WeekType.ODD -> context.getString(R.string.fmt_week_type_odd)
        WeekType.EVEN -> context.getString(R.string.fmt_week_type_even)
    }

    /**
     * 窄处的紧凑周次：「1-16周」「3周」，单双周缀在后面（「1-16周·单」）。
     *
     * 比 [weekRange] 少一个「第」—— 周视图灰块那一格只有几十 dp 宽，
     * 「第1-16周」会被省略号吃掉尾巴，剩个「第1-1…」等于没说。
     */
    fun weekSpan(context: Context, block: ScheduleBlock): String {
        val span = if (block.startWeek == block.endWeek) {
            context.getString(R.string.fmt_week_span_single, block.startWeek)
        } else {
            context.getString(R.string.fmt_week_span_range, block.startWeek, block.endWeek)
        }
        val type = when (block.weekType) {
            WeekType.ALL -> return span
            WeekType.ODD -> context.getString(R.string.fmt_week_type_odd_short)
            WeekType.EVEN -> context.getString(R.string.fmt_week_type_even_short)
        }
        return context.getString(R.string.fmt_week_span_with_type, span, type)
    }

    /** 「3-4节」；恰好一节时「3节」。 */
    fun periodRange(context: Context, block: ScheduleBlock): String =
        if (block.startPeriod == block.endPeriod) {
            context.getString(R.string.fmt_period_single, block.startPeriod)
        } else {
            context.getString(R.string.fmt_period_range, block.startPeriod, block.endPeriod)
        }

    /** 上课中倒计时的时长：「45 分钟」「1 小时」「1 小时 20 分钟」（「还剩」前缀由调用方拼）。 */
    fun remaining(context: Context, minutes: Int): String {
        val hours = minutes / 60
        val mins = minutes % 60
        return when {
            hours == 0 -> context.getString(R.string.fmt_remaining_minutes, mins)
            mins == 0 -> context.getString(R.string.fmt_remaining_hours, hours)
            else -> context.getString(R.string.fmt_remaining_hours_minutes, hours, mins)
        }
    }

    /** 完整一行：「第1-16周 · 单周 · 周二 · 3-4节 · A101」。 */
    fun blockSummary(context: Context, block: ScheduleBlock): String = buildList {
        add(weekRange(context, block))
        weekType(context, block.weekType).takeIf { it.isNotEmpty() }?.let { add(it) }
        add(dayOfWeek(context, block.dayOfWeek))
        add(periodRange(context, block))
        block.location?.takeIf { it.isNotBlank() }?.let { add(it) }
    }.joinToString(context.getString(R.string.common_separator))
}

@Composable
fun dayOfWeekLabel(dayOfWeek: Int): String = ScheduleText.dayOfWeek(LocalContext.current, dayOfWeek)

@Composable
fun dayOfWeekShortLabel(dayOfWeek: Int): String = ScheduleText.dayOfWeekShort(LocalContext.current, dayOfWeek)

@Composable
fun weekRangeLabel(block: ScheduleBlock): String = ScheduleText.weekRange(LocalContext.current, block)

@Composable
fun weekTypeLabel(weekType: WeekType): String = ScheduleText.weekType(LocalContext.current, weekType)

@Composable
fun weekSpanLabel(block: ScheduleBlock): String = ScheduleText.weekSpan(LocalContext.current, block)

@Composable
fun periodRangeLabel(block: ScheduleBlock): String = ScheduleText.periodRange(LocalContext.current, block)

@Composable
fun remainingLabel(minutes: Int): String = ScheduleText.remaining(LocalContext.current, minutes)

@Composable
fun blockSummaryLabel(block: ScheduleBlock): String = ScheduleText.blockSummary(LocalContext.current, block)
