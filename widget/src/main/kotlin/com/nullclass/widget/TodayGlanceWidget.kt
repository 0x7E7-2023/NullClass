package com.nullclass.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
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
import com.nullclass.core.model.ScheduleFormat
import com.nullclass.core.model.TodaySnapshot
import com.nullclass.core.model.WidgetAgendaPage
import com.nullclass.core.model.WidgetFontSize
import com.nullclass.core.model.WidgetPageLayout
import com.nullclass.core.model.buildWidgetAgenda
import com.nullclass.core.model.paginateWidgetAgenda
import com.nullclass.core.model.widgetPageLayout
import com.nullclass.core.ui.theme.courseColor
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/** 今日课程：自适应容量的纵向列表，按钮分页，不再截断后续课程。 */
class TodayGlanceWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact
    override val stateDefinition = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val entryPoint = widgetEntryPoint(context)
        val snapshot = buildTodaySnapshot(entryPoint.termRepository(), entryPoint.courseRepository())
        val prefs = entryPoint.userPreferences()
        val initialFont = prefs.widgetFontSize.first()
        provideContent {
            val fontSize by prefs.widgetFontSize.collectAsState(initialFont)
            val pageState = currentState<Preferences>()
            // 翻页可唤醒仍存活的 Glance 会话，此时 provideGlance 不会重跑。
            val liveSnapshot by produceState(snapshot, pageState) {
                value = buildTodaySnapshot(entryPoint.termRepository(), entryPoint.courseRepository())
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
    // 日期、课程集合或容量变了就回到第一页；分钟倒计时不会把用户正在看的页翻走。
    val pageKey = buildString {
        append(LocalDate.now()).append('|').append(snapshot.termName).append('|').append(layout.rowsPerPage)
        upcoming.forEach {
            append('|').append(it.placed.course.id).append(':').append(it.placed.block.id)
            append(':').append(it.startMinuteOfDay).append('-').append(it.endMinuteOfDay)
        }
    }
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
                if (layout.inlinePager) "今日" else "今日课程",
                modifier = GlanceModifier.defaultWeight().clickable(actionRunCallback<OpenAppAction>()),
                style = TextStyle(color = WidgetOnBackground, fontSize = fontSize.sp(13), fontWeight = FontWeight.Bold),
                maxLines = 1,
            )
            if (layout.inlinePager && page.pageCount > 1) {
                PageControls(page, pageKey, fontSize, layout, updatedAtLabel, compact = true)
            } else {
                Text(
                    if (layout.inlinePager && updatedAtLabel != null) "更新于 $updatedAtLabel"
                    else snapshot.weekNumber?.let { "第${it}周 · ${ScheduleFormat.dayOfWeekLabel(LocalDate.now().dayOfWeek.value)}" }
                        ?: "空课",
                    style = TextStyle(color = WidgetAccent, fontSize = fontSize.sp(10)),
                    maxLines = 1,
                )
            }
        }
        Spacer(GlanceModifier.height(8.dp))
        Column(GlanceModifier.fillMaxWidth().defaultWeight()) {
            when {
                snapshot.termName.isEmpty() -> WidgetEmptyState("还没有课表", "点此创建学期，安排新的一天", fontSize)
                snapshot.blocks.isEmpty() -> WidgetEmptyState("今天没有课", "留一点时间，做喜欢的事", fontSize)
                page.rows.isEmpty() -> WidgetEmptyState("今天的课上完了", "辛苦了，好好休息", fontSize)
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
                        if (page.totalRows > 0) "待上 ${page.totalRows} 门" else "空课 · 今天",
                        modifier = GlanceModifier.defaultWeight(),
                        style = TextStyle(color = WidgetOnBackgroundVariant, fontSize = fontSize.sp(10)),
                        maxLines = 1,
                    )
                    updatedAtLabel?.let {
                        Text("更新于 $it", style = TextStyle(color = WidgetOnBackgroundVariant, fontSize = fontSize.sp(9)), maxLines = 1)
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
        PageButton(if (compact) "↑" else "↑ 上页", "上一页", page.index - 1, key, page, page.hasPrevious, fontSize, layout)
        Box(
            modifier = GlanceModifier.defaultWeight(),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "${page.index + 1}/${page.pageCount}",
                    style = TextStyle(color = WidgetOnBackgroundVariant, fontSize = fontSize.sp(10)),
                    modifier = GlanceModifier.semantics { contentDescription = "第${page.index + 1}页，共${page.pageCount}页" },
                    maxLines = 1,
                )
                updatedAtLabel?.let {
                    Text("更新于 $it", style = TextStyle(color = WidgetOnBackgroundVariant, fontSize = fontSize.sp(9)), maxLines = 1)
                }
            }
        }
        PageButton(if (compact) "↓" else "↓ 下页", "下一页", page.index + 1, key, page, page.hasNext, fontSize, layout)
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
    val modifier = GlanceModifier.height(layout.controlsHeightDp.dp)
        .background(if (enabled) WidgetAccentSurface else WidgetSurface).cornerRadius(10.dp)
        .padding(horizontal = 10.dp)
        .semantics { contentDescription = if (enabled) description else "$description，不可用" }
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
    val subtitle = when {
        inProgress -> "上课中 · 剩${snapshot.remainingMinutes(entry, nowMinuteOfDay)}分"
        isNext -> "下一节 · ${location ?: "第${block.startPeriod}–${block.endPeriod}节"}"
        else -> location ?: "第${block.startPeriod}–${block.endPeriod}节"
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
