package com.nullclass.feature.schedule

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import android.graphics.Color as AndroidColor

/** 分组标题。 */
@Composable
internal fun SettingSectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = modifier.padding(top = 8.dp),
    )
}

/** 标题 + 说明 + 右侧开关，整行可点。 */
@Composable
internal fun SettingSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    description: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Switch) { onCheckedChange(!checked) }
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title)
            description?.let { SettingDescription(it) }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SettingDescription(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * 滑块行：标题 · 当前值 · 改过时的单项恢复，下面一条滑块。
 *
 * 滑块本身是连续的（不画刻度点：一两百个档位的刻度挤在一起只剩一条毛边），
 * 取整交给调用方与 ScheduleAppearance.sanitized()。拖动中 [onValueChange] 只改预览，
 * 松手 [onValueChangeFinished] 才写盘。
 *
 * @param onReset 已是默认值时传 null，不显示恢复按钮
 */
@Composable
internal fun SettingSliderRow(
    title: String,
    valueLabel: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    onReset: (() -> Unit)?,
    description: String? = null,
) {
    Column(Modifier.fillMaxWidth()) {
        SettingValueHeader(title, valueLabel, onReset)
        description?.let { SettingDescription(it) }
        Slider(
            value = value.coerceIn(valueRange),
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = valueRange,
        )
    }
}

/** 标题 + 右侧的当前值 + 可选的恢复按钮。按钮位置始终占着，行高不会因它出现而跳动。 */
@Composable
private fun SettingValueHeader(title: String, valueLabel: String, onReset: (() -> Unit)?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, modifier = Modifier.weight(1f))
        Text(
            valueLabel,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            if (onReset != null) {
                IconButton(onClick = onReset) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = stringResource(R.string.schedule_personalize_reset_item),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

/** 标题 + 一排分段按钮。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun <T> SettingSegmentedRow(
    title: String,
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title)
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = selected == option,
                    onClick = { onSelect(option) },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                ) {
                    Text(label(option), fontSize = 13.sp)
                }
            }
        }
    }
}

/** 颜色选择器的预设色：白、黑、深灰、浅灰 —— 叠在照片上最常用的几种字色。 */
private val PresetColors = listOf(
    0xFFFFFFFF.toInt() to R.string.schedule_personalize_color_white,
    0xFF000000.toInt() to R.string.schedule_personalize_color_black,
    0xFF424242.toInt() to R.string.schedule_personalize_color_dark_gray,
    0xFFBDBDBD.toInt() to R.string.schedule_personalize_color_light_gray,
)

/**
 * 颜色设置：标题行（色块 + 当前值 + 恢复）点开后是预设色和色相 / 饱和度 / 亮度三条滑块。
 *
 * 三条滑块自己记着 HSV：颜色存的是 ARGB，饱和度拖到 0 时色相就从 ARGB 里消失了，
 * 每次都从 ARGB 反推的话，灰色上拖色相滑块会原地不动。只有外面把颜色换成了
 * 不是这三条滑块拼出来的值（点了预设、恢复默认、↺ 回到跟随主题）时，才按新颜色重新对齐滑块。
 *
 * @param color 当前颜色（ARGB）；null = 跟随主题
 * @param themeColor 跟随主题时实际显示的颜色：色块画它，滑块第一次展开也从它起步
 * @param onPick 点了预设色（直接写盘）
 * @param onDrag 拖动滑块中（只改预览）
 * @param onDragFinished 滑块松手（把最后一次拖动写盘）
 * @param onReset 回到跟随主题；已是跟随主题时为 null
 */
@Composable
internal fun ColorSetting(
    title: String,
    description: String,
    color: Int?,
    themeColor: Color,
    onPick: (Int) -> Unit,
    onDrag: (Int) -> Unit,
    onDragFinished: () -> Unit,
    onReset: (() -> Unit)?,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val start = color ?: themeColor.toArgb()
    val initialHsv = remember { hsvOf(start) }
    var hue by remember { mutableFloatStateOf(initialHsv[0]) }
    var saturation by remember { mutableFloatStateOf(initialHsv[1]) }
    var brightness by remember { mutableFloatStateOf(initialHsv[2]) }
    // 外面换了颜色（预设、恢复默认、别处改了）且不是三条滑块拼出来的 → 重新对齐。
    // 回到跟随主题（null）时对齐到主题色：不然滑块还停在上一次自定义的色相上，
    // 再拖任意一条都会拼出一个和主题色毫不相干的颜色。
    // 灰色（饱和度 0）没有色相可言，保留滑块上原来的色相
    LaunchedEffect(color) {
        val target = color ?: themeColor.toArgb()
        if (colorOf(hue, saturation, brightness) != target) {
            val hsv = hsvOf(target)
            if (hsv[1] > 0f) hue = hsv[0]
            saturation = hsv[1]
            brightness = hsv[2]
        }
    }
    val shown = color?.let(::Color) ?: themeColor
    val followTheme = stringResource(R.string.schedule_personalize_color_follow_theme)

    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title)
                SettingDescription(description)
            }
            ColorSwatch(shown, Modifier.padding(start = 12.dp))
            Text(
                if (color == null) followTheme else hexOf(color),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp),
            )
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                if (onReset != null) {
                    IconButton(onClick = onReset) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.schedule_personalize_reset_item),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier.padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PresetColors.forEach { (preset, labelRes) ->
                        val label = stringResource(labelRes)
                        ColorSwatch(
                            color = Color(preset),
                            selected = color == preset,
                            modifier = Modifier
                                .clip(CircleShape)
                                .clickable { onPick(preset) }
                                .semantics { contentDescription = label },
                        )
                    }
                }
                GradientSlider(
                    label = stringResource(R.string.schedule_personalize_color_hue),
                    value = hue,
                    valueRange = 0f..360f,
                    brush = Brush.horizontalGradient(
                        listOf(0f, 60f, 120f, 180f, 240f, 300f, 360f).map { Color(colorOf(it, 1f, 1f)) },
                    ),
                    onValueChange = {
                        hue = it
                        onDrag(colorOf(hue, saturation, brightness))
                    },
                    onValueChangeFinished = onDragFinished,
                )
                GradientSlider(
                    label = stringResource(R.string.schedule_personalize_color_saturation),
                    value = saturation,
                    valueRange = 0f..1f,
                    brush = Brush.horizontalGradient(
                        listOf(Color(colorOf(hue, 0f, brightness)), Color(colorOf(hue, 1f, brightness))),
                    ),
                    onValueChange = {
                        saturation = it
                        onDrag(colorOf(hue, saturation, brightness))
                    },
                    onValueChangeFinished = onDragFinished,
                )
                GradientSlider(
                    label = stringResource(R.string.schedule_personalize_color_brightness),
                    value = brightness,
                    valueRange = 0f..1f,
                    brush = Brush.horizontalGradient(
                        listOf(Color.Black, Color(colorOf(hue, saturation, 1f))),
                    ),
                    onValueChange = {
                        brightness = it
                        onDrag(colorOf(hue, saturation, brightness))
                    },
                    onValueChangeFinished = onDragFinished,
                )
            }
        }
    }
}

