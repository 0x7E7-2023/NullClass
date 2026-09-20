package com.nullclass.feature.schedule

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nullclass.core.model.DayOverrides
import com.nullclass.core.model.ScheduleFormat
import com.nullclass.core.model.Term
import java.time.LocalDate

/**
 * 表头：星期 + 日期。与 WeekGrid 的列宽严格对齐（spacer + N × weight(1f)）。今日列带高亮条上段，下段在 WeekGrid。
 *
 * 点一列打开该日的调课（串课）面板；被串过的列在日期下多一行「上 周五」，
 * 与下方格子里画的课对得上。
 *
 * @param dayOverrides 串课表 date → source date，见 [DayOverrides]
 */
@Composable
internal fun WeekHeader(
    term: Term,
    week: Int,
    weekDays: List<Int>,
    todayDayOfWeek: Int?,
    showTimeInCards: Boolean,
    modifier: Modifier = Modifier,
    dayOverrides: Map<Long, Long> = emptyMap(),
    onDayClick: ((epochDay: Long) -> Unit)? = null,
) {
    Row(modifier = modifier.fillMaxWidth()) {
        Spacer(modifier = Modifier.width(periodColumnWidth(showTimeInCards)))
        for (day in weekDays) {
            val epochDay = term.epochDayOf(week, day)
            val date = LocalDate.ofEpochDay(epochDay)
            val isToday = todayDayOfWeek == day
            val sourceEpochDay = dayOverrides[epochDay]
            Column(
                modifier = Modifier
                    .weight(1f)
                    .let { base -> onDayClick?.let { base.clickable { it(epochDay) } } ?: base }
                    .padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = ScheduleFormat.dayOfWeekShortLabel(day),
                    fontSize = 11.sp,
                    color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                )
                Text(
                    text = "${date.monthValue}/${date.dayOfMonth}",
                    fontSize = 10.sp,
                    color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                )
                if (sourceEpochDay != null) {
                    val source = LocalDate.ofEpochDay(sourceEpochDay)
                    Text(
                        // 前缀是「调」而不是「上」：「上周五」在中文里会被读成「上一周的周五」，
                        // 而这里说的是「上（动词）周五的课」。间隔号把动词和宾语隔开，两义不再撞车。
                        // 来源日就在同一周内时写星期；跨周才补月/日（同一列宽里放不下两者）。
                        text = if (term.weekOf(sourceEpochDay) == week) {
                            "调·周${ScheduleFormat.dayOfWeekShortLabel(source.dayOfWeek.value)}"
                        } else {
                            "调·${source.monthValue}/${source.dayOfMonth}"
                        },
                        fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.tertiary,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
