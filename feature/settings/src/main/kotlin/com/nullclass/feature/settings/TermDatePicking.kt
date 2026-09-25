package com.nullclass.feature.settings

import android.content.Context
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SelectableDates
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.nullclass.core.ui.i18n.ScheduleText
import com.nullclass.core.model.Term
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 「挑学期里的某一天」这件事的共用口径：调课页与快速删课页都要一个限定在学期内的
 * 日期选择器，两边的可选范围、默认停靠月份和日期文案必须一致。
 */

/** DatePicker 的毫秒口径是 UTC 零点。 */
internal const val MILLIS_PER_DAY = 86_400_000L

/** DatePicker 的毫秒 → epoch day。向下取整而不是整除：1970 年前的日期会被整除截断成后一天。 */
internal fun epochDayOfMillis(utcTimeMillis: Long): Long = Math.floorDiv(utcTimeMillis, MILLIS_PER_DAY)

/** 「10月11日 · 周六」；跨年时补年份（与跳过日期列表同一口径）。 */
internal fun dayLabel(context: Context, epochDay: Long): String {
    val date = LocalDate.ofEpochDay(epochDay)
    val pattern = DateTimeFormatter.ofPattern(context.getString(R.string.settings_month_day_pattern))
    val monthDay = date.format(pattern)
    val weekday = ScheduleText.dayOfWeek(context, date.dayOfWeek.value)
    return if (date.year != LocalDate.now().year) {
        context.getString(R.string.settings_day_label_with_year, date.year, monthDay, weekday)
    } else {
        context.getString(R.string.settings_day_label, monthDay, weekday)
    }
}

/** Compose 里用的同一口径。 */
@Composable
internal fun dayLabel(epochDay: Long): String = dayLabel(LocalContext.current, epochDay)

/** 日期选择器默认停在哪个月：今天在学期内就是今天，否则开学那天。 */
internal fun defaultDisplayedDay(term: Term): Long {
    val today = LocalDate.now().toEpochDay()
    return if (term.weekOf(today) != null) today else term.firstDayEpochDay
}

/** 日期选择器限制在学期覆盖的日子内（学期外没有课表可看，也没有课可删）。 */
@OptIn(ExperimentalMaterial3Api::class)
internal fun termSelectableDates(term: Term): SelectableDates = object : SelectableDates {
    override fun isSelectableDate(utcTimeMillis: Long): Boolean =
        term.weekOf(epochDayOfMillis(utcTimeMillis)) != null

    override fun isSelectableYear(year: Int): Boolean =
        year in LocalDate.ofEpochDay(term.firstDayEpochDay).year..
            LocalDate.ofEpochDay(term.firstDayEpochDay + term.totalWeeks * 7L - 1).year
}
