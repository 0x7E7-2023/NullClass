package com.nullclass.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.nullclass.core.model.TodaySnapshot
import com.nullclass.core.model.WidgetAgendaPage
import com.nullclass.core.model.WidgetFontSize
import com.nullclass.core.model.WidgetPageLayout
import com.nullclass.core.model.buildWidgetAgenda
import com.nullclass.core.model.paginateWidgetAgenda
import com.nullclass.core.model.widgetPageLayout
import com.nullclass.core.ui.i18n.ScheduleText
import com.nullclass.core.ui.theme.courseColor
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/** 今日课程：自适应容量的纵向列表，按钮分页，不再截断后续课程。 */
class TodayGlanceWidget : GlanceAppWidget() {
    // Exact 只给横竖两份按上报尺寸算的布局，上报有误差时桌面会挑到比实际大的那份（分页按钮位置乱跳）。
    // Responsive 下桌面总挑「放得下的最大一档」；最小档 = 最小尺寸，保证总有一档放得下。
    // 宽度两档对应 compact 判定；RemoteViews 尺寸表上限 16。
    // ponytail: 档间高度差最多浪费一行空间，嫌空就在 HEIGHTS 里加密。
    override val sizeMode = SizeMode.Responsive(
        listOf(180, 280).flatMap { w -> HEIGHTS.map { h -> DpSize(w.dp, h.dp) } }.toSet(),
    )
    override val stateDefinition = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val entryPoint = widgetEntryPoint(context)
        val snapshot = buildTodaySnapshot(
            entryPoint.termRepository(),
            entryPoint.courseRepository(),
            entryPoint.dayOverrideRepository(),
        )
        val prefs = entryPoint.userPreferences()
        val initialFont = prefs.widgetFontSize.first()
        provideContent {
            val fontSize by prefs.widgetFontSize.collectAsState(initialFont)
            val pageState = currentState<Preferences>()
            // 翻页可唤醒仍存活的 Glance 会话，此时 provideGlance 不会重跑。
            val liveSnapshot by produceState(snapshot, pageState) {
                value = buildTodaySnapshot(
                    entryPoint.termRepository(),
                    entryPoint.courseRepository(),
                    entryPoint.dayOverrideRepository(),
                )
            }
            val now = LocalTime.now()
            TodayWidgetContent(
                liveSnapshot,
                now.hour * 60 + now.minute,
                fontSize,
                now.format(DateTimeFormatter.ofPattern("HH:mm")),
            )
        }
    }

    private companion object {
        /** 110 = minResizeHeight；180 = 分页按钮从标题栏移到底栏的分界。 */
        val HEIGHTS = listOf(110, 140, 180, 220, 270, 330, 410)
    }
}

