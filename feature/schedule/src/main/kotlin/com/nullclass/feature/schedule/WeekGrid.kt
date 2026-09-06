package com.nullclass.feature.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.nullclass.core.model.PeriodTime
import com.nullclass.core.model.PlacedBlock
import com.nullclass.core.model.Session
import com.nullclass.core.ui.theme.courseColor

/** 单节行高。课块高度 = 行高 × 跨节数。 */
internal val PeriodCellHeight = 56.dp

/** 左侧节次列宽度（表头 spacer 与此对齐）。 */
internal val PeriodColumnWidth = 44.dp

/**
 * 周视图主体：左侧节次列 + 7 天列。
 * 外层负责纵向滚动与按周翻页。
 */
@Composable
internal fun WeekGrid(
    periodTimes: List<PeriodTime>,
    layout: Map<Int, List<PlacedBlock>>,
    todayDayOfWeek: Int?,
    onBlockClick: (PlacedBlock) -> Unit,
    onCellClick: (dayOfWeek: Int, period: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val totalPeriods = periodTimes.size.coerceAtLeast(1)
    val todayHighlight = MaterialTheme.colorScheme.primary.copy(alpha = 0.06f)

    Row(modifier = modifier.fillMaxSize()) {
        PeriodColumn(periodTimes)
        for (day in 1..7) {
            DayColumn(
                day = day,
                isToday = todayDayOfWeek == day,
                blocks = layout[day].orEmpty(),
                totalPeriods = totalPeriods,
                todayHighlight = todayHighlight,
                onBlockClick = onBlockClick,
                onCellClick = onCellClick,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** 左侧节次列：节次号 + 起止时间，会话切换处画分隔线。 */
@Composable
private fun PeriodColumn(periodTimes: List<PeriodTime>) {
    Column(modifier = Modifier.width(PeriodColumnWidth)) {
        periodTimes.forEachIndexed { index, time ->
            val sessionChanged = index == 0 || periodTimes[index - 1].session != time.session
            if (sessionChanged && index != 0) {
                SessionDivider()
            }
            Column(
                modifier = Modifier
                    .height(PeriodCellHeight)
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = time.periodIndex.toString(),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = formatMinute(time.startMinuteOfDay),
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = formatMinute(time.endMinuteOfDay),
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SessionDivider() {
    HorizontalDivider(
        modifier = Modifier
            .width(28.dp)
            .padding(vertical = 3.dp),
        thickness = 2.dp,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

@Composable
private fun DayColumn(
    day: Int,
    isToday: Boolean,
    blocks: List<PlacedBlock>,
    totalPeriods: Int,
    todayHighlight: androidx.compose.ui.graphics.Color,
    onBlockClick: (PlacedBlock) -> Unit,
    onCellClick: (dayOfWeek: Int, period: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val cellHeightPx = with(density) { PeriodCellHeight.toPx() }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .background(if (isToday) todayHighlight else androidx.compose.ui.graphics.Color.Transparent)
            .pointerInput(day, totalPeriods, cellHeightPx) {
                detectTapGestures { offset ->
                    // 点空白格：换算出节次后预填进编辑页（课块自身的点击在子组件消费，不会到这里）
                    val period = (offset.y / cellHeightPx).toInt() + 1
                    if (period in 1..totalPeriods) {
                        onCellClick(day, period)
                    }
                }
            },
    ) {
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
                    .clickable { onBlockClick(placed) },
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = 2.dp, vertical = 3.dp),
                ) {
                    Text(
                        text = placed.course.name,
                        fontSize = 10.sp,
                        lineHeight = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = color.content,
                        textAlign = TextAlign.Center,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!placed.block.location.isNullOrBlank()) {
                        Text(
                            text = placed.block.location!!,
                            fontSize = 9.sp,
                            lineHeight = 11.sp,
                            color = color.content.copy(alpha = 0.8f),
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

internal fun formatMinute(minuteOfDay: Int): String {
    val hour = minuteOfDay / 60
    val minute = minuteOfDay % 60
    return "$hour:${minute.toString().padStart(2, '0')}"
}
