package com.nullclass.feature.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.nullclass.core.model.PeriodTime
import com.nullclass.core.model.PlacedBlock
import com.nullclass.core.model.ScheduleFormat
import com.nullclass.core.model.Session
import com.nullclass.core.ui.theme.courseColor

/** 单节行高。课块高度 = 行高 × 跨节数。 */
internal val PeriodCellHeight = 56.dp

/** 左侧节次列宽度（表头 spacer 与此对齐）。起止时间标在课块上时只留节次号，收窄。 */
internal fun periodColumnWidth(showTimeInCards: Boolean): Dp = if (showTimeInCards) 28.dp else 44.dp

/**
 * 周视图主体：左侧节次列 + 5/7 天列（周末可隐藏）。
 * 外层负责纵向滚动与按周翻页。
 */
@Composable
internal fun WeekGrid(
    periodTimes: List<PeriodTime>,
    layout: Map<Int, List<PlacedBlock>>,
    todayDayOfWeek: Int?,
    showWeekend: Boolean,
    showTimeInCards: Boolean,
    nowMinuteOfDay: Int?,
    onBlockClick: (PlacedBlock) -> Unit,
    modifier: Modifier = Modifier,
) {
    val totalPeriods = periodTimes.size.coerceAtLeast(1)
    val lastDay = if (showWeekend) 7 else 5
    val hairline = with(LocalDensity.current) { 1.toDp() }
    // 当前时间线：仅本周页；今天逢周末仅在显示周末时画；时刻须在节次表跨度内
    val nowLineY = todayDayOfWeek?.let { today ->
        if (today <= 5 || showWeekend) {
            nowMinuteOfDay?.let { nowLineYDp(periodTimes, it) }
        } else {
            null
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 纵向滚动链路 maxHeight = 无穷，fillMaxHeight 会失效、列高塌成课块堆高度；
                // 显式钉为「节数 × 行高」，今日高亮条与空白格点击区才能铺满整张网格
                .height(PeriodCellHeight * totalPeriods),
        ) {
            PeriodColumn(periodTimes, showTimeInCards)
            for (day in 1..lastDay) {
                DayColumn(
                    isToday = todayDayOfWeek == day,
                    blocks = layout[day].orEmpty(),
                    periodTimes = periodTimes,
                    showTimeInCards = showTimeInCards,
                    onBlockClick = onBlockClick,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        // 会话分隔线（上午/下午/晚上）：整行覆盖层，y 与课块网格（PeriodCellHeight × index）严格对齐
        periodTimes.forEachIndexed { index, time ->
            if (index > 0 && periodTimes[index - 1].session != time.session) {
                HorizontalDivider(
                    modifier = Modifier
                        .offset(y = PeriodCellHeight * index)
                        .fillMaxWidth(),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }
        }
        // 当前时间线：横贯网格，压在分隔线之上
        nowLineY?.let { y ->
            HorizontalDivider(
                modifier = Modifier
                    .offset(y = y - hairline / 2)
                    .fillMaxWidth(),
                thickness = hairline,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** 左侧节次列：起-号-止 紧贴堆叠（时间标在课块上时仅节次号），每格严格 PeriodCellHeight 高。 */
@Composable
private fun PeriodColumn(periodTimes: List<PeriodTime>, showTimeInCards: Boolean) {
    Column(modifier = Modifier.width(periodColumnWidth(showTimeInCards))) {
        periodTimes.forEach { time ->
            Column(
                modifier = Modifier
                    .height(PeriodCellHeight)
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                if (!showTimeInCards) {
                    Text(
                        text = ScheduleFormat.minuteLabel(time.startMinuteOfDay),
                        fontSize = 9.sp,
                        lineHeight = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                    )
                }
                Text(
                    text = time.periodIndex.toString(),
                    fontSize = 12.sp,
                    lineHeight = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (!showTimeInCards) {
                    Text(
                        text = ScheduleFormat.minuteLabel(time.endMinuteOfDay),
                        fontSize = 9.sp,
                        lineHeight = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                    )
                }
            }
        }
    }
}

@Composable
private fun DayColumn(
    isToday: Boolean,
    blocks: List<PlacedBlock>,
    periodTimes: List<PeriodTime>,
    showTimeInCards: Boolean,
    onBlockClick: (PlacedBlock) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val hairline = with(density) { 1.toDp() } // 1 物理像素，屏上最细的描边

    Box(modifier = modifier.fillMaxHeight()) {
        blocks.forEach { placed ->
            val color = courseColor(placed.course.colorIndex)
            Box(
                modifier = Modifier
                    .offset(y = PeriodCellHeight * (placed.block.startPeriod - 1))
                    .fillMaxWidth()
                    .height(PeriodCellHeight * placed.block.periodCount)
                    .padding(1.5.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(color.container)
                    .border(hairline, color.border, RoundedCornerShape(8.dp))
                    .clickable { onBlockClick(placed) },
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = 2.dp, vertical = 3.dp),
                ) {
                    Text(
                        text = placed.course.name,
                        fontSize = 11.sp,
                        lineHeight = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!placed.block.location.isNullOrBlank()) {
                        Text(
                            text = placed.block.location!!,
                            fontSize = 9.sp,
                            lineHeight = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (showTimeInCards) {
                    CornerTime(
                        label = periodTimes.getOrNull(placed.block.startPeriod - 1)
                            ?.let { ScheduleFormat.minuteLabel(it.startMinuteOfDay) },
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(start = 3.dp, top = 1.dp),
                    )
                    CornerTime(
                        label = periodTimes.getOrNull(placed.block.startPeriod + placed.block.periodCount - 2)
                            ?.let { ScheduleFormat.minuteLabel(it.endMinuteOfDay) },
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 3.dp, bottom = 1.dp),
                    )
                }
            }
        }
    }
}

/** 当前时刻在节次网格中的纵向偏移；空表或时刻不在首节开始～末节结束之间时返回 null。 */
private fun nowLineYDp(periodTimes: List<PeriodTime>, nowMinute: Int): Dp? {
    if (periodTimes.isEmpty()) return null
    if (nowMinute < periodTimes.first().startMinuteOfDay || nowMinute > periodTimes.last().endMinuteOfDay) {
        return null
    }
    for ((index, time) in periodTimes.withIndex()) {
        if (nowMinute <= time.endMinuteOfDay) {
            val span = (time.endMinuteOfDay - time.startMinuteOfDay).coerceAtLeast(1)
            val fraction = ((nowMinute - time.startMinuteOfDay).toFloat() / span).coerceIn(0f, 1f)
            return PeriodCellHeight * (index + fraction)
        }
    }
    return null
}

/** 课块角上的起止时间标注；label 为 null（节次超出节次表）时不显示。align 由调用点在 BoxScope 内应用。 */
@Composable
private fun CornerTime(
    label: String?,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    if (label == null) return
    Text(
        text = label,
        fontSize = 8.sp,
        lineHeight = 9.sp,
        color = color.copy(alpha = 0.7f),
        maxLines = 1,
        modifier = modifier,
    )
}
