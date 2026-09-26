package com.nullclass.feature.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
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
import com.nullclass.core.model.BlockBorderStyle
import com.nullclass.core.model.BlockTextAlign
import com.nullclass.core.model.PeriodTime
import com.nullclass.core.model.PlacedBlock
import com.nullclass.core.model.ScheduleFormat
import com.nullclass.core.model.Term
import com.nullclass.core.model.TimelineGeometry
import com.nullclass.core.ui.i18n.weekSpanLabel
import com.nullclass.core.ui.theme.courseColor
import com.nullclass.core.ui.theme.otherWeekBlockColor

/**
 * 周视图要显示的列（ISO 星期几，按学期的每周起始日排序）。
 *
 * 关掉「显示周末」= 去掉周六、周日**这两列**，而不是砍掉末尾两列：一周从周日算起时，
 * 末尾两列是周五、周六，砍尾巴会把周五也一起砍掉。剩下的列仍按学期的周序排
 * （周三开学的学期去掉周末后是 三四五一二）。
 */
internal fun Term.visibleWeekDays(showWeekend: Boolean): List<Int> =
    if (showWeekend) daysInWeekOrder else daysInWeekOrder.filter { it <= 5 }

/** 课块四周的内边距（在外边距 [GridStyle.blockSpacing] 之内）。 */
private val BlockPaddingHorizontal = 2.dp
private val BlockPaddingVertical = 3.dp

/** 虚线边框的线宽与虚实段长：hairline 的虚线断断续续几乎看不见，所以比实线粗。 */
private val DashedBorderWidth = 1.dp
private val DashOn = 4.dp
private val DashOff = 3.dp

/**
 * 周视图主体：左侧侧边栏 + 5/7 天列（周末可隐藏、列序随学期起始日）。
 * 外层负责纵向滚动与按周翻页。
 *
 * 纵轴有两种（见 [GridStyle.timeline]）：节次模式一行一节；24 小时时间轴一行一小时、
 * 卡片按实际上课时间定位。两种都以「行」为单位定位（[RowSpan]），画线与避让共用一套代码。
 *
 * @param weekDays 要画的列，见 [visibleWeekDays]；与 [WeekHeader] 必须传同一份
 * @param otherWeekLayout 该周不上、别的周要上的课块（灰块，见 WeekLayout.otherWeekLayout）；
 *   只画在当周空着的时段里，关掉显示开关时传空表。
 * @param rowHeight 一行的高度，见 [GridStyle.rowHeight]：由调用方按可用高度算好传进来 ——
 *   平板竖屏净高一千多 dp，固定 56dp 会让整张网格只占 672dp、下方空出一大片；
 *   手机横屏则保持 56dp 下限，靠外层 verticalScroll 滚动。
 */
