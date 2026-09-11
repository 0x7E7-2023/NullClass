package com.nullclass.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.ColorProvider
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
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.nullclass.core.model.ScheduleFormat
import com.nullclass.core.model.TodaySnapshot
import com.nullclass.core.model.WidgetFontSize
import com.nullclass.core.model.buildWidgetAgenda
import com.nullclass.core.model.widgetCourseRowBudget
import com.nullclass.core.ui.theme.courseColor
import kotlinx.coroutines.flow.first
import java.time.LocalTime
import kotlin.math.roundToInt

/**
 * 「今日课程」小组件：标题（周次+星期）+ 未结束的课。
 *
 * 按高度和字号配额截断（[widgetCourseRowBudget]），已上完的丢掉，同课连堂合并。
 * 配额留出的空白里靠左另起一行「还剩 n 节」，不为脚注少排一门课。
 *
 * 刷新双轨：App 活着时由 :app 的 WidgetAutoUpdater 推（Room Flow 触发），
 * 进程死后靠 DailyMaintenanceWorker 每 24h 兜底（跨天必须发生）。
 */
class TodayGlanceWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val entryPoint = widgetEntryPoint(context)
        val snapshot = buildTodaySnapshot(entryPoint.termRepository(), entryPoint.courseRepository())
        val prefs = entryPoint.userPreferences()
        // updateAll 在 Glance 会话还活着时不会重跑 provideGlance，字号必须在
        // composition 里 collect，否则改第二档会继续用第一次读到的值。
        val initialFont = prefs.widgetFontSize.first()
        provideContent {
            val fontSize by prefs.widgetFontSize.collectAsState(initialFont)
            TodayWidgetContent(
                snapshot,
                LocalTime.now().let { it.hour * 60 + it.minute },
                fontSize,
            )
        }
    }
}

internal val WidgetBackground = ColorProvider(day = Color.White, night = Color(0xFF1A1B21))
internal val WidgetOnBackground = ColorProvider(day = Color(0xFF1B1C22), night = Color(0xFFE4E4EA))
internal val WidgetOnBackgroundVariant = ColorProvider(day = Color(0xFF6B6E76), night = Color(0xFF9DA0A8))
internal val WidgetAccent = ColorProvider(day = Color(0xFF4F46E5), night = Color(0xFFBEC2FF))
internal val WidgetDivider = ColorProvider(day = Color(0xFFE3E3E8), night = Color(0xFF2E3038))

@Composable
internal fun TodayWidgetContent(
    snapshot: TodaySnapshot,
    nowMinuteOfDay: Int,
    fontSize: WidgetFontSize = WidgetFontSize.STANDARD,
) {
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(WidgetBackground)
            .cornerRadius(16.dp)
            .padding(12.dp)
            .clickable(actionRunCallback<OpenAppAction>()),
    ) {
        TodayFullContent(snapshot, nowMinuteOfDay, fontSize)
    }
}