/** 轨道画成渐变色的滑块，左侧一个定宽标签。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GradientSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    brush: Brush,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            // 英文「Saturation」「Brightness」要这么宽，中英文下三条滑块才对得齐
            modifier = Modifier.width(72.dp),
        )
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = valueRange,
            colors = SliderDefaults.colors(
                activeTrackColor = Color.Transparent,
                inactiveTrackColor = Color.Transparent,
            ),
            track = {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(12.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(brush)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp)),
                )
            },
            modifier = Modifier.weight(1f),
        )
    }
}

/** 圆形色块。浅色背景上的白色、深色背景上的黑色都要看得见，所以总带一圈描边。 */
@Composable
private fun ColorSwatch(color: Color, modifier: Modifier = Modifier, selected: Boolean = false) {
    Box(
        modifier = modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(color)
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                shape = CircleShape,
            ),
    )
}

private fun hsvOf(argb: Int): FloatArray = FloatArray(3).also { AndroidColor.colorToHSV(argb, it) }

private fun colorOf(hue: Float, saturation: Float, brightness: Float): Int =
    AndroidColor.HSVToColor(floatArrayOf(hue.coerceIn(0f, 360f), saturation, brightness))

private fun hexOf(argb: Int): String = "#%06X".format(Locale.ROOT, argb and 0xFFFFFF)
