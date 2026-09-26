package com.nullclass.widget

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ColumnScope
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import com.nullclass.core.model.TodaySnapshot
import com.nullclass.core.model.WidgetFontSize
import kotlinx.coroutines.flow.first
import java.time.LocalDateTime

/**
 * 下节课：按尺寸分三档。
 * - 完整（≥150dp 高）：状态标签、课名、时间段、地点，上课中再加一条进度条；
 * - 中等（≥100dp 高）：状态标签、课名，时间段与地点并一行；
 * - 紧凑：色条 + 课名 + 开始时间（或剩余分钟）。
 */
class NextClassGlanceWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

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
            WidgetTheme { NextClassWidgetContent(live.snapshot, live.now.hour * 60 + live.now.minute, fontSize) }
        }
    }
}

private enum class NextClassLayout { Compact, Medium, Full }

@Composable
internal fun NextClassWidgetContent(
    snapshot: TodaySnapshot,
    nowMinuteOfDay: Int,
    fontSize: WidgetFontSize = WidgetFontSize.STANDARD,
) {
    val size = LocalSize.current
    val layout = when {
        size.height >= 150.dp && size.width >= 140.dp -> NextClassLayout.Full
        size.height >= 100.dp && size.width >= 120.dp -> NextClassLayout.Medium
        else -> NextClassLayout.Compact
    }
    val tiny = size.height < 64.dp || size.width < 100.dp
    val padding = when {
        layout == NextClassLayout.Full -> 14.dp
        layout == NextClassLayout.Medium -> 12.dp
        tiny -> 6.dp
        else -> 10.dp
    }
    val next = snapshot.nextUp(nowMinuteOfDay)
    Column(
        GlanceModifier.fillMaxSize().appWidgetBackground()
            .roundedBackground(WidgetColors.background, WidgetCorner.Root)
            .padding(padding).clickable(actionRunCallback<OpenAppAction>()),
        // 中等 / 完整档自己用权重撑满上下；紧凑档与空状态居中
        verticalAlignment = if (next != null && layout != NextClassLayout.Compact) {
            Alignment.Top
        } else {
            Alignment.CenterVertically
        },
    ) {
        when {
            next == null -> NextClassEmpty(snapshot, layout, fontSize)
            layout == NextClassLayout.Compact -> NextClassCompact(snapshot, next, nowMinuteOfDay, fontSize, tiny)
            else -> NextClassDetailed(snapshot, next, nowMinuteOfDay, fontSize, layout == NextClassLayout.Full)
        }
    }
}

@Composable
private fun ColumnScope.NextClassDetailed(
    snapshot: TodaySnapshot,
    next: TodaySnapshot.TodayEntry,
    nowMinuteOfDay: Int,
    fontSize: WidgetFontSize,
    full: Boolean,
) {
    val context = LocalContext.current
    val inProgress = snapshot.inProgress(next, nowMinuteOfDay)
    val timeRange = context.getString(R.string.widget_next_time_range, next.startTime, next.endTime)
    val location = next.placed.block.location?.takeIf { it.isNotBlank() }
        ?: context.getString(R.string.widget_next_location_unknown)
    val timeStyle = TextStyle(color = WidgetColors.primary, fontSize = fontSize.sp(12), fontWeight = FontWeight.Medium)
    val locationStyle = TextStyle(color = WidgetColors.textVariant, fontSize = fontSize.sp(11))

    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        StatusChip(
            context.getString(if (inProgress) R.string.widget_next_in_class else R.string.widget_next_upcoming),
            fontSize,
        )
        Spacer(GlanceModifier.defaultWeight())
        if (inProgress) {
            val minutes = snapshot.remainingMinutes(next, nowMinuteOfDay)
            Text(
                context.resources.getQuantityString(R.plurals.widget_next_remaining, minutes, minutes),
                style = TextStyle(color = WidgetColors.primary, fontSize = fontSize.sp(11), fontWeight = FontWeight.Medium),
                maxLines = 1,
            )
        }
    }
    // 状态标签贴顶、课程信息沉底：小组件拉高时空白留在中间，而不是上下各空一截
    Spacer(GlanceModifier.defaultWeight())
    Column(GlanceModifier.fillMaxWidth()) {
        // 色条只陪着课名：在自适应高度的 Row 里用 fillMaxHeight 会把整行撑满，挤掉上下的内容
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                GlanceModifier.width(4.dp).height(fontSize.dp(if (full) 18 else 16))
                    .background(courseAccent(next.placed.course.colorIndex)).cornerRadius(2.dp),
            ) {}
            Spacer(GlanceModifier.width(8.dp))
            Text(
                next.placed.course.name,
                modifier = GlanceModifier.defaultWeight(),
                style = TextStyle(
                    color = WidgetColors.text,
                    fontSize = fontSize.sp(if (full) 17 else 15),
                    fontWeight = FontWeight.Bold,
                ),
                maxLines = if (full && LocalSize.current.height >= 180.dp) 2 else 1,
            )
        }
        if (full) {
            Spacer(GlanceModifier.height(4.dp))
            Text(timeRange, style = timeStyle, maxLines = 1)
            Spacer(GlanceModifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    ImageProvider(R.drawable.ic_widget_location),
                    contentDescription = null,
                    modifier = GlanceModifier.size(fontSize.dp(12)),
                    colorFilter = ColorFilter.tint(WidgetColors.textVariant),
                )
                Spacer(GlanceModifier.width(4.dp))
                Text(location, modifier = GlanceModifier.defaultWeight(), style = locationStyle, maxLines = 1)
            }
        } else {
            Spacer(GlanceModifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(timeRange, style = timeStyle, maxLines = 1)
                Spacer(GlanceModifier.width(8.dp))
                Text(location, modifier = GlanceModifier.defaultWeight(), style = locationStyle, maxLines = 1)
            }
        }
    }
    if (full && inProgress) {
        val length = (next.endMinuteOfDay - next.startMinuteOfDay).coerceAtLeast(1)
        Spacer(GlanceModifier.height(10.dp))
        LinearProgressIndicator(
            progress = ((nowMinuteOfDay - next.startMinuteOfDay).toFloat() / length).coerceIn(0f, 1f),
            modifier = GlanceModifier.fillMaxWidth().height(4.dp),
            color = WidgetColors.primary,
            backgroundColor = WidgetColors.highlight,
        )
    }
}

