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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.nullclass.core.model.PeriodTime
import com.nullclass.core.model.PlacedBlock
import com.nullclass.core.model.ScheduleFormat
import com.nullclass.core.model.Session
import com.nullclass.core.model.Term
import com.nullclass.core.ui.theme.courseColor
import com.nullclass.core.ui.theme.otherWeekBlockColor

/** 单节行高。课块高度 = 行高 × 跨节数。 */
internal val PeriodCellHeight = 56.dp

/** 左侧节次列宽度（表头 spacer 与此对齐）。起止时间标在课块上时只留节次号，收窄。 */
internal fun periodColumnWidth(showTimeInCards: Boolean): Dp = if (showTimeInCards) 28.dp else 44.dp

/**
 * 周视图要显示的列（ISO 星期几，按学期的每周起始日排序）。
 *
 * 关掉「显示周末」= 去掉周六、周日**这两列**，而不是砍掉末尾两列：一周从周日算起时，
 * 末尾两列是周五、周六，砍尾巴会把周五也一起砍掉。剩下的列仍按学期的周序排
 * （周三开学的学期去掉周末后是 三四五一二）。
 */
internal fun Term.visibleWeekDays(showWeekend: Boolean): List<Int> =
    if (showWeekend) daysInWeekOrder else daysInWeekOrder.filter { it <= 5 }

/**
 * 周视图主体：左侧节次列 + 5/7 天列（周末可隐藏、列序随学期起始日）。
 * 外层负责纵向滚动与按周翻页。
 *
 * @param weekDays 要画的列，见 [visibleWeekDays]；与 [WeekHeader] 必须传同一份
 * @param otherWeekLayout 该周不上、别的周要上的课块（灰块，见 WeekLayout.otherWeekLayout）；
 *   只画在当周空着的时段里，关掉显示开关时传空表。
 */