@Composable
internal fun WeekGrid(
    periodTimes: List<PeriodTime>,
    layout: Map<Int, List<PlacedBlock>>,
    weekDays: List<Int>,
    todayDayOfWeek: Int?,
    style: GridStyle,
    nowMinuteOfDay: Int?,
    onBlockClick: (PlacedBlock) -> Unit,
    otherWeekLayout: Map<Int, List<PlacedBlock>> = emptyMap(),
    rowHeight: Dp = PeriodCellHeight,
    modifier: Modifier = Modifier,
) {
    val rowCount = style.rowCount(periodTimes.size)
    val hairline = with(LocalDensity.current) { 1.toDp() }
    // 略淡于上午/下午/晚的分隔线（全不透明 + 1dp），这里是七成透明度 + hairline
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
    val sessionColor = MaterialTheme.colorScheme.outlineVariant
    // 每列的课块、灰块在纵轴上的位置（时间轴模式下没有时间可依的课块不画，直接剔掉）
    val placedColumns = remember(layout, otherWeekLayout, weekDays, periodTimes, style.timeline) {
        weekDays.map { day ->
            PlacedColumn(
                blocks = placeAll(layout[day].orEmpty(), periodTimes, style.timeline),
                otherWeekBlocks = placeAll(otherWeekLayout[day].orEmpty(), periodTimes, style.timeline),
            )
        }
    }
    // 每列上「被某个课块从中间穿过」的行边界，逐列记下来，画线时整段跳过。灰块同样是一整块，同等对待。
    val coveredRows = remember(placedColumns, rowCount) {
        placedColumns.map { column ->
            coveredBoundaries((column.blocks + column.otherWeekBlocks).map { it.span }, rowCount)
        }
    }
    // 当前时间线：仅本周页；今天那一列没显示（关掉了周末又逢周末）就不画。
    // 节次模式下时刻须在节次表跨度内；时间轴全天都画。
    val nowLineY = todayDayOfWeek?.let { today ->
        if (today in weekDays) {
            nowMinuteOfDay?.let {
                if (style.timeline) rowHeight * (it / 60f) else nowLineYDp(periodTimes, it, rowHeight)
            }
        } else {
            null
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 纵向滚动链路 maxHeight = 无穷，fillMaxHeight 会失效、列高塌成课块堆高度；
                // 显式钉为「行数 × 行高」，空白格点击区才能铺满整张网格
                .height(rowHeight * rowCount)
                // 网格线与会话分隔线都画在课块下面，且逐列绘制、遇到课块就断开：
                // 格线是给空时段用的参照，不该压在课上，也不该把一节跨多节的课切成几段。
                .drawBehind {
                    if (weekDays.isEmpty()) return@drawBehind
                    val rowHeightPx = rowHeight.toPx()
                    val sidebarWidthPx = style.sidebarWidth.toPx()
                    val dayWidthPx = ((size.width - sidebarWidthPx) / weekDays.size).coerceAtLeast(0f)
                    val gridStrokePx = hairline.toPx()
                    val sessionStrokePx = 1.dp.toPx()

                    // 竖线整列贯通：它落在相邻两列课块之间的空档里，本来就碰不到课块
                    if (style.showGridLines) {
                        for (column in 0..weekDays.size) {
                            val x = sidebarWidthPx + dayWidthPx * column
                            drawLine(
                                color = gridColor,
                                start = Offset(x, 0f),
                                end = Offset(x, rowHeightPx * rowCount),
                                strokeWidth = gridStrokePx,
                            )
                        }
                    }

                    for (row in 0..rowCount) {
                        // 上午/下午/晚上换段那条线更重，且关掉网格线后仍要画。
                        // 时间轴一行一小时，节次的分段落不到整点线上，不画。
                        val isSession = !style.timeline && row in 1 until rowCount &&
                            periodTimes.getOrNull(row)?.session != periodTimes.getOrNull(row - 1)?.session
                        if (!isSession && !style.showGridLines) continue
                        val y = rowHeightPx * row
                        val color = if (isSession) sessionColor else gridColor
                        val stroke = if (isSession) sessionStrokePx else gridStrokePx
                        // 会话分隔线连左侧侧边栏一起横贯（首尾两条边界不可能是会话分界）
                        if (isSession) {
                            drawLine(color, Offset(0f, y), Offset(sidebarWidthPx, y), stroke)
                        }
                        for (column in weekDays.indices) {
                            if (coveredRows[column][row]) continue
                            val x = sidebarWidthPx + dayWidthPx * column
                            drawLine(color, Offset(x, y), Offset(x + dayWidthPx, y), stroke)
                        }
                    }
                },
        ) {
            if (style.timeline) {
                HourColumn(style, rowHeight)
            } else {
                PeriodColumn(periodTimes, style, rowHeight)
            }
            for ((index, day) in weekDays.withIndex()) {
                DayColumn(
                    column = placedColumns[index],
                    periodTimes = periodTimes,
                    style = style,
                    onBlockClick = onBlockClick,
                    rowHeight = rowHeight,
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

/** 一张已定位的卡片。 */
private data class Placed(val placed: PlacedBlock, val span: RowSpan)

/** 一列（一天）里要画的卡片。 */
private data class PlacedColumn(val blocks: List<Placed>, val otherWeekBlocks: List<Placed>)

private fun placeAll(blocks: List<PlacedBlock>, periodTimes: List<PeriodTime>, timeline: Boolean): List<Placed> =
    blocks.mapNotNull { placed ->
        rowSpanOf(placed.block, periodTimes, timeline)?.let { Placed(placed, it) }
    }

/** 左侧节次列：起-号-止 紧贴堆叠（不写时间时仅节次号），每格严格 rowHeight 高。 */
@Composable
private fun PeriodColumn(periodTimes: List<PeriodTime>, style: GridStyle, rowHeight: Dp) {
    val numberColor = style.pageTextColor ?: MaterialTheme.colorScheme.onSurface
    val timeColor = (style.pageTextColor ?: MaterialTheme.colorScheme.onSurfaceVariant).copy(alpha = 0.65f)
    Column(modifier = Modifier.width(style.sidebarWidth)) {
        periodTimes.forEach { time ->
            Column(
                modifier = Modifier
                    .height(rowHeight)
                    .fillMaxWidth()
                    // 水平只留 2dp：侧边栏可以调到 20dp，两位数节次号要放得下；内容居中，默认宽度下看不出差别
                    .padding(horizontal = 2.dp, vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                if (style.showTimesInSidebar) {
                    SidebarText(ScheduleFormat.minuteLabel(time.startMinuteOfDay), timeColor, 9.sp, 10.sp)
                }
                Text(
                    text = time.periodIndex.toString(),
                    fontSize = 12.sp,
                    lineHeight = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = numberColor,
                    maxLines = 1,
                    softWrap = false,
                )
                if (style.showTimesInSidebar) {
                    SidebarText(ScheduleFormat.minuteLabel(time.endMinuteOfDay), timeColor, 9.sp, 10.sp)
                }
            }
        }
    }
}

/** 时间轴的侧边栏：每小时一格，整点刻度贴在格子顶端（正好在那条整点线下面）。 */
@Composable
private fun HourColumn(style: GridStyle, rowHeight: Dp) {
    val color = (style.pageTextColor ?: MaterialTheme.colorScheme.onSurfaceVariant).copy(alpha = 0.8f)
    Column(modifier = Modifier.width(style.sidebarWidth)) {
        for (hour in 0 until TimelineGeometry.HOURS_PER_DAY) {
            Box(
                modifier = Modifier
                    .height(rowHeight)
                    .fillMaxWidth()
                    .padding(horizontal = 2.dp, vertical = 2.dp),
                contentAlignment = Alignment.TopCenter,
            ) {
                SidebarText(ScheduleFormat.minuteLabel(hour * 60), color, 10.sp, 12.sp)
            }
        }
    }
}

/** 侧边栏里的一行小字。侧边栏可以被调窄，放不下就截掉，不换行。 */
@Composable
private fun SidebarText(text: String, color: Color, fontSize: TextUnit, lineHeight: TextUnit) {
    Text(
        text = text,
        fontSize = fontSize,
        lineHeight = lineHeight,
        color = color,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
    )
}

@Composable
private fun DayColumn(
    column: PlacedColumn,
    periodTimes: List<PeriodTime>,
    style: GridStyle,
    onBlockClick: (PlacedBlock) -> Unit,
    rowHeight: Dp,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxHeight()) {
        // 灰块先画：它只出现在当周空着的时段里，与真课块不会重叠，先画只是万无一失
        column.otherWeekBlocks.forEach { (placed, span) ->
            OtherWeekBlock(placed, span, style, rowHeight, onBlockClick)
        }
        column.blocks.forEach { (placed, span) ->
            CourseBlock(placed, span, periodTimes, style, rowHeight, onBlockClick)
        }
    }
}

@Composable
private fun CourseBlock(
    placed: PlacedBlock,
    span: RowSpan,
    periodTimes: List<PeriodTime>,
    style: GridStyle,
    rowHeight: Dp,
    onBlockClick: (PlacedBlock) -> Unit,
) {
    val color = courseColor(placed.course.colorIndex)
    val textColor = style.blockTextColor ?: MaterialTheme.colorScheme.onSurface
    val blockDensity = LocalDensity.current
    val hairline = with(blockDensity) { 1.toDp() } // 1 物理像素，屏上最细的描边
    val scale = style.blockTextScale
    // 角标时间的行高：顶格排时文字区上下各让出这么多，免得压在起止时间上
    val cornerLineDp = with(blockDensity) { (9.sp * scale).toDp() }
    val reserveCorners = style.showCornerTimes && style.blockTextAlign == BlockTextAlign.TOP_START
    val contentPadding = blockContentPadding(style, extraVertical = if (reserveCorners) cornerLineDp else 0.dp)
    Box(
        modifier = Modifier
            .offset(y = rowHeight * span.start)
            .fillMaxWidth()
            .height(rowHeight * span.length)
            // 先铺一层不透明底再叠课程色：容器只有 15%~18% 不透明度，冲突课（同一
            // 天同一时段两门课）是直接叠着画的，不垫底的话两门课的颜色会混在一起，
            // 谁都看不清。底色取 Scaffold 的 background，叠加结果与不垫底时同色。
            // 用户调低了不透明度时垫底层也一起变透明（否则背景图永远透不过卡片），
            // 此时冲突课的重叠处会互相透出 —— 这是个性化设置里明确接受的取舍。
            .cardFrame(
                style = style,
                underlay = MaterialTheme.colorScheme.background,
                container = color.container,
                border = color.border,
                hairline = hairline,
                onClick = { onBlockClick(placed) },
            ),
        contentAlignment = style.blockTextAlign.boxAlignment,
    ) {
        Column(
            horizontalAlignment = style.blockTextAlign.columnAlignment,
            modifier = Modifier.padding(contentPadding),
        ) {
            // 行数按可用高度推算，而不是写死 3 行 + 2 行。
            // 写死的问题：格子净高只有约 47dp，而课名 3 行(13sp)+地点 2 行(11sp)
            // 要 61dp——排得下 3 行所以 Ellipsis 根本不触发，超出部分被外面的
            // clip 直接裁成半个字；系统字体放大后更早发生。
            // sp.toDp() 会带上 fontScale，字体放大时行数自动减少而不是被裁。
            // 个性化设置里的文字缩放同样乘进行高，一起参与推算。
            val nameLineDp = with(blockDensity) { (13.sp * scale).toDp() }
            val locationLineDp = with(blockDensity) { (11.sp * scale).toDp() }
            val hasLocation = !placed.block.location.isNullOrBlank()
            // 课块净高：行高 × 跨行数 − 外边距 × 2 − 内边距 × 2
            val contentHeight = rowHeight * span.length - style.blockSpacing * 2 -
                contentPadding.calculateTopPadding() - contentPadding.calculateBottomPadding()
            // 地点最多 2 行，但先给它留 1 行，剩下的都归课名
            val reservedForLocation = if (hasLocation) locationLineDp else 0.dp
            val nameLines = if (nameLineDp > 0.dp) {
                ((contentHeight - reservedForLocation) / nameLineDp).toInt()
            } else {
                1
            }.coerceIn(1, 4)
            val leftover = contentHeight - nameLineDp * nameLines
            val locationLines = if (hasLocation && locationLineDp > 0.dp) {
                (leftover / locationLineDp).toInt().coerceIn(1, 2)
            } else {
                1
            }

            Text(
                text = placed.course.name,
                fontSize = 11.sp * scale,
                lineHeight = 13.sp * scale,
                fontWeight = FontWeight.SemiBold,
                color = textColor,
                textAlign = style.blockTextAlign.textAlign,
                maxLines = nameLines,
                overflow = TextOverflow.Ellipsis,
            )
            if (hasLocation) {
                Text(
                    text = placed.block.location!!,
                    fontSize = 9.sp * scale,
                    lineHeight = 11.sp * scale,
                    color = textColor.copy(alpha = 0.75f),
                    textAlign = style.blockTextAlign.textAlign,
                    maxLines = locationLines,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (style.showCornerTimes) {
            CornerTime(
                label = periodTimes.getOrNull(placed.block.startPeriod - 1)
                    ?.let { ScheduleFormat.minuteLabel(it.startMinuteOfDay) },
                color = textColor,
                scale = scale,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 3.dp, top = 1.dp),
            )
            CornerTime(
                label = periodTimes.getOrNull(placed.block.startPeriod + placed.block.periodCount - 2)
                    ?.let { ScheduleFormat.minuteLabel(it.endMinuteOfDay) },
                color = textColor,
                scale = scale,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 3.dp, bottom = 1.dp),
            )
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
 *
 * 形状、边框、间距、不透明度、文字缩放与对齐跟真课块一致；文字颜色设了自定义色时用它的七成，
 * 仍比真课块淡一档。
 */
@Composable
private fun OtherWeekBlock(
    placed: PlacedBlock,
    span: RowSpan,
    style: GridStyle,
    rowHeight: Dp,
    onBlockClick: (PlacedBlock) -> Unit,
) {
    val color = otherWeekBlockColor()
    val textColor = style.blockTextColor?.copy(alpha = 0.7f) ?: color.content
    val hairline = with(LocalDensity.current) { 1.toDp() }
    val scale = style.blockTextScale
    val align = style.blockTextAlign
    Box(
        modifier = Modifier
            .offset(y = rowHeight * span.start)
            .fillMaxWidth()
            .height(rowHeight * span.length)
            // 同真课块垫一层不透明底：灰块更透（6%~8%），垫了底色才与真课块走同一套合成，
            // 叠在什么上面（今日高亮、背景图）都是同一个灰
            .cardFrame(
                style = style,
                underlay = MaterialTheme.colorScheme.background,
                container = color.container,
                border = color.border,
                hairline = hairline,
                onClick = { onBlockClick(placed) },
            ),
        contentAlignment = align.boxAlignment,
    ) {
        Column(
            horizontalAlignment = align.columnAlignment,
            modifier = Modifier.padding(blockContentPadding(style, extraVertical = 0.dp)),
        ) {
            OtherWeekText(stringResource(R.string.schedule_other_week), textColor, align, scale)
            OtherWeekText(
                text = placed.course.name,
                color = textColor,
                align = align,
                scale = scale,
                fontSize = 10.sp,
                lineHeight = 12.sp,
                maxLines = 2,
            )
            OtherWeekText(weekSpanLabel(placed.block), textColor, align, scale)
        }
    }
}

/** 灰块里的一行灰字。默认按小一号的标注排（8sp 单行），课名另给字号与行数。 */
@Composable
private fun OtherWeekText(
    text: String,
    color: Color,
    align: BlockTextAlign,
    scale: Float,
    fontSize: TextUnit = 8.sp,
    lineHeight: TextUnit = 10.sp,
    maxLines: Int = 1,
) {
    Text(
        text = text,
        fontSize = fontSize * scale,
        lineHeight = lineHeight * scale,
        color = color,
        textAlign = align.textAlign,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * 课块的外框：外边距 → 圆角裁切 → 垫底 + 底色（乘不透明度）→ 边框 → 点击。
 * 默认样式（1.5dp、8dp 圆角、不透明、hairline 实线）与加个性化设置之前逐项一致。
 */
private fun Modifier.cardFrame(
    style: GridStyle,
    underlay: Color,
    container: Color,
    border: Color,
    hairline: Dp,
    onClick: () -> Unit,
): Modifier {
    val shape = RoundedCornerShape(style.blockCornerRadius)
    return this
        .padding(style.blockSpacing)
        .clip(shape)
        .background(underlay.copy(alpha = underlay.alpha * style.blockOpacity))
        .background(container.copy(alpha = container.alpha * style.blockOpacity))
        .borderOf(style.blockBorderStyle, hairline, border, shape, style.blockCornerRadius)
        .clickable(onClick = onClick)
}

private fun Modifier.borderOf(
    borderStyle: BlockBorderStyle,
    hairline: Dp,
    color: Color,
    shape: Shape,
    cornerRadius: Dp,
): Modifier = when (borderStyle) {
    BlockBorderStyle.NONE -> this
    BlockBorderStyle.SOLID -> border(hairline, color, shape)
    // Compose 没有虚线 border，只能自绘。描边是骑在路径上的（一半在内一半在外），
    // 而前面已经按圆角 clip 过：矩形向内收半个线宽、圆角也减半个线宽，整条虚线才都落在卡片里
    BlockBorderStyle.DASHED -> drawWithContent {
        drawContent()
        val stroke = DashedBorderWidth.toPx()
        val inset = stroke / 2
        drawRoundRect(
            color = color,
            topLeft = Offset(inset, inset),
            size = Size(size.width - stroke, size.height - stroke),
            cornerRadius = CornerRadius((cornerRadius.toPx() - inset).coerceAtLeast(0f)),
            style = Stroke(
                width = stroke,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(DashOn.toPx(), DashOff.toPx())),
            ),
        )
    }
}

/**
 * 课块文字区的内边距。顶格排时文字贴着左上角，圆角大了会把首字切掉一块，
 * 所以至少让出圆角半径的三成（约为圆弧在 45° 处离边的距离）。
 */
private fun blockContentPadding(style: GridStyle, extraVertical: Dp) =
    if (style.blockTextAlign == BlockTextAlign.TOP_START) {
        val cornerInset = style.blockCornerRadius * 0.3f
        PaddingValues(
            horizontal = maxOf(BlockPaddingHorizontal, cornerInset),
            vertical = maxOf(BlockPaddingVertical, cornerInset) + extraVertical,
        )
    } else {
        PaddingValues(
            horizontal = BlockPaddingHorizontal,
            vertical = BlockPaddingVertical + extraVertical,
        )
    }

private val BlockTextAlign.boxAlignment: Alignment
    get() = if (this == BlockTextAlign.TOP_START) Alignment.TopStart else Alignment.Center

private val BlockTextAlign.columnAlignment: Alignment.Horizontal
    get() = if (this == BlockTextAlign.TOP_START) Alignment.Start else Alignment.CenterHorizontally

private val BlockTextAlign.textAlign: TextAlign
    get() = if (this == BlockTextAlign.TOP_START) TextAlign.Start else TextAlign.Center

/** 节次模式下当前时刻在网格中的纵向偏移；空表或时刻不在首节开始～末节结束之间时返回 null。 */
private fun nowLineYDp(periodTimes: List<PeriodTime>, nowMinute: Int, rowHeight: Dp): Dp? {
    if (periodTimes.isEmpty()) return null
    if (nowMinute < periodTimes.first().startMinuteOfDay || nowMinute > periodTimes.last().endMinuteOfDay) {
        return null
    }
    for ((index, time) in periodTimes.withIndex()) {
        if (nowMinute <= time.endMinuteOfDay) {
            val span = (time.endMinuteOfDay - time.startMinuteOfDay).coerceAtLeast(1)
            val fraction = ((nowMinute - time.startMinuteOfDay).toFloat() / span).coerceIn(0f, 1f)
            return rowHeight * (index + fraction)
        }
    }
    return null
}

/** 课块角上的起止时间标注；label 为 null（节次超出节次表）时不显示。align 由调用点在 BoxScope 内应用。 */
@Composable
private fun CornerTime(
    label: String?,
    color: Color,
    scale: Float,
    modifier: Modifier = Modifier,
) {
    if (label == null) return
    Text(
        text = label,
        fontSize = 8.sp * scale,
        lineHeight = 9.sp * scale,
        color = color.copy(alpha = 0.7f),
        maxLines = 1,
        modifier = modifier,
    )
}
