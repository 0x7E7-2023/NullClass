package com.nullclass.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
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
import com.nullclass.core.ui.theme.courseColor
import java.time.LocalTime

/**
 * 「今日课程」小组件：标题（周次+星期）+ 今日课程列表，下一节/进行中高亮。
 *
 * 刷新双轨：App 活着时由 :app 的 WidgetAutoUpdater 推（Room Flow 触发），
 * 进程死后靠 DailyMaintenanceWorker 每 24h 兜底（跨天必须发生）。
 */
class TodayGlanceWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Responsive(
        setOf(
            DpSize(110.dp, 110.dp),
            DpSize(180.dp, 110.dp),
            DpSize(250.dp, 180.dp),
            DpSize(320.dp, 220.dp),
        ),
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val entryPoint = widgetEntryPoint(context)
        val snapshot = buildTodaySnapshot(entryPoint.termRepository(), entryPoint.courseRepository())
        provideContent {
            TodayWidgetContent(snapshot, LocalTime.now().let { it.hour * 60 + it.minute })
        }
    }
}

internal val WidgetBackground = ColorProvider(day = Color.White, night = Color(0xFF1A1B21))
internal val WidgetOnBackground = ColorProvider(day = Color(0xFF1B1C22), night = Color(0xFFE4E4EA))
internal val WidgetOnBackgroundVariant = ColorProvider(day = Color(0xFF6B6E76), night = Color(0xFF9DA0A8))
internal val WidgetAccent = ColorProvider(day = Color(0xFF4F46E5), night = Color(0xFFBEC2FF))
internal val WidgetDivider = ColorProvider(day = Color(0xFFE3E3E8), night = Color(0xFF2E3038))

@Composable
internal fun TodayWidgetContent(snapshot: TodaySnapshot, nowMinuteOfDay: Int) {
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(WidgetBackground)
            .cornerRadius(16.dp)
            .padding(12.dp)
            .clickable(actionRunCallback<OpenAppAction>()),
    ) {
        TodayFullContent(snapshot, nowMinuteOfDay)
    }
}

/** 标题行 + 完整列表（标题作为 LazyColumn 首 item，避免嵌套测量问题）。 */
@Composable
private fun TodayFullContent(snapshot: TodaySnapshot, nowMinuteOfDay: Int) {
    if (snapshot.termName.isEmpty()) {
        Column(modifier = GlanceModifier.fillMaxSize()) {
            Text(
                "空课",
                style = TextStyle(color = WidgetAccent, fontSize = 13.sp, fontWeight = FontWeight.Medium),
            )
            Spacer(GlanceModifier.height(6.dp))
            Box(GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "请先在应用中创建学期",
                    style = TextStyle(color = WidgetOnBackgroundVariant, fontSize = 12.sp),
                )
            }
        }
        return
    }

    LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
        item {
            Column {
                Text(
                    when (snapshot.weekNumber) {
                        null -> snapshot.termName // 学期还没开始（或已结束），只显学期名
                        else -> "第${snapshot.weekNumber}周 · ${ScheduleFormat.dayOfWeekLabel(java.time.LocalDate.now().dayOfWeek.value)}"
                    },
                    style = TextStyle(color = WidgetAccent, fontSize = 13.sp, fontWeight = FontWeight.Medium),
                )
                Spacer(GlanceModifier.height(4.dp))
                Box(GlanceModifier.fillMaxWidth().height(1.dp).background(WidgetDivider)) {}
                Spacer(GlanceModifier.height(4.dp))
            }
        }
        if (snapshot.blocks.isEmpty()) {
            item {
                Box(
                    GlanceModifier.fillMaxWidth().padding(vertical = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("今天没有课 🎉", style = TextStyle(color = WidgetOnBackgroundVariant, fontSize = 12.sp))
                }
            }
        } else {
            items(snapshot.blocks.size) { index ->
                TodayRow(snapshot.blocks[index], snapshot, nowMinuteOfDay)
            }
            if (snapshot.nextUp(nowMinuteOfDay) == null) {
                item {
                    Text(
                        "今天课程已结束",
                        style = TextStyle(color = WidgetOnBackgroundVariant, fontSize = 11.sp),
                        modifier = GlanceModifier.padding(top = 4.dp),
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
) {
    val isNext = snapshot.nextUp(nowMinuteOfDay)?.placed?.block?.id == entry.placed.block.id
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
        Column(modifier = GlanceModifier.width(38.dp)) {
            Text(
                entry.startTime,
                style = TextStyle(
                    color = if (isNext) WidgetAccent else WidgetOnBackgroundVariant,
                    fontSize = 11.sp,
                    fontWeight = if (isNext) FontWeight.Bold else FontWeight.Normal,
                ),
            )
            Text(
                entry.endTime,
                style = TextStyle(color = WidgetOnBackgroundVariant, fontSize = 9.sp),
            )
        }
        Spacer(GlanceModifier.width(6.dp))
        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(
                entry.placed.course.name,
                style = TextStyle(
                    color = WidgetOnBackground,
                    fontSize = 12.sp,
                    fontWeight = if (isNext) FontWeight.Bold else FontWeight.Medium,
                ),
                maxLines = 1,
            )
            entry.placed.block.location?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = TextStyle(color = WidgetOnBackgroundVariant, fontSize = 10.sp), maxLines = 1)
            }
        }
        if (isNext) {
            Text(
                if (snapshot.inProgress(entry, nowMinuteOfDay)) "进行中" else "下一节",
                style = TextStyle(color = WidgetAccent, fontSize = 10.sp, fontWeight = FontWeight.Medium),
            )
        }
    }
}
