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
import com.nullclass.core.model.Term
import java.time.LocalDate

/** 表头：一~日 + 日期。与 WeekGrid 的列宽严格对齐（spacer + 7 × weight(1f)）。今日列带高亮条上段，下段在 WeekGrid。 */
@Composable
internal fun WeekHeader(
    term: Term,
    week: Int,
    todayDayOfWeek: Int?,
    showWeekend: Boolean,
    showTimeInCards: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth()) {
        Spacer(modifier = Modifier.width(periodColumnWidth(showTimeInCards)))
        val lastDay = if (showWeekend) 7 else 5
        for (day in 1..lastDay) {
            val epochDay = term.epochDayOf(week, day)
            val date = LocalDate.ofEpochDay(epochDay)
            val isToday = todayDayOfWeek == day
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = dayLabel(day),
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

private fun dayLabel(day: Int): String = when (day) {
    1 -> "一"
    2 -> "二"
    3 -> "三"
    4 -> "四"
    5 -> "五"
    6 -> "六"
    else -> "日"
}
