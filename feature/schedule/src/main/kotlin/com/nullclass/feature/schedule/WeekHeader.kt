package com.nullclass.feature.schedule

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import com.nullclass.core.model.ScheduleFormat
import com.nullclass.core.model.Term
import java.time.LocalDate

/** 表头：星期 + 日期。与 WeekGrid 的列宽严格对齐（spacer + N × weight(1f)）。今日列带高亮条上段，下段在 WeekGrid。 */
@Composable
internal fun WeekHeader(
    term: Term,
    week: Int,
    weekDays: List<Int>,
    todayDayOfWeek: Int?,
    showTimeInCards: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth()) {
        Spacer(modifier = Modifier.width(periodColumnWidth(showTimeInCards)))
        for (day in weekDays) {
            val date = LocalDate.ofEpochDay(term.epochDayOf(week, day))
            val isToday = todayDayOfWeek == day
            Column(
                modifier = Modifier
                    .weight(1f)
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
            }
        }
    }
}
