package com.nullclass.widget

import android.content.Context
import android.text.format.DateFormat
import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import com.nullclass.core.model.TodaySnapshot
import com.nullclass.core.model.WIDGET_PAGE_BUTTON_DP
import com.nullclass.core.model.WidgetAgendaPage
import com.nullclass.core.model.WidgetFontSize
import com.nullclass.core.model.WidgetPageLayout
import com.nullclass.core.model.buildWidgetAgenda
import com.nullclass.core.model.paginateWidgetAgenda
import com.nullclass.core.model.widgetPageLayout
import com.nullclass.core.ui.i18n.ScheduleText
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/** 今日课程：自适应容量的课程卡片列表，标题栏右侧按钮上下翻页，不截断后续课程。 */
class TodayGlanceWidget : GlanceAppWidget() {
    // Exact 只给横竖两份按上报尺寸算的布局，上报有误差时桌面会挑到比实际大的那份（分页按钮位置乱跳）。
    // Responsive 下桌面总挑「放得下的最大一档」；最小档 = 最小尺寸，保证总有一档放得下。
    // 宽度两档对应标题栏窄/宽两种写法；RemoteViews 尺寸表上限 16。
    // 档间高度差由卡片拉伸吸收，不会在底部留一整行的空白。
    override val sizeMode = SizeMode.Responsive(
        listOf(180, NARROW_WIDTH_DP).flatMap { w -> HEIGHTS.map { h -> DpSize(w.dp, h.dp) } }.toSet(),
    )
    override val stateDefinition = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val entryPoint = widgetEntryPoint(context)
        val initialNow = LocalDateTime.now()
        val initialSnapshot = buildTodaySnapshot(
            entryPoint.termRepository(),
            entryPoint.courseRepository(),
            entryPoint.dayOverrideRepository(),
        )
        val prefs = entryPoint.userPreferences()
        val initialFont = prefs.widgetFontSize.first()
        provideContent {
            val fontSize by prefs.widgetFontSize.collectAsState(initialFont)
            val live = liveToday(entryPoint, initialSnapshot, initialNow)
            WidgetTheme { TodayWidgetContent(live.snapshot, live.now, fontSize) }
        }
    }

    private companion object {
        /**
         * 110 = minResizeHeight；180 = 边距与间距放宽的分界（见 widgetPageLayout）。
         * 档距小于一张卡片，每页行数才跟得上实际高度；两种宽度 × 8 档正好用满 16 个布局。
         */
        val HEIGHTS = listOf(110, 140, 180, 215, 255, 300, 350, 420)
    }
}

/** 窄于这个宽度（按字号放大后）时，标题栏放不下「日期 · 周次」再加翻页按钮。 */
private const val NARROW_WIDTH_DP = 280

