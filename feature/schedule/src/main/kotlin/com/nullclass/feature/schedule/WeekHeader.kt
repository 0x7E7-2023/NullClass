package com.nullclass.feature.schedule

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nullclass.core.model.DayOverrides
import com.nullclass.core.model.Term
import com.nullclass.core.ui.i18n.dayOfWeekLabel
import com.nullclass.core.ui.i18n.dayOfWeekShortLabel
import java.time.LocalDate

/**
 * 表头：星期 + 日期。与 WeekGrid 的列宽严格对齐（spacer + N × weight(1f)）。今日列带高亮条上段，下段在 WeekGrid。
 *
 * 点一列打开该日的调课（串课）面板；被串过的列在日期下多一行「上 周五」，
 * 与下方格子里画的课对得上。
 *
 * 个性化设置可以隐藏日期、固定表头高度、改文字颜色（见 [GridStyle]）。固定高度时内容垂直居中，
 * 放不下的行（例如调课日多出的那一行）被裁掉 —— 这是用户自己把表头调矮的直接结果，预览里看得见。
 *
 * @param dayOverrides 串课表 date → source date，见 [DayOverrides]
 */
@Composable
internal fun WeekHeader(
    term: Term,
    week: Int,
    weekDays: List<Int>,
    todayDayOfWeek: Int?,
    style: GridStyle,
    modifier: Modifier = Modifier,
    dayOverrides: Map<Long, Long> = emptyMap(),
    onDayClick: ((epochDay: Long) -> Unit)? = null,
) {
    val fixedHeight = style.headerHeight
    val rowModifier = if (fixedHeight != null) {
        // 固定高度下内容可能比格子高：裁掉，别让它画到下面的网格上
        modifier.fillMaxWidth().height(fixedHeight).clipToBounds()
    } else {
        modifier.fillMaxWidth()
    }
    val normalColor = style.pageTextColor ?: MaterialTheme.colorScheme.onSurfaceVariant
    Row(modifier = rowModifier) {
        Spacer(modifier = Modifier.width(style.sidebarWidth))
        for (day in weekDays) {
            val epochDay = term.epochDayOf(week, day)
            val date = LocalDate.ofEpochDay(epochDay)
            val isToday = todayDayOfWeek == day
            val sourceEpochDay = dayOverrides[epochDay]
            // 今天那列始终用主题强调色加粗：自定义文字颜色也不能把「今天」抹平
            val textColor = if (isToday) MaterialTheme.colorScheme.primary else normalColor
            Column(
                modifier = Modifier
                    .weight(1f)
                    .let { if (fixedHeight != null) it.fillMaxHeight() else it }
                    .let { base -> onDayClick?.let { base.clickable { it(epochDay) } } ?: base }
                    .padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = if (fixedHeight != null) Arrangement.Center else Arrangement.Top,
            ) {
                Text(
                    text = dayOfWeekShortLabel(day),
                    fontSize = 11.sp,
                    color = textColor,
                    fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                )
                if (style.showHeaderDates) {
                    Text(
                        text = "${date.monthValue}/${date.dayOfMonth}",
                        fontSize = 10.sp,
                        color = textColor,
                        fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                    )
                }
                if (sourceEpochDay != null) {
                    val source = LocalDate.ofEpochDay(sourceEpochDay)
                    Text(
                        // 前缀是「调」而不是「上」：「上周五」在中文里会被读成「上一周的周五」，
                        // 而这里说的是「上（动词）周五的课」。间隔号把动词和宾语隔开，两义不再撞车。
                        // 来源日就在同一周内时写星期；跨周才补月/日（同一列宽里放不下两者）。
                        text = if (term.weekOf(sourceEpochDay) == week) {
                            stringResource(
                                R.string.schedule_header_swap_weekday,
                                dayOfWeekLabel(source.dayOfWeek.value),
                            )
                        } else {
                            stringResource(
                                R.string.schedule_header_swap_date,
                                source.monthValue,
                                source.dayOfMonth,
                            )
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
