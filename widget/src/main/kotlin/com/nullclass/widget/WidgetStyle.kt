package com.nullclass.widget

import android.os.Build
import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.ImageProvider
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.ContentScale
import androidx.glance.GlanceTheme
import androidx.glance.color.ColorProvider
import androidx.glance.color.colorProviders
import androidx.glance.unit.ColorProvider
import com.nullclass.core.ui.theme.CoursePalette
import com.nullclass.core.ui.theme.DarkBackground
import com.nullclass.core.ui.theme.DarkOnBackground
import com.nullclass.core.ui.theme.DarkOnPrimary
import com.nullclass.core.ui.theme.DarkOnPrimaryContainer
import com.nullclass.core.ui.theme.DarkOnSurface
import com.nullclass.core.ui.theme.DarkOnSurfaceVariant
import com.nullclass.core.ui.theme.DarkPrimary
import com.nullclass.core.ui.theme.DarkPrimaryContainer
import com.nullclass.core.ui.theme.DarkSecondary
import com.nullclass.core.ui.theme.DarkSurfaceVariant
import com.nullclass.core.ui.theme.Gray10
import com.nullclass.core.ui.theme.Gray20
import com.nullclass.core.ui.theme.Gray90
import com.nullclass.core.ui.theme.Gray95
import com.nullclass.core.ui.theme.LightBackground
import com.nullclass.core.ui.theme.LightOnBackground
import com.nullclass.core.ui.theme.LightOnPrimary
import com.nullclass.core.ui.theme.LightOnPrimaryContainer
import com.nullclass.core.ui.theme.LightOnSurface
import com.nullclass.core.ui.theme.LightOnSurfaceVariant
import com.nullclass.core.ui.theme.LightPrimary
import com.nullclass.core.ui.theme.LightPrimaryContainer
import com.nullclass.core.ui.theme.LightSecondary
import com.nullclass.core.ui.theme.LightSurfaceVariant

/**
 * 小组件配色。Android 12+ 用 Glance 自带的壁纸取色（与应用的 Material You 主题一致），
 * 更低版本回退到应用内置的 indigo 色板；深浅色都跟随系统。
 * 在 provideContent 里套一层 [WidgetTheme]，各处再经 [WidgetColors] 取色。
 */
@Composable
internal fun WidgetTheme(content: @Composable () -> Unit) {
    GlanceTheme(
        colors = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) GlanceTheme.colors else FallbackColors,
        content = content,
    )
}

/** 小组件里用到的几种颜色在主题中的角色。 */
internal object WidgetColors {
    /** 小组件底色。 */
    val background: ColorProvider @Composable get() = GlanceTheme.colors.widgetBackground

    /** 强调文字：时间、剩余分钟、图标。 */
    val primary: ColorProvider @Composable get() = GlanceTheme.colors.primary
    val text: ColorProvider @Composable get() = GlanceTheme.colors.onSurface
    val textVariant: ColorProvider @Composable get() = GlanceTheme.colors.onSurfaceVariant

    /** 翻页按钮；到头的那个只剩描边色的箭头。 */
    val button: ColorProvider @Composable get() = GlanceTheme.colors.secondaryContainer
    val onButton: ColorProvider @Composable get() = GlanceTheme.colors.onSecondaryContainer
    val disabled: ColorProvider @Composable get() = GlanceTheme.colors.outline
}

private val FallbackWidgetBackgroundDay = Color(0xFFEFF0FB)
private val FallbackWidgetBackgroundNight = Color(0xFF15161C)