@Composable
internal fun TodayWidgetContent(
    snapshot: TodaySnapshot,
    now: LocalDateTime,
    fontSize: WidgetFontSize = WidgetFontSize.STANDARD,
) {
    val context = LocalContext.current
    val size = LocalSize.current
    val fontScale = context.resources.configuration.fontScale
    val layout = widgetPageLayout(size.height.value.roundToInt(), fontSize, fontScale)
    val nowMinuteOfDay = now.hour * 60 + now.minute
    val today = now.toLocalDate()
    val upcoming = buildWidgetAgenda(snapshot, nowMinuteOfDay, Int.MAX_VALUE).rows
    // 日期或课程集合变了就回到第一页；分钟倒计时不会把用户正在看的页翻走。
    // 不含每页行数：各尺寸档布局共用页码，桌面换用哪一档都不能让点击失效。
    val pageKey = buildString {
        append(today).append('|').append(snapshot.termName)
        upcoming.forEach {
            append('|').append(it.placed.course.id).append(':').append(it.placed.block.id)
            append(':').append(it.startMinuteOfDay).append('-').append(it.endMinuteOfDay)
        }
    }
    val state = currentState<Preferences>()
    val requestedPage = if (state[AgendaPageKey] == pageKey) state[AgendaPage] ?: 0 else 0
    val page = paginateWidgetAgenda(snapshot, nowMinuteOfDay, layout.rowsPerPage, requestedPage)
    val narrow = size.width < NARROW_WIDTH_DP.dp * fontSize.scale * fontScale
    Column(
        GlanceModifier.fillMaxSize().appWidgetBackground()
            .roundedBackground(WidgetColors.background, WidgetCorner.Root)
            .padding(layout.paddingDp.dp),
    ) {
        TodayHeader(today, snapshot, page, pageKey, layout, fontSize, narrow)
        Spacer(GlanceModifier.height(layout.headerGapDp.dp))
        Column(GlanceModifier.fillMaxWidth().defaultWeight()) {
            when {
                snapshot.termName.isEmpty() -> WidgetEmptyState(
                    R.drawable.ic_widget_add,
                    context.getString(R.string.widget_empty_no_timetable),
                    context.getString(R.string.widget_empty_no_timetable_desc),
                    fontSize,
                )
                snapshot.blocks.isEmpty() -> WidgetEmptyState(
                    R.drawable.ic_widget_event_available,
                    context.getString(R.string.widget_empty_no_class),
                    context.getString(R.string.widget_empty_no_class_desc),
                    fontSize,
                )
                page.rows.isEmpty() -> WidgetEmptyState(
                    R.drawable.ic_widget_event_available,
                    context.getString(R.string.widget_empty_finished),
                    context.getString(R.string.widget_empty_finished_desc),
                    fontSize,
                )
                // 每页固定 rowsPerPage 个等高的格子，按权重等分列表区：卡片跟着小组件的实际高度拉伸，
                // 行数估算偏保守、或桌面选的尺寸档比实际矮时，余量摊进卡片而不是堆在底部。
                // 末页不满时空格子照样占位，卡片高度与前几页一致。
                // 每格包一层 Column：外层 Column 的直接子项才受 RemoteViews 的 10 个上限约束。
                else -> for (slot in 0 until layout.rowsPerPage) {
                    Column(GlanceModifier.fillMaxWidth().defaultWeight()) {
                        if (slot > 0) Spacer(GlanceModifier.height(layout.rowGapDp.dp))
                        val entry = page.rows.getOrNull(slot)
                        if (entry != null) {
                            CourseCard(
                                entry, snapshot, nowMinuteOfDay, fontSize, layout,
                                isNext = page.index == 0 && slot == 0,
                                wide = !narrow,
                                modifier = GlanceModifier.defaultWeight(),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 标题栏：左边「周五  9月26日 · 第5周」（点击进应用），右边有多页时放翻页按钮。 */
@Composable
private fun TodayHeader(
    today: LocalDate,
    snapshot: TodaySnapshot,
    page: WidgetAgendaPage,
    pageKey: String,
    layout: WidgetPageLayout,
    fontSize: WidgetFontSize,
    narrow: Boolean,
) {
    val context = LocalContext.current
    val paging = page.pageCount > 1
    val week = snapshot.weekNumber
    val locale = context.resources.configuration.locales[0]
    val date = today.format(DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "MMMd"), locale))
    val detail = when {
        week == null -> date
        paging && narrow -> context.getString(R.string.widget_today_week_short, week)
        else -> context.getString(R.string.widget_today_date_week, date, week)
    }
    Row(
        GlanceModifier.fillMaxWidth().height(layout.headerHeightDp.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            GlanceModifier.defaultWeight().clickable(actionRunCallback<OpenAppAction>()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                ScheduleText.dayOfWeek(context, today.dayOfWeek.value),
                style = TextStyle(color = WidgetColors.text, fontSize = fontSize.sp(16), fontWeight = FontWeight.Bold),
                maxLines = 1,
            )
            Spacer(GlanceModifier.width(6.dp))
            Text(
                detail,
                modifier = GlanceModifier.defaultWeight(),
                style = TextStyle(
                    color = WidgetColors.textVariant,
                    fontSize = fontSize.sp(11),
                    fontWeight = FontWeight.Medium,
                ),
                maxLines = 1,
            )
        }
        if (paging) PageControls(page, pageKey, fontSize)
    }
}

@Composable
private fun PageControls(page: WidgetAgendaPage, key: String, fontSize: WidgetFontSize) {
    val context = LocalContext.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        PageButton(
            icon = R.drawable.ic_widget_chevron_up,
            description = context.getString(R.string.widget_page_prev_desc),
            target = page.index - 1,
            key = key,
            page = page,
            enabled = page.hasPrevious,
        )
        Text(
            "${page.index + 1}/${page.pageCount}",
            style = TextStyle(
                color = WidgetColors.textVariant,
                fontSize = fontSize.sp(11),
                fontWeight = FontWeight.Medium,
            ),
            modifier = GlanceModifier.padding(horizontal = 6.dp).semantics {
                contentDescription = context.getString(
                    R.string.widget_page_indicator_desc,
                    page.index + 1,
                    page.pageCount,
                )
            },
            maxLines = 1,
        )
        PageButton(
            icon = R.drawable.ic_widget_chevron_down,
            description = context.getString(R.string.widget_page_next_desc),
            target = page.index + 1,
            key = key,
            page = page,
            enabled = page.hasNext,
        )
    }
}

@Composable
private fun PageButton(
    @DrawableRes icon: Int,
    description: String,
    target: Int,
    key: String,
    page: WidgetAgendaPage,
    enabled: Boolean,
) {
    val label = if (enabled) {
        description
    } else {
        LocalContext.current.getString(R.string.widget_action_unavailable, description)
    }
    // 到头的那个按钮不画底色，只留一个淡色箭头
    val base = GlanceModifier.size(WIDGET_PAGE_BUTTON_DP.dp)
    val modifier = (if (enabled) base.roundedBackground(WidgetColors.button, WidgetCorner.Card) else base)
        .semantics { contentDescription = label }
        // 不移除 clickable：保持 RemoteViews 结构稳定，并显式覆盖复用视图上的旧动作。
        // 边界按钮也绑定回调，但 enabled=false 时不写状态、不触发重绘。
        .clickable(actionRunCallback<WidgetPageAction>(
            actionParametersOf(
                TargetPage to target,
                TargetPageKey to key,
                SourcePage to page.index,
                PageCount to page.pageCount,
                PageActionEnabled to enabled,
            ),
        ))
    Box(modifier, contentAlignment = Alignment.Center) {
        Image(
            ImageProvider(icon),
            contentDescription = null,
            modifier = GlanceModifier.size(20.dp),
            colorFilter = ColorFilter.tint(if (enabled) WidgetColors.onButton else WidgetColors.disabled),
        )
    }
}

/**
 * 一门课一张卡片：课程色条 | 时间 | 课名与地点。上课中 / 下一节的卡片换强调底色，
 * 并标出状态（剩几分钟 / 下一节）：窄尺寸写在地点前面，宽尺寸和单行卡片放在行尾。
 */
@Composable
private fun CourseCard(
    entry: TodaySnapshot.TodayEntry,
    snapshot: TodaySnapshot,
    nowMinuteOfDay: Int,
    fontSize: WidgetFontSize,
    layout: WidgetPageLayout,
    isNext: Boolean,
    wide: Boolean,
    modifier: GlanceModifier,
) {
    val context = LocalContext.current
    val inProgress = snapshot.inProgress(entry, nowMinuteOfDay)
    val highlighted = inProgress || isNext
    val block = entry.placed.block
    val location = block.location?.takeIf { it.isNotBlank() }
        ?: context.getString(R.string.widget_row_period_range, block.startPeriod, block.endPeriod)
    val status = when {
        inProgress -> context.getString(
            R.string.widget_row_status_in_class,
            snapshot.remainingMinutes(entry, nowMinuteOfDay),
        )
        isNext -> context.getString(R.string.widget_row_status_next)
        else -> null
    }
    val strong = if (highlighted) WidgetColors.onHighlight else WidgetColors.text
    val nameStyle = TextStyle(color = strong, fontSize = fontSize.sp(13), fontWeight = FontWeight.Bold)
    val statusStyle = TextStyle(color = WidgetColors.primary, fontSize = fontSize.sp(10), fontWeight = FontWeight.Bold)
    Row(
        modifier.fillMaxWidth()
            .roundedBackground(if (highlighted) WidgetColors.highlight else WidgetColors.card, WidgetCorner.Card)
            .padding(start = 8.dp, end = 10.dp, top = layout.rowPaddingDp.dp, bottom = layout.rowPaddingDp.dp)
            .clickable(actionRunCallback<OpenAppAction>()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            GlanceModifier.width(4.dp).fillMaxHeight()
                .background(courseAccent(entry.placed.course.colorIndex)).cornerRadius(2.dp),
        ) {}
        Spacer(GlanceModifier.width(8.dp))
        Column(GlanceModifier.width(fontSize.dp(40))) {
            Text(
                entry.startTime,
                style = TextStyle(color = strong, fontSize = fontSize.sp(13), fontWeight = FontWeight.Medium),
                maxLines = 1,
            )
            if (layout.showDetails) {
                Text(
                    entry.endTime,
                    style = TextStyle(color = WidgetColors.textVariant, fontSize = fontSize.sp(10)),
                    maxLines = 1,
                )
            }
        }
        Spacer(GlanceModifier.width(6.dp))
        if (layout.showDetails) {
            Column(GlanceModifier.defaultWeight()) {
                Text(entry.placed.course.name, style = nameStyle, maxLines = 1)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (status != null && !wide) {
                        Text(status, style = statusStyle, maxLines = 1)
                        Spacer(GlanceModifier.width(6.dp))
                    }
                    Text(
                        location,
                        modifier = GlanceModifier.defaultWeight(),
                        style = TextStyle(color = WidgetColors.textVariant, fontSize = fontSize.sp(10)),
                        maxLines = 1,
                    )
                }
            }
            // 宽尺寸右边空着，状态挪到行尾，地点那一行不再挤
            if (status != null && wide) {
                Spacer(GlanceModifier.width(8.dp))
                Text(status, style = statusStyle.copy(fontSize = fontSize.sp(11)), maxLines = 1)
            }
        } else {
            Text(entry.placed.course.name, modifier = GlanceModifier.defaultWeight(), style = nameStyle, maxLines = 1)
            if (status != null) {
                Spacer(GlanceModifier.width(6.dp))
                Text(status, style = statusStyle, maxLines = 1)
            }
        }
    }
}

/** 列表区的空状态：图标 + 标题 + 说明，太矮时只留标题。整块点击进应用。 */
@Composable
private fun WidgetEmptyState(@DrawableRes icon: Int, title: String, subtitle: String, fontSize: WidgetFontSize) {
    val roomy = LocalSize.current.height >= 150.dp
    Column(
        GlanceModifier.fillMaxSize().roundedBackground(WidgetColors.card, WidgetCorner.Card)
            .padding(12.dp).clickable(actionRunCallback<OpenAppAction>()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (roomy) {
            Image(
                ImageProvider(icon),
                contentDescription = null,
                modifier = GlanceModifier.size(fontSize.dp(26)),
                colorFilter = ColorFilter.tint(WidgetColors.primary),
            )
            Spacer(GlanceModifier.height(6.dp))
        }
        Text(
            title,
            style = TextStyle(
                color = WidgetColors.text,
                fontSize = fontSize.sp(13),
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            ),
            maxLines = if (roomy) 2 else 1,
        )
        if (roomy) {
            Spacer(GlanceModifier.height(2.dp))
            Text(
                subtitle,
                style = TextStyle(
                    color = WidgetColors.textVariant,
                    fontSize = fontSize.sp(11),
                    textAlign = TextAlign.Center,
                ),
                maxLines = 2,
            )
        }
    }
}
