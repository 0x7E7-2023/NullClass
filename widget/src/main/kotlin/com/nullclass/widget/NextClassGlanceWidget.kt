package com.nullclass.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
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
import com.nullclass.core.model.TodaySnapshot
import com.nullclass.core.model.WidgetFontSize
import com.nullclass.core.ui.theme.courseColor
import kotlinx.coroutines.flow.first
import java.time.LocalTime

/** 下节课：紧凑尺寸只保留课程和时间；宽高充足时展示教室与完整时段。 */
class NextClassGlanceWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

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
            val now = LocalTime.now()
            NextClassWidgetContent(snapshot, now.hour * 60 + now.minute, fontSize)
        }
    }
}

@Composable
internal fun NextClassWidgetContent(
    snapshot: TodaySnapshot,
    nowMinuteOfDay: Int,
    fontSize: WidgetFontSize = WidgetFontSize.STANDARD,
) {
    val next = snapshot.nextUp(nowMinuteOfDay)
    val compact = LocalSize.current.height < 110.dp || LocalSize.current.width < 140.dp
    val tiny = LocalSize.current.height < 72.dp
    val padding = if (tiny) 6.dp else if (compact) 10.dp else 14.dp
    Column(
        GlanceModifier.fillMaxSize().background(WidgetBackground).cornerRadius(20.dp)
            .padding(padding).clickable(actionRunCallback<OpenAppAction>()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (next == null) {
            Text(
                when {
                    snapshot.termName.isEmpty() -> "还没有课表"
                    snapshot.blocks.isEmpty() -> "今天没有课"
                    else -> "今日已完成"
                },
                style = TextStyle(color = WidgetOnBackground, fontSize = fontSize.sp(if (tiny) 11 else 13), fontWeight = FontWeight.Bold),
                maxLines = if (compact) 1 else 2,
            )
            if (!compact) {
                Spacer(GlanceModifier.height(6.dp))
                Text(
                    if (snapshot.termName.isEmpty()) "点此创建学期" else "留一点时间给自己",
                    style = TextStyle(color = WidgetOnBackgroundVariant, fontSize = fontSize.sp(11)),
                    maxLines = 2,
                )
            }
        } else {
            val inProgress = snapshot.inProgress(next, nowMinuteOfDay)
            if (!tiny) {
                Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(GlanceModifier.width(3.dp).height(12.dp)
                        .background(courseColor(next.placed.course.colorIndex).content).cornerRadius(2.dp)) {}
                    Spacer(GlanceModifier.width(6.dp))
                    Text(
                        if (inProgress) "正在上课" else "接下来",
                        style = TextStyle(color = WidgetAccent, fontSize = fontSize.sp(10), fontWeight = FontWeight.Medium),
                        maxLines = 1,
                    )
                }
                Spacer(GlanceModifier.height(if (compact) 4.dp else 10.dp))
            }
            Text(
                next.placed.course.name,
                style = TextStyle(color = WidgetOnBackground, fontSize = fontSize.sp(if (compact) 13 else 17), fontWeight = FontWeight.Bold),
                maxLines = if (compact) 1 else 2,
            )
            Spacer(GlanceModifier.height(if (compact) 2.dp else 8.dp))
            Text(
                if (inProgress) "还剩 ${snapshot.remainingMinutes(next, nowMinuteOfDay)} 分钟"
                else if (compact) next.startTime else "${next.startTime} — ${next.endTime}",
                style = TextStyle(color = WidgetAccent, fontSize = fontSize.sp(if (compact) 10 else 12), fontWeight = FontWeight.Medium),
                maxLines = 1,
            )
            if (!compact) {
                Spacer(GlanceModifier.height(6.dp))
                Text(
                    next.placed.block.location?.takeIf { it.isNotBlank() } ?: "教室待定",
                    style = TextStyle(color = WidgetOnBackgroundVariant, fontSize = fontSize.sp(11)),
                    maxLines = 1,
                )
            }
        }
    }
}