/** Android 11 及以下的配色：与应用内置色板同源（见 core:ui 的 Color.kt）。 */
private val FallbackColors = colorProviders(
    primary = ColorProvider(day = LightPrimary, night = DarkPrimary),
    onPrimary = ColorProvider(day = LightOnPrimary, night = DarkOnPrimary),
    primaryContainer = ColorProvider(day = LightPrimaryContainer, night = DarkPrimaryContainer),
    onPrimaryContainer = ColorProvider(day = LightOnPrimaryContainer, night = DarkOnPrimaryContainer),
    secondary = ColorProvider(day = LightSecondary, night = DarkSecondary),
    onSecondary = ColorProvider(day = Color.White, night = Gray20),
    secondaryContainer = ColorProvider(day = Color(0xFFE0E0F3), night = Color(0xFF33344A)),
    onSecondaryContainer = ColorProvider(day = Color(0xFF1A1B2E), night = Color(0xFFE0E0F3)),
    tertiary = ColorProvider(day = LightSecondary, night = DarkSecondary),
    onTertiary = ColorProvider(day = Color.White, night = Gray20),
    tertiaryContainer = ColorProvider(day = Color(0xFFE0E0F3), night = Color(0xFF33344A)),
    onTertiaryContainer = ColorProvider(day = Color(0xFF1A1B2E), night = Color(0xFFE0E0F3)),
    error = ColorProvider(day = Color(0xFFB3261E), night = Color(0xFFF2B8B5)),
    errorContainer = ColorProvider(day = Color(0xFFF9DEDC), night = Color(0xFF8C1D18)),
    onError = ColorProvider(day = Color.White, night = Color(0xFF601410)),
    onErrorContainer = ColorProvider(day = Color(0xFF410E0B), night = Color(0xFFF2B8B5)),
    background = ColorProvider(day = LightBackground, night = DarkBackground),
    onBackground = ColorProvider(day = LightOnBackground, night = DarkOnBackground),
    surface = ColorProvider(day = Color(0xFFFCFBFF), night = Color(0xFF24252E)),
    onSurface = ColorProvider(day = LightOnSurface, night = DarkOnSurface),
    surfaceVariant = ColorProvider(day = LightSurfaceVariant, night = DarkSurfaceVariant),
    onSurfaceVariant = ColorProvider(day = LightOnSurfaceVariant, night = DarkOnSurfaceVariant),
    outline = ColorProvider(day = Color(0xFFB8B9C9), night = Color(0xFF55566A)),
    inverseOnSurface = ColorProvider(day = Gray95, night = Gray10),
    inverseSurface = ColorProvider(day = Gray20, night = Gray90),
    inversePrimary = ColorProvider(day = DarkPrimary, night = LightPrimary),
    widgetBackground = ColorProvider(day = FallbackWidgetBackgroundDay, night = FallbackWidgetBackgroundNight),
)

/** 课程原色（全饱和）：紧凑档的色条、进度条。深浅底上都认得出，与应用内课块的边框同色。 */
internal fun courseAccent(colorIndex: Int): ColorProvider =
    CoursePalette[colorIndex.mod(CoursePalette.size)].let { ColorProvider(day = it, night = it) }

/**
 * 课程卡片、状态标签的底色，直接取课程色：原色压低透明度铺在小组件底色上（与应用内课块同一做法），
 * 不再另配色条或主题强调色，一块上只有一种颜色。[emphasized]（上课中 / 下一节）同色加深一档。
 * 深色底更暗，透明度相应高一些，否则卡片融进底色。
 */
internal fun courseCardColor(colorIndex: Int, emphasized: Boolean = false): ColorProvider {
    val base = CoursePalette[colorIndex.mod(CoursePalette.size)]
    val day = base.copy(alpha = if (emphasized) 0.32f else 0.16f)
    val night = base.copy(alpha = if (emphasized) 0.40f else 0.22f)
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        ColorProvider(day = day, night = night)
    } else {
        // Android 11 及以下的圆角底色是给形状图片着色（SRC_ATOP），半透明色会和图片的白底混成不透明的浅色，
        // 深色模式下成了亮块。那边的小组件底色是固定的，先叠上去算成不透明色。
        ColorProvider(
            day = day.compositeOver(FallbackWidgetBackgroundDay),
            night = night.compositeOver(FallbackWidgetBackgroundNight),
        )
    }
}

internal enum class WidgetCorner(val radius: Dp, @DrawableRes val shape: Int) {
    Root(22.dp, R.drawable.widget_corner_22),
    Card(14.dp, R.drawable.widget_corner_14),
    Chip(11.dp, R.drawable.widget_corner_11),
}

/** 圆角底色。cornerRadius 在 Android 11 及以下不生效，那边改用圆角形状图片着色垫底。 */
internal fun GlanceModifier.roundedBackground(color: ColorProvider, corner: WidgetCorner): GlanceModifier =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        background(color).cornerRadius(corner.radius)
    } else {
        background(
            ImageProvider(corner.shape),
            contentScale = ContentScale.FillBounds,
            colorFilter = ColorFilter.tint(color),
        )
    }
