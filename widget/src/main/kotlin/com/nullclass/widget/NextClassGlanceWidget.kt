package com.nullclass.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
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
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.nullclass.core.ui.theme.courseColor
import java.time.LocalTime

/** 「下节课」紧凑小组件（1×1 ~ 2×1）。 */
class NextClassGlanceWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val entryPoint = widgetEntryPoint(context)
        val snapshot = buildTodaySnapshot(entryPoint.termRepository(), entryPoint.courseRepository())
        provideContent {
            NextClassWidgetContent(snapshot, LocalTime.now().let { it.hour * 60 + it.minute })
        }
    }
}

@Composable
internal fun NextClassWidgetContent(snapshot: TodaySnapshot, nowMinuteOfDay: Int) {
    val next = snapshot.nextUp(nowMinuteOfDay)
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(WidgetBackground)
            .cornerRadius(16.dp)
            .padding(10.dp)
            .clickable(actionRunCallback<OpenAppAction>()),
        contentAlignment = Alignment.Center,
    ) {
        when {
            snapshot.termName.isEmpty() -> Text(
                "空课",
                style = TextStyle(color = WidgetAccent, fontSize = 14.sp, fontWeight = FontWeight.Medium),
            )
            next == null && snapshot.blocks.isEmpty() -> Text(
                "今天没有课 🎉",
                style = TextStyle(color = WidgetOnBackgroundVariant, fontSize = 12.sp),
            )
            next == null -> Text(
                "今天课程已结束",
                style = TextStyle(color = WidgetOnBackgroundVariant, fontSize = 12.sp),
            )
            else -> {
                val color = courseColor(next.placed.course.colorIndex)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        if (snapshot.inProgress(next, nowMinuteOfDay)) "进行中" else "下一节",
                        style = TextStyle(color = WidgetAccent, fontSize = 10.sp, fontWeight = FontWeight.Medium),
                    )
                    Spacer(GlanceModifier.height(2.dp))
                    Text(
                        next.placed.course.name,
                        style = TextStyle(
                            color = androidx.glance.color.ColorProvider(
                                day = color.content,
                                night = color.content,
                            ),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                        maxLines = 2,
                    )
                    Spacer(GlanceModifier.height(2.dp))
                    Text(
                        listOfNotNull(
                            if (snapshot.inProgress(next, nowMinuteOfDay)) "${next.endTime} 结束" else next.startTime,
                            next.placed.block.location?.takeIf { it.isNotBlank() },
                        ).joinToString(" · "),
                        style = TextStyle(color = WidgetOnBackgroundVariant, fontSize = 11.sp),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