@Composable
internal fun TodayWidgetContent(
    snapshot: TodaySnapshot,
    nowMinuteOfDay: Int,
    fontSize: WidgetFontSize = WidgetFontSize.STANDARD,
    updatedAtLabel: String? = null,
) {
    val layout = widgetPageLayout(
        LocalSize.current.height.value.roundToInt(),
        fontSize,
        LocalContext.current.resources.configuration.fontScale,
    )
    val upcoming = buildWidgetAgenda(snapshot, nowMinuteOfDay, Int.MAX_VALUE).rows
    // 日期或课程集合变了就回到第一页；分钟倒计时不会把用户正在看的页翻走。
    // 不含每页行数：各尺寸档布局共用页码，桌面换用哪一档都不能让点击失效。
    val pageKey = buildString {
        append(LocalDate.now()).append('|').append(snapshot.termName)
        upcoming.forEach {
            append('|').append(it.placed.course.id).append(':').append(it.placed.block.id)
            append(':').append(it.startMinuteOfDay).append('-').append(it.endMinuteOfDay)
        }
    }
    val context = LocalContext.current
    val state = currentState<Preferences>()
    val requestedPage = if (state[AgendaPageKey] == pageKey) state[AgendaPage] ?: 0 else 0
    val page = paginateWidgetAgenda(snapshot, nowMinuteOfDay, layout.rowsPerPage, requestedPage)
    Column(
        GlanceModifier.fillMaxSize().background(WidgetBackground).cornerRadius(20.dp)
            .padding(layout.paddingDp.dp),
    ) {
        Row(
            GlanceModifier.fillMaxWidth().height(layout.headerHeightDp.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!layout.inlinePager || page.pageCount == 1) Text(
                context.getString(
                    if (layout.inlinePager) {
                        R.string.widget_today_title_short
                    } else {
                        R.string.widget_today_title
                    },
                ),
                modifier = GlanceModifier.defaultWeight().clickable(actionRunCallback<OpenAppAction>()),
                style = TextStyle(color = WidgetOnBackground, fontSize = fontSize.sp(13), fontWeight = FontWeight.Bold),
                maxLines = 1,
            )
            if (layout.inlinePager && page.pageCount > 1) {
                PageControls(page, pageKey, fontSize, layout, updatedAtLabel, compact = true)
            } else {
                Text(
                    if (layout.inlinePager && updatedAtLabel != null) {
                        context.getString(R.string.widget_updated_at, updatedAtLabel)
                    } else {
                        snapshot.weekNumber?.let { week ->
                            context.getString(
                                R.string.widget_today_week,
                                week,
                                ScheduleText.dayOfWeek(context, LocalDate.now().dayOfWeek.value),
                            )
                        } ?: context.getString(R.string.widget_app_name)
                    },
                    style = TextStyle(color = WidgetAccent, fontSize = fontSize.sp(10)),
                    maxLines = 1,
                )
            }
        }
        Spacer(GlanceModifier.height(8.dp))
        Column(GlanceModifier.fillMaxWidth().defaultWeight()) {
            when {
                snapshot.termName.isEmpty() -> WidgetEmptyState(
                    context.getString(R.string.widget_empty_no_timetable),
                    context.getString(R.string.widget_empty_no_timetable_desc),
                    fontSize,
                )
                snapshot.blocks.isEmpty() -> WidgetEmptyState(
                    context.getString(R.string.widget_empty_no_class),
                    context.getString(R.string.widget_empty_no_class_desc),
                    fontSize,
                )
                page.rows.isEmpty() -> WidgetEmptyState(
                    context.getString(R.string.widget_empty_finished),
                    context.getString(R.string.widget_empty_finished_desc),
                    fontSize,
                )
                else -> page.rows.forEachIndexed { index, entry ->
                    Column {
                        TodayRow(entry, snapshot, nowMinuteOfDay, fontSize, layout, page.index == 0 && index == 0)
                    }
                }
            }
        }
        if (!layout.inlinePager) {
            Spacer(GlanceModifier.height(8.dp))
            Row(
                GlanceModifier.fillMaxWidth().height(layout.controlsHeightDp.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (page.pageCount > 1) {
                    PageControls(
                        page, pageKey, fontSize, layout, updatedAtLabel,
                        compact = LocalSize.current.width < 280.dp * fontSize.scale *
                            LocalContext.current.resources.configuration.fontScale,
                    )
                } else {
                    Text(
                        if (page.totalRows > 0) {
                            context.getString(R.string.widget_today_remaining_count, page.totalRows)
                        } else {
                            context.getString(R.string.widget_today_footer)
                        },
                        modifier = GlanceModifier.defaultWeight(),
                        style = TextStyle(color = WidgetOnBackgroundVariant, fontSize = fontSize.sp(10)),
                        maxLines = 1,
                    )
                    updatedAtLabel?.let {
                        Text(
                            context.getString(R.string.widget_updated_at, it),
                            style = TextStyle(color = WidgetOnBackgroundVariant, fontSize = fontSize.sp(9)),
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PageControls(
    page: WidgetAgendaPage,
    key: String,
    fontSize: WidgetFontSize,
    layout: WidgetPageLayout,
    updatedAtLabel: String?,
    compact: Boolean,
) {
    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val context = LocalContext.current
        PageButton(
            text = context.getString(
                if (compact) R.string.widget_page_prev_short else R.string.widget_page_prev,
            ),
            description = context.getString(R.string.widget_page_prev_desc),
            target = page.index - 1,
            key = key,
            page = page,
            enabled = page.hasPrevious,
            fontSize = fontSize,
            layout = layout,
        )
        Box(
            modifier = GlanceModifier.defaultWeight(),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "${page.index + 1}/${page.pageCount}",
                    style = TextStyle(color = WidgetOnBackgroundVariant, fontSize = fontSize.sp(10)),
                    modifier = GlanceModifier.semantics {
                        contentDescription = context.getString(
                            R.string.widget_page_indicator_desc,
                            page.index + 1,
                            page.pageCount,
                        )
                    },
                    maxLines = 1,
                )
                updatedAtLabel?.let {
                    Text(
                        context.getString(R.string.widget_updated_at, it),
                        style = TextStyle(color = WidgetOnBackgroundVariant, fontSize = fontSize.sp(9)),
                        maxLines = 1,
                    )
                }
            }
        }
        PageButton(
            text = context.getString(
                if (compact) R.string.widget_page_next_short else R.string.widget_page_next,
            ),
            description = context.getString(R.string.widget_page_next_desc),
            target = page.index + 1,
            key = key,
            page = page,
            enabled = page.hasNext,
            fontSize = fontSize,
            layout = layout,
        )
    }
}

@Composable
private fun PageButton(
    text: String,
    description: String,
    target: Int,
    key: String,
    page: WidgetAgendaPage,
    enabled: Boolean,
    fontSize: WidgetFontSize,
    layout: WidgetPageLayout,
) {
    val label = if (enabled) {
        description
    } else {
        LocalContext.current.getString(R.string.widget_action_unavailable, description)
    }
    val modifier = GlanceModifier.height(layout.controlsHeightDp.dp)
        .background(if (enabled) WidgetAccentSurface else WidgetSurface).cornerRadius(10.dp)
        .padding(horizontal = 10.dp)
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
        Text(text, style = TextStyle(
            color = if (enabled) WidgetAccent else WidgetDisabled,
            fontSize = fontSize.sp(11), fontWeight = FontWeight.Medium,
        ), maxLines = 1)
    }
}

@Composable
private fun TodayRow(
    entry: TodaySnapshot.TodayEntry,
    snapshot: TodaySnapshot,
    nowMinuteOfDay: Int,
    fontSize: WidgetFontSize,
    layout: WidgetPageLayout,
    isNext: Boolean,
) {
    val inProgress = snapshot.inProgress(entry, nowMinuteOfDay)
    val block = entry.placed.block
    val location = block.location?.takeIf { it.isNotBlank() }
    val context = LocalContext.current
    val periodRange = context.getString(
        R.string.widget_row_period_range,
        block.startPeriod,
        block.endPeriod,
    )
    val subtitle = when {
        inProgress -> context.getString(
            R.string.widget_row_in_class,
            snapshot.remainingMinutes(entry, nowMinuteOfDay),
        )
        isNext -> context.getString(R.string.widget_row_next, location ?: periodRange)
        else -> location ?: periodRange
    }
    Row(
        GlanceModifier.fillMaxWidth().height((layout.rowHeightDp - 4).dp)
            .background(if (isNext || inProgress) WidgetAccentSurface else WidgetSurface)
            .cornerRadius(12.dp).padding(horizontal = 8.dp, vertical = 4.dp)
            .clickable(actionRunCallback<OpenAppAction>()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(GlanceModifier.width(3.dp).height(if (layout.showDetails) 28.dp else 16.dp)
            .background(courseColor(entry.placed.course.colorIndex).content).cornerRadius(2.dp)) {}
        Spacer(GlanceModifier.width(8.dp))
        Column(GlanceModifier.width(fontSize.dp(44))) {
            Text(entry.startTime, style = TextStyle(color = WidgetAccent, fontSize = fontSize.sp(11), fontWeight = FontWeight.Bold), maxLines = 1)
            if (layout.showDetails) {
                Text(entry.endTime, style = TextStyle(color = WidgetOnBackgroundVariant, fontSize = fontSize.sp(10)), maxLines = 1)
            }
        }
        Spacer(GlanceModifier.width(6.dp))
        Column(GlanceModifier.defaultWeight()) {
            Text(entry.placed.course.name, style = TextStyle(
                color = WidgetOnBackground, fontSize = fontSize.sp(12), fontWeight = FontWeight.Bold,
            ), maxLines = 1)
            if (layout.showDetails) {
                Text(subtitle, style = TextStyle(color = WidgetOnBackgroundVariant, fontSize = fontSize.sp(10)), maxLines = 1)
            }
        }
    }
    Spacer(GlanceModifier.height(4.dp))
}

@Composable
internal fun WidgetEmptyState(title: String, subtitle: String, fontSize: WidgetFontSize) {
    Column(
        GlanceModifier.fillMaxSize().background(WidgetSurface).cornerRadius(12.dp)
            .padding(8.dp).clickable(actionRunCallback<OpenAppAction>()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = TextStyle(color = WidgetOnBackground, fontSize = fontSize.sp(12), fontWeight = FontWeight.Bold), maxLines = 1)
        if (LocalSize.current.height >= 150.dp) {
            Spacer(GlanceModifier.height(4.dp))
            Text(subtitle, style = TextStyle(color = WidgetOnBackgroundVariant, fontSize = fontSize.sp(10)), maxLines = 2)
        }
    }
}