/** 标题 + 配额内的课。高度常数与 [widgetCourseRowBudget] 对齐。 */
@Composable
private fun TodayFullContent(
    snapshot: TodaySnapshot,
    nowMinuteOfDay: Int,
    fontSize: WidgetFontSize,
) {
    if (snapshot.termName.isEmpty()) {
        Column(modifier = GlanceModifier.fillMaxSize()) {
            Text(
                "空课",
                style = TextStyle(color = WidgetAccent, fontSize = fontSize.sp(13), fontWeight = FontWeight.Medium),
            )
            Spacer(GlanceModifier.height(6.dp))
            Box(GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "请先在应用中创建学期",
                    style = TextStyle(color = WidgetOnBackgroundVariant, fontSize = fontSize.sp(12)),
                )
            }
        }
        return
    }

    val heightDp = LocalSize.current.height.value.roundToInt()
    val fontScale = LocalContext.current.resources.configuration.fontScale
    val agenda = buildWidgetAgenda(
        snapshot,
        nowMinuteOfDay,
        widgetCourseRowBudget(heightDp, fontSize, fontScale),
    )
    Column(modifier = GlanceModifier.fillMaxSize()) {
        Text(
            when (snapshot.weekNumber) {
                null -> snapshot.termName // 学期还没开始（或已结束），只显学期名
                else -> "第${snapshot.weekNumber}周 · ${ScheduleFormat.dayOfWeekLabel(java.time.LocalDate.now().dayOfWeek.value)}"
            },
            style = TextStyle(color = WidgetAccent, fontSize = fontSize.sp(13), fontWeight = FontWeight.Medium),
        )
        Spacer(GlanceModifier.height(4.dp))
        Box(GlanceModifier.fillMaxWidth().height(1.dp).background(WidgetDivider)) {}
        Spacer(GlanceModifier.height(4.dp))

        when {
            snapshot.blocks.isEmpty() -> Box(
                GlanceModifier.fillMaxWidth().padding(vertical = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("今天没有课 🎉", style = TextStyle(color = WidgetOnBackgroundVariant, fontSize = fontSize.sp(12)))
            }
            agenda.rows.isEmpty() -> Text(
                "今天课程已结束 😴",
                style = TextStyle(color = WidgetOnBackgroundVariant, fontSize = fontSize.sp(11)),
                modifier = GlanceModifier.padding(top = 4.dp),
            )
            else -> {
                agenda.rows.forEachIndexed { index, entry ->
                    TodayRow(entry, snapshot, nowMinuteOfDay, fontSize, isNext = index == 0)
                }
                if (agenda.hiddenUpcoming > 0) {
                    Text(
                        "还剩${agenda.hiddenUpcoming}节",
                        style = TextStyle(color = WidgetOnBackgroundVariant, fontSize = fontSize.sp(11)),
                        modifier = GlanceModifier.padding(start = 6.dp, top = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun TodayRow(
    entry: TodaySnapshot.TodayEntry,
    snapshot: TodaySnapshot,
    nowMinuteOfDay: Int,
    fontSize: WidgetFontSize,
    isNext: Boolean,
) {
    val isInProgress = snapshot.inProgress(entry, nowMinuteOfDay)
    val color = courseColor(entry.placed.course.colorIndex)
    val rowBackground = if (isNext) {
        ColorProvider(day = color.content.copy(alpha = 0.14f), night = color.content.copy(alpha = 0.24f))
    } else {
        ColorProvider(day = Color.Transparent, night = Color.Transparent)
    }
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .background(rowBackground)
            .cornerRadius(8.dp)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = GlanceModifier.width(fontSize.dp(38))) {
            Text(
                entry.startTime,
                style = TextStyle(
                    color = if (isNext) WidgetAccent else WidgetOnBackgroundVariant,
                    fontSize = fontSize.sp(11),
                    fontWeight = if (isNext) FontWeight.Bold else FontWeight.Normal,
                ),
            )
            // Glance Text 无省略号能力，列宽只放得下 3-4 字符：
            // 上课中的行用「剩X分」替代下课时间（下课时间可由开始时间+剩余推出）
            Text(
                if (isInProgress) "剩${snapshot.remainingMinutes(entry, nowMinuteOfDay)}分" else entry.endTime,
                style = TextStyle(color = WidgetOnBackgroundVariant, fontSize = fontSize.sp(9)),
            )
        }
        Spacer(GlanceModifier.width(6.dp))
        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(
                entry.placed.course.name,
                style = TextStyle(
                    color = WidgetOnBackground,
                    fontSize = fontSize.sp(12),
                    fontWeight = if (isNext) FontWeight.Bold else FontWeight.Medium,
                ),
                maxLines = 1,
            )
            entry.placed.block.location?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = TextStyle(color = WidgetOnBackgroundVariant, fontSize = fontSize.sp(10)), maxLines = 1)
            }
        }
        if (isNext) {
            Text(
                if (isInProgress) "上课中" else "下一节",
                style = TextStyle(color = WidgetAccent, fontSize = fontSize.sp(10), fontWeight = FontWeight.Medium),
                maxLines = 1,
            )
        }
    }
}
