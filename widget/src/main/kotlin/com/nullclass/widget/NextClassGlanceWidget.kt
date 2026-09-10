package com.nullclass.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
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
import com.nullclass.core.model.TodaySnapshot
import com.nullclass.core.model.WidgetFontSize
import com.nullclass.core.ui.theme.courseColor
import kotlinx.coroutines.flow.first
import java.time.LocalTime

/** 「下节课」紧凑小组件（1×1 ~ 2×1）。 */
class NextClassGlanceWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val entryPoint = widgetEntryPoint(context)
        val snapshot = buildTodaySnapshot(entryPoint.termRepository(), entryPoint.courseRepository())
        val prefs = entryPoint.userPreferences()
        val initialFont = prefs.widgetFontSize.first()
        provideContent {
            val fontSize by prefs.widgetFontSize.collectAsState(initialFont)
            NextClassWidgetContent(
                snapshot,
                LocalTime.now().let { it.hour * 60 + it.minute },
                fontSize,
            )
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
                style = TextStyle(color = WidgetAccent, fontSize = fontSize.sp(14), fontWeight = FontWeight.Medium),
            )
            next == null && snapshot.blocks.isEmpty() -> Text(
                "今天没有课 🎉",
                style = TextStyle(color = WidgetOnBackgroundVariant, fontSize = fontSize.sp(12)),
            )
            next == null -> Text(
                "今天课程已结束 😴",
                style = TextStyle(color = WidgetOnBackgroundVariant, fontSize = fontSize.sp(12)),
            )
            else -> {
                val color = courseColor(next.placed.course.colorIndex)
                val inProgress = snapshot.inProgress(next, nowMinuteOfDay)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        if (inProgress) "上课中" else "下一节",
                        style = TextStyle(color = WidgetAccent, fontSize = fontSize.sp(10), fontWeight = FontWeight.Medium),
                    )
                    Spacer(GlanceModifier.height(2.dp))
                    Text(
                        next.placed.course.name,
                        style = TextStyle(
                            color = androidx.glance.color.ColorProvider(
                                day = color.content,
                                night = color.content,
                            ),
                            fontSize = fontSize.sp(16),
                            fontWeight = FontWeight.Bold,
                        ),
                        maxLines = 2,
                    )
                    Spacer(GlanceModifier.height(2.dp))
                    Text(
                        listOfNotNull(
                            // Glance 无省略号，保持短于原「9:40 结束」口径：纯分钟数
                            if (inProgress) "还剩${snapshot.remainingMinutes(next, nowMinuteOfDay)}分" else next.startTime,
                            next.placed.block.location?.takeIf { it.isNotBlank() },
                        ).joinToString(" · "),
                        style = TextStyle(color = WidgetOnBackgroundVariant, fontSize = fontSize.sp(11)),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