@Composable
internal fun WeekGrid(
    periodTimes: List<PeriodTime>,
    layout: Map<Int, List<PlacedBlock>>,
    weekDays: List<Int>,
    todayDayOfWeek: Int?,
    showTimeInCards: Boolean,
    showGridLines: Boolean,
    nowMinuteOfDay: Int?,
    onBlockClick: (PlacedBlock) -> Unit,
    otherWeekLayout: Map<Int, List<PlacedBlock>> = emptyMap(),
    modifier: Modifier = Modifier,
) {
    val totalPeriods = periodTimes.size.coerceAtLeast(1)
    val hairline = with(LocalDensity.current) { 1.toDp() }
    // 略淡于上午/下午/晚的分隔线（全不透明 + 1dp），这里是七成透明度 + hairline
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
    val sessionColor = MaterialTheme.colorScheme.outlineVariant
    // 每列上「被某个课块从中间穿过」的行边界，逐列记下来，画线时整段跳过。
    // 课块四周各留了 1.5dp 的空，线照画不误的话会从课块两侧各露出一小截，
    // 看着像课块被横着划了一刀 —— 比整条线压过去还奇怪。灰块同样是一整块，同等对待。
    val coveredRows = remember(layout, otherWeekLayout, weekDays, totalPeriods) {
        weekDays.map { day ->
            val covered = BooleanArray(totalPeriods + 1)
            for (placed in layout[day].orEmpty() + otherWeekLayout[day].orEmpty()) {
                val start = placed.block.startPeriod
                // 第 b 节与第 b+1 节之间那条线记作边界 b；只有块**内部**的边界要盖掉
                for (boundary in start until start + placed.block.periodCount - 1) {
                    if (boundary in 1 until totalPeriods) covered[boundary] = true
                }
            }
            covered
        }
    }
    // 当前时间线：仅本周页；今天那一列没显示（关掉了周末又逢周末）就不画；时刻须在节次表跨度内
    val nowLineY = todayDayOfWeek?.let { today ->
        if (today in weekDays) {
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
                .height(PeriodCellHeight * totalPeriods)
                // 网格线与会话分隔线都画在课块下面，且逐列绘制、遇到课块就断开：
                // 格线是给空时段用的参照，不该压在课上，也不该把一节跨多节的课切成几段。
                .drawBehind {
                    if (weekDays.isEmpty()) return@drawBehind
                    val rowHeightPx = PeriodCellHeight.toPx()
                    val periodColumnWidthPx = periodColumnWidth(showTimeInCards).toPx()
                    val dayWidthPx =
                        ((size.width - periodColumnWidthPx) / weekDays.size).coerceAtLeast(0f)
                    val gridStrokePx = hairline.toPx()
                    val sessionStrokePx = 1.dp.toPx()

                    // 竖线整列贯通：它落在相邻两列课块之间 1.5dp 的空档里，本来就碰不到课块
                    if (showGridLines) {
                        for (column in 0..weekDays.size) {
                            val x = periodColumnWidthPx + dayWidthPx * column
                            drawLine(
                                color = gridColor,
                                start = Offset(x, 0f),
                                end = Offset(x, rowHeightPx * totalPeriods),
                                strokeWidth = gridStrokePx,
                            )
                        }
                    }

                    for (row in 0..totalPeriods) {
                        // 上午/下午/晚上换段那条线更重，且关掉网格线后仍要画
                        val isSession = row in 1 until totalPeriods &&
                            periodTimes.getOrNull(row)?.session != periodTimes.getOrNull(row - 1)?.session
                        if (!isSession && !showGridLines) continue
                        val y = rowHeightPx * row
                        val color = if (isSession) sessionColor else gridColor
                        val stroke = if (isSession) sessionStrokePx else gridStrokePx
                        // 会话分隔线连左侧节次列一起横贯（首尾两条边界不可能是会话分界）
                        if (isSession) {
                            drawLine(color, Offset(0f, y), Offset(periodColumnWidthPx, y), stroke)
                        }
                        for (column in weekDays.indices) {
                            if (coveredRows[column][row]) continue
                            val x = periodColumnWidthPx + dayWidthPx * column
                            drawLine(color, Offset(x, y), Offset(x + dayWidthPx, y), stroke)
                        }
                    }
                },
        ) {
            PeriodColumn(periodTimes, showTimeInCards)
            for (day in weekDays) {
                DayColumn(
                    isToday = todayDayOfWeek == day,
                    blocks = layout[day].orEmpty(),
                    otherWeekBlocks = otherWeekLayout[day].orEmpty(),
                    periodTimes = periodTimes,
                    showTimeInCards = showTimeInCards,
                    onBlockClick = onBlockClick,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        // 会话分隔线（上午/下午/晚上）在上面的 drawBehind 里与网格线一起画 —— 它原先是压在
        // 课块之上的整行覆盖层，跨越上午/下午分界的课（如 3-6 节）会被拦腰划一道。
        // 当前时间线：横贯网格，压在课块之上（这条就是要盖着课画的）
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
    otherWeekBlocks: List<PlacedBlock>,
    periodTimes: List<PeriodTime>,
    showTimeInCards: Boolean,
    onBlockClick: (PlacedBlock) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val hairline = with(density) { 1.toDp() } // 1 物理像素，屏上最细的描边

    Box(modifier = modifier.fillMaxHeight()) {
        // 灰块先画：它只出现在当周空着的时段里，与真课块不会重叠，先画只是万无一失
        otherWeekBlocks.forEach { placed ->
            OtherWeekBlock(placed = placed, onBlockClick = onBlockClick)
        }
        blocks.forEach { placed ->
            val color = courseColor(placed.course.colorIndex)
            Box(
                modifier = Modifier
                    .offset(y = PeriodCellHeight * (placed.block.startPeriod - 1))
                    .fillMaxWidth()
                    .height(PeriodCellHeight * placed.block.periodCount)
                    .padding(1.5.dp)
                    .clip(RoundedCornerShape(8.dp))
                    // 先铺一层不透明底再叠课程色：容器只有 15%~18% 不透明度，冲突课（同一
                    // 天同一时段两门课）是直接叠着画的，不垫底的话两门课的颜色会混在一起，
                    // 谁都看不清。底色取 Scaffold 的 background，叠加结果与不垫底时同色。
                    .background(MaterialTheme.colorScheme.background)
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

/**
 * 「非本周」灰块：这一周不上、别的周要上的课（单双周、上半学期的课……）。
 *
 * 一律主题灰、低透明度，**不跟课程色走** —— 它是给「这格为什么空着」作注解的背景信息，
 * 不该和真课块争视线；没有课程色可认，也就不会和任何一门课混淆。
 *
 * 光有一块灰，用户看不出它是什么意思，所以文字分三行按「这是什么 → 哪门课 → 哪几周来」
 * 标清楚。三者挤在一行放不下：一列宽不到 8sp 的二十来个字，「非本周 · 3-6周」会被省略号
 * 吃掉整个周次，只剩一个看不出所以然的「非本周 ·」。分开各占一行才都看得见 ——
 * 单节的灰块（56dp 高）也放得下这四行。
 */
@Composable
private fun OtherWeekBlock(placed: PlacedBlock, onBlockClick: (PlacedBlock) -> Unit) {
    val color = otherWeekBlockColor()
    val hairline = with(LocalDensity.current) { 1.toDp() }
    Box(
        modifier = Modifier
            .offset(y = PeriodCellHeight * (placed.block.startPeriod - 1))
            .fillMaxWidth()
            .height(PeriodCellHeight * placed.block.periodCount)
            .padding(1.5.dp)
            .clip(RoundedCornerShape(8.dp))
            // 同真课块垫一层不透明底：灰块更透（6%~8%），垫了底色才与真课块走同一套合成，
            // 叠在什么上面（今日高亮、以后的背景图）都是同一个灰
            .background(MaterialTheme.colorScheme.background)
            .background(color.container)
            .border(hairline, color.border, RoundedCornerShape(8.dp))
            .clickable { onBlockClick(placed) },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 2.dp, vertical = 3.dp),
        ) {
            OtherWeekText("非本周", color.content)
            OtherWeekText(
                text = placed.course.name,
                color = color.content,
                fontSize = 10.sp,
                lineHeight = 12.sp,
                maxLines = 2,
            )
            OtherWeekText(ScheduleFormat.weekSpanLabel(placed.block), color.content)
        }
    }
}

/** 灰块里的一行灰字。默认按小一号的标注排（8sp 单行），课名另给字号与行数。 */
@Composable
private fun OtherWeekText(
    text: String,
    color: Color,
    fontSize: TextUnit = 8.sp,
    lineHeight: TextUnit = 10.sp,
    maxLines: Int = 1,
) {
    Text(
        text = text,
        fontSize = fontSize,
        lineHeight = lineHeight,
        color = color,
        textAlign = TextAlign.Center,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth(),
    )
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