@Composable
private fun NextClassCompact(
    snapshot: TodaySnapshot,
    next: TodaySnapshot.TodayEntry,
    nowMinuteOfDay: Int,
    fontSize: WidgetFontSize,
    tiny: Boolean,
) {
    val context = LocalContext.current
    val inProgress = snapshot.inProgress(next, nowMinuteOfDay)
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        CourseAccentBar(next)
        Spacer(GlanceModifier.width(if (tiny) 5.dp else 8.dp))
        Column(GlanceModifier.defaultWeight()) {
            Text(
                next.placed.course.name,
                style = TextStyle(
                    color = WidgetColors.text,
                    fontSize = fontSize.sp(if (tiny) 12 else 13),
                    fontWeight = FontWeight.Bold,
                ),
                maxLines = 1,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (inProgress) {
                        context.getString(
                            R.string.widget_row_status_in_class,
                            snapshot.remainingMinutes(next, nowMinuteOfDay),
                        )
                    } else {
                        next.startTime
                    },
                    style = TextStyle(color = WidgetColors.primary, fontSize = fontSize.sp(10), fontWeight = FontWeight.Medium),
                    maxLines = 1,
                )
                val location = next.placed.block.location?.takeIf { it.isNotBlank() }
                if (!tiny && !inProgress && location != null) {
                    Spacer(GlanceModifier.width(6.dp))
                    Text(
                        location,
                        modifier = GlanceModifier.defaultWeight(),
                        style = TextStyle(color = WidgetColors.textVariant, fontSize = fontSize.sp(10)),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun NextClassEmpty(snapshot: TodaySnapshot, layout: NextClassLayout, fontSize: WidgetFontSize) {
    val context = LocalContext.current
    @DrawableRes val icon: Int
    val title: Int
    val subtitle: Int
    when {
        snapshot.termName.isEmpty() -> {
            icon = R.drawable.ic_widget_add
            title = R.string.widget_empty_no_timetable
            subtitle = R.string.widget_empty_no_timetable_desc
        }
        snapshot.blocks.isEmpty() -> {
            icon = R.drawable.ic_widget_event_available
            title = R.string.widget_empty_no_class
            subtitle = R.string.widget_empty_no_class_desc
        }
        else -> {
            icon = R.drawable.ic_widget_event_available
            title = R.string.widget_next_empty_finished
            subtitle = R.string.widget_empty_finished_desc
        }
    }
    Column(
        GlanceModifier.fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (layout != NextClassLayout.Compact) {
            Image(
                ImageProvider(icon),
                contentDescription = null,
                modifier = GlanceModifier.size(fontSize.dp(24)),
                colorFilter = ColorFilter.tint(WidgetColors.primary),
            )
            Spacer(GlanceModifier.height(6.dp))
        }
        Text(
            context.getString(title),
            style = TextStyle(
                color = WidgetColors.text,
                fontSize = fontSize.sp(if (layout == NextClassLayout.Compact) 11 else 13),
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            ),
            maxLines = 2,
        )
        if (layout == NextClassLayout.Full) {
            Spacer(GlanceModifier.height(2.dp))
            Text(
                context.getString(subtitle),
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

@Composable
private fun StatusChip(text: String, fontSize: WidgetFontSize) {
    Box(
        GlanceModifier.roundedBackground(WidgetColors.highlight, WidgetCorner.Chip)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text,
            style = TextStyle(color = WidgetColors.onHighlight, fontSize = fontSize.sp(10), fontWeight = FontWeight.Bold),
            maxLines = 1,
        )
    }
}

/** 紧凑档的课程色条：fillMaxHeight 会把所在的 Row 撑到小组件内高，色条与小组件等高。 */
@Composable
private fun CourseAccentBar(entry: TodaySnapshot.TodayEntry) {
    Box(
        GlanceModifier.width(4.dp).fillMaxHeight()
            .background(courseAccent(entry.placed.course.colorIndex)).cornerRadius(2.dp),
    ) {}
}
