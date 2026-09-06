package com.nullclass.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle

/**
 * 「今日课程」小组件。
 *
 * 重要：Glance 的重绘时机由系统管控，不能依赖它实时刷新。
 * 数据变化后调用 GlanceAppWidget.update 扩展刷新实例，
 * M3 会接入 WorkManager 在每日零点与每节课结束时定点刷新。
 *
 * TODO(M3): 接入课表数据，显示今日课程列表与「下一节 · 还有 N 分钟」。
 */
class TodayGlanceWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Responsive(
        setOf(
            DpSize(180.dp, 110.dp),
            DpSize(250.dp, 180.dp),
            DpSize(320.dp, 220.dp),
        ),
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { TodayWidgetContent() }
    }
}

@Composable
private fun TodayWidgetContent() {
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(
                ColorProvider(
                    day = Color.White,
                    night = Color(0xFF1A1B21),
                ),
            )
            .cornerRadius(16.dp)
            .padding(12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "空课 · 暂无课程",
            style = TextStyle(
                color = ColorProvider(
                    day = Color(0xFF4F46E5),
                    night = Color(0xFFBEC2FF),
                ),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            ),
        )
    }
}
