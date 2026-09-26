package com.nullclass.feature.schedule

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.nullclass.core.model.BlockBorderStyle
import com.nullclass.core.model.BlockTextAlign
import com.nullclass.core.model.Course
import com.nullclass.core.model.DefaultPeriodTimes
import com.nullclass.core.model.PlacedBlock
import com.nullclass.core.model.ScheduleAppearance
import com.nullclass.core.model.ScheduleBlock
import com.nullclass.core.model.Term
import com.nullclass.core.ui.layout.AdaptiveColumn
import com.nullclass.core.ui.layout.LocalWindowSize
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Locale
import kotlin.math.roundToInt
import com.nullclass.core.ui.R as CoreR

/** 竖屏时钉在页面顶部的预览高度：表头 + 三四节课，下面还留得出大半屏给设置项。 */
private val PreviewHeight = 260.dp

/** 表头「自动」时滑块停的位置：星期 + 日期两行加上下内边距，大致就是这么高。 */
private const val AutoHeaderHeightThumbDp = 56

/**
 * 「我的 → 个性化设置」：课表背景、24 小时时间轴、表头与侧边栏、课程卡片样式。
 *
 * 页面顶部钉着一块实时预览，与课表页共用同一套绘制代码（[WeekHeader] / [WeekGrid] / [gridStyleOf]），
 * 拖滑块时逐帧跟手。页面放在 `:feature:schedule` 而不是设置模块，就是为了直接复用这几个内部组件。
 * 手机横屏（矮屏）时改为左右分栏，不然预览会吃掉整屏。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonalizationScreen(
    onBack: () -> Unit,
    viewModel: PersonalizationViewModel = hiltViewModel(),
) {
    val appearance by viewModel.appearance.collectAsState()
    val switches by viewModel.switches.collectAsState()
    val wallpaper by viewModel.wallpaper.collectAsState()
    val previewWeek by viewModel.previewWeek.collectAsState()
    var confirmReset by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.schedule_personalize_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(CoreR.string.common_back),
                        )
                    }
                },
                actions = {
                    TextButton(onClick = { confirmReset = true }) {
                        Text(stringResource(R.string.schedule_personalize_reset))
                    }
                },
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        // 偏好还没读出来：先空着，别拿默认值画一帧再跳
        val current = appearance ?: return@Scaffold
        val style = remember(current, switches) {
            gridStyleOf(current, switches.showTimeInCards, switches.showGridLines)
        }
        val sample = rememberSampleWeek()
        val week = previewWeek ?: sample
        if (LocalWindowSize.current.isCompactHeight) {
            Row(
                Modifier
                    .padding(padding)
                    .fillMaxSize(),
            ) {
                AppearancePreview(
                    style = style,
                    week = week,
                    showWeekend = switches.showWeekend,
                    wallpaper = wallpaper,
                    modifier = Modifier
                        .weight(0.45f)
                        .fillMaxHeight()
                        .padding(start = 16.dp, top = 8.dp, bottom = 16.dp),
                )
                AppearanceSettings(current, switches, viewModel, Modifier.weight(0.55f))
            }
        } else {
            Column(
                Modifier
                    .padding(padding)
                    .fillMaxSize(),
            ) {
                AppearancePreview(
                    style = style,
                    week = week,
                    showWeekend = switches.showWeekend,
                    wallpaper = wallpaper,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(PreviewHeight)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
                AppearanceSettings(current, switches, viewModel, Modifier.weight(1f))
            }
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(R.string.schedule_personalize_reset_title)) },
            text = { Text(stringResource(R.string.schedule_personalize_reset_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmReset = false
                        viewModel.resetAll()
                    },
                ) { Text(stringResource(R.string.schedule_personalize_reset_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) {
                    Text(stringResource(CoreR.string.common_cancel))
                }
            },
        )
    }
}

/**
 * 预览：背景图 + 表头 + 网格，按真实尺寸画、框内可上下滑。不响应点课（这里不是课表页）。
 * 行高取「自动」时按 56dp 的下限画 —— 课表页会按屏幕撑高，预览这块地方撑不开。
 */
@Composable
private fun AppearancePreview(
    style: GridStyle,
    week: PreviewWeek,
    showWeekend: Boolean,
    wallpaper: Bitmap?,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.background)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
    ) {
        wallpaper?.let { ScheduleWallpaper(it) }
        Column(Modifier.fillMaxSize()) {
            val weekDays = week.term.visibleWeekDays(showWeekend)
            WeekHeader(
                term = week.term,
                week = week.week,
                weekDays = weekDays,
                todayDayOfWeek = week.todayDayOfWeek,
                style = style,
                dayOverrides = week.dayOverrides,
            )
            val rowHeight = style.rowHeight(week.periodTimes.size, availableHeight = 0.dp)
            Box(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberGridScrollState(style.timeline, week.periodTimes, rowHeight)),
            ) {
                WeekGrid(
                    periodTimes = week.periodTimes,
                    layout = week.layout,
                    weekDays = weekDays,
                    todayDayOfWeek = week.todayDayOfWeek,
                    style = style,
                    nowMinuteOfDay = null,
                    onBlockClick = {},
                    otherWeekLayout = week.otherWeekLayout,
                    rowHeight = rowHeight,
                )
            }
        }
    }
}

@Composable
private fun AppearanceSettings(
    current: ScheduleAppearance,
    switches: DisplaySwitches,
    viewModel: PersonalizationViewModel,
    modifier: Modifier = Modifier,
) {
    val wallpaper by viewModel.wallpaper.collectAsState()
    val importing by viewModel.importing.collectAsState()
    val importFailed by viewModel.importFailed.collectAsState()
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(viewModel::importWallpaper)
    }
    val auto = stringResource(R.string.schedule_personalize_value_auto)

    AdaptiveColumn(
        modifier = modifier,
        scrollState = rememberScrollState(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        imePadding = false,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ---- 背景 ----
        SettingSectionTitle(stringResource(R.string.schedule_personalize_section_background))
        Text(
            stringResource(R.string.schedule_personalize_background_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = {
                    pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                enabled = !importing,
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    stringResource(
                        if (wallpaper == null) {
                            R.string.schedule_personalize_background_pick
                        } else {
                            R.string.schedule_personalize_background_change
                        },
                    ),
                )
            }
            OutlinedButton(
                onClick = viewModel::removeWallpaper,
                enabled = wallpaper != null && !importing,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.schedule_personalize_background_remove))
            }
        }
        when {
            importing -> Text(
                stringResource(R.string.schedule_personalize_background_importing),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            importFailed -> Text(
                stringResource(R.string.schedule_personalize_background_failed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        // ---- 显示内容 ----
        SettingSectionTitle(stringResource(R.string.schedule_personalize_section_display))
        SettingSwitchRow(
            title = stringResource(R.string.schedule_personalize_timeline),
            description = stringResource(R.string.schedule_personalize_timeline_desc),
            checked = current.timelineMode,
            onCheckedChange = { on -> viewModel.update { it.copy(timelineMode = on) } },
        )
        SettingSwitchRow(
            title = stringResource(R.string.schedule_personalize_hide_period_times),
            description = stringResource(R.string.schedule_personalize_hide_period_times_desc),
            checked = current.hidePeriodTimes,
            onCheckedChange = { on -> viewModel.update { it.copy(hidePeriodTimes = on) } },
        )
        SettingSwitchRow(
            title = stringResource(R.string.schedule_personalize_hide_dates),
            description = stringResource(R.string.schedule_personalize_hide_dates_desc),
            checked = current.hideHeaderDates,
            onCheckedChange = { on -> viewModel.update { it.copy(hideHeaderDates = on) } },
        )
        SettingSwitchRow(
            title = stringResource(R.string.schedule_personalize_hide_grid_lines),
            checked = !switches.showGridLines,
            onCheckedChange = viewModel::setHideGridLines,
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        // ---- 课表页面 ----
        SettingSectionTitle(stringResource(R.string.schedule_personalize_section_page))
        ColorSetting(
            title = stringResource(R.string.schedule_personalize_text_color),
            description = stringResource(R.string.schedule_personalize_page_text_color_desc),
            color = current.pageTextColor,
            themeColor = MaterialTheme.colorScheme.onSurfaceVariant,
            onPick = { color -> viewModel.update { it.copy(pageTextColor = color) } },
            onDrag = { color -> viewModel.drag { it.copy(pageTextColor = color) } },
            onDragFinished = viewModel::commitDrag,
            onReset = current.pageTextColor?.let { { viewModel.update { it.copy(pageTextColor = null) } } },
        )
        if (current.timelineMode) {
            val range = ScheduleAppearance.TIMELINE_HOUR_HEIGHT_RANGE
            DpSlider(
                title = stringResource(R.string.schedule_personalize_row_height),
                description = stringResource(R.string.schedule_personalize_row_height_timeline_desc),
                value = current.timelineHourHeightDp,
                autoValue = ScheduleAppearance.DEFAULT_TIMELINE_HOUR_HEIGHT_DP,
                range = range,
                autoLabel = auto,
                viewModel = viewModel,
                edit = { dp -> { it.copy(timelineHourHeightDp = dp) } },
            )
        } else {
            DpSlider(
                title = stringResource(R.string.schedule_personalize_row_height),
                value = current.periodCellHeightDp,
                autoValue = PeriodCellHeight.value.roundToInt(),
                range = ScheduleAppearance.PERIOD_CELL_HEIGHT_RANGE,
                autoLabel = auto,
                viewModel = viewModel,
                edit = { dp -> { it.copy(periodCellHeightDp = dp) } },
            )
        }
        val autoSidebar = remember(current, switches) {
            gridStyleOf(current.copy(sidebarWidthDp = null), switches.showTimeInCards, switches.showGridLines)
                .sidebarWidth.value.roundToInt()
        }
        DpSlider(
            title = stringResource(R.string.schedule_personalize_sidebar_width),
            value = current.sidebarWidthDp,
            autoValue = autoSidebar,
            range = ScheduleAppearance.SIDEBAR_WIDTH_RANGE,
            autoLabel = auto,
            viewModel = viewModel,
            edit = { dp -> { it.copy(sidebarWidthDp = dp) } },
        )
        DpSlider(
            title = stringResource(R.string.schedule_personalize_header_height),
            value = current.headerHeightDp,
            autoValue = AutoHeaderHeightThumbDp,
            range = ScheduleAppearance.HEADER_HEIGHT_RANGE,
            autoLabel = auto,
            viewModel = viewModel,
            edit = { dp -> { it.copy(headerHeightDp = dp) } },
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        // ---- 课程卡片 ----
        SettingSectionTitle(stringResource(R.string.schedule_personalize_section_card))
        ColorSetting(
            title = stringResource(R.string.schedule_personalize_text_color),
            description = stringResource(R.string.schedule_personalize_card_text_color_desc),
            color = current.blockTextColor,
            themeColor = MaterialTheme.colorScheme.onSurface,
            onPick = { color -> viewModel.update { it.copy(blockTextColor = color) } },
            onDrag = { color -> viewModel.drag { it.copy(blockTextColor = color) } },
            onDragFinished = viewModel::commitDrag,
            onReset = current.blockTextColor?.let { { viewModel.update { it.copy(blockTextColor = null) } } },
        )
        SettingSegmentedRow(
            title = stringResource(R.string.schedule_personalize_text_align),
            options = BlockTextAlign.entries,
            selected = current.blockTextAlign,
            label = { stringResource(it.labelRes) },
            onSelect = { align -> viewModel.update { it.copy(blockTextAlign = align) } },
        )
        SettingSegmentedRow(
            title = stringResource(R.string.schedule_personalize_border),
            options = BlockBorderStyle.entries,
            selected = current.blockBorderStyle,
            label = { stringResource(it.labelRes) },
            onSelect = { border -> viewModel.update { it.copy(blockBorderStyle = border) } },
        )
        PercentSlider(
            title = stringResource(R.string.schedule_personalize_text_scale),
            value = current.blockTextScalePercent,
            defaultValue = ScheduleAppearance.DEFAULT_TEXT_SCALE_PERCENT,
            range = ScheduleAppearance.TEXT_SCALE_RANGE,
            viewModel = viewModel,
            edit = { percent -> { it.copy(blockTextScalePercent = percent) } },
        )
        val radiusRange = ScheduleAppearance.CORNER_RADIUS_RANGE
        SettingSliderRow(
            title = stringResource(R.string.schedule_personalize_corner_radius),
            valueLabel = current.blockCornerRadiusDp.toString(),
            value = current.blockCornerRadiusDp.toFloat(),
            valueRange = radiusRange.first.toFloat()..radiusRange.last.toFloat(),
            onValueChange = { v -> viewModel.drag { it.copy(blockCornerRadiusDp = v.roundToInt()) } },
            onValueChangeFinished = viewModel::commitDrag,
            onReset = resetIf(current.blockCornerRadiusDp != ScheduleAppearance.DEFAULT_CORNER_RADIUS_DP) {
                viewModel.update { it.copy(blockCornerRadiusDp = ScheduleAppearance.DEFAULT_CORNER_RADIUS_DP) }
            },
        )
        SettingSliderRow(
            title = stringResource(R.string.schedule_personalize_spacing),
            valueLabel = formatDp(current.blockSpacingDp),
            value = current.blockSpacingDp,
            valueRange = 0f..ScheduleAppearance.SPACING_MAX_DP,
            onValueChange = { v -> viewModel.drag { it.copy(blockSpacingDp = v) } },
            onValueChangeFinished = viewModel::commitDrag,
            onReset = resetIf(current.blockSpacingDp != ScheduleAppearance.DEFAULT_SPACING_DP) {
                viewModel.update { it.copy(blockSpacingDp = ScheduleAppearance.DEFAULT_SPACING_DP) }
            },
        )
        PercentSlider(
            title = stringResource(R.string.schedule_personalize_opacity),
            description = stringResource(R.string.schedule_personalize_opacity_desc),
            value = current.blockOpacityPercent,
            defaultValue = ScheduleAppearance.DEFAULT_OPACITY_PERCENT,
            range = ScheduleAppearance.OPACITY_RANGE,
            viewModel = viewModel,
            edit = { percent -> { it.copy(blockOpacityPercent = percent) } },
        )
    }
}

/**
 * 可以是「自动」的 dp 滑块（行高、侧边栏宽度、表头高度）。自动时滑块停在 [autoValue]、值显示「自动」；
 * 一拖就变成定值，↺ 回到自动。
 *
 * 松手时写的是最后一次拖动的草稿（见 PersonalizationViewModel.commitDrag）。
 */
@Composable
private fun DpSlider(
    title: String,
    value: Int?,
    autoValue: Int,
    range: IntRange,
    autoLabel: String,
    viewModel: PersonalizationViewModel,
    edit: (Int?) -> AppearanceEdit,
    description: String? = null,
) {
    SettingSliderRow(
        title = title,
        description = description,
        valueLabel = value?.toString() ?: autoLabel,
        value = (value ?: autoValue).toFloat(),
        valueRange = range.first.toFloat()..range.last.toFloat(),
        onValueChange = { v -> viewModel.drag(edit(v.roundToInt())) },
        onValueChangeFinished = viewModel::commitDrag,
        onReset = resetIf(value != null) { viewModel.update(edit(null)) },
    )
}

@Composable
private fun PercentSlider(
    title: String,
    value: Int,
    defaultValue: Int,
    range: IntRange,
    viewModel: PersonalizationViewModel,
    edit: (Int) -> AppearanceEdit,
    description: String? = null,
) {
    SettingSliderRow(
        title = title,
        description = description,
        valueLabel = stringResource(R.string.schedule_personalize_value_percent, value),
        value = value.toFloat(),
        valueRange = range.first.toFloat()..range.last.toFloat(),
        onValueChange = { v -> viewModel.drag(edit(v.roundToInt())) },
        onValueChangeFinished = viewModel::commitDrag,
        onReset = resetIf(value != defaultValue) { viewModel.update(edit(defaultValue)) },
    )
}

private fun resetIf(changed: Boolean, reset: () -> Unit): (() -> Unit)? = if (changed) reset else null

/** 1.5 → 「1.5」，2.0 → 「2」。 */
private fun formatDp(value: Float): String =
    if (value % 1f == 0f) value.roundToInt().toString() else String.format(Locale.ROOT, "%.1f", value)

private val BlockTextAlign.labelRes: Int
    get() = when (this) {
        BlockTextAlign.CENTER -> R.string.schedule_personalize_align_center
        BlockTextAlign.TOP_START -> R.string.schedule_personalize_align_top_start
    }

private val BlockBorderStyle.labelRes: Int
    get() = when (this) {
        BlockBorderStyle.NONE -> R.string.schedule_personalize_border_none
        BlockBorderStyle.SOLID -> R.string.schedule_personalize_border_solid
        BlockBorderStyle.DASHED -> R.string.schedule_personalize_border_dashed
    }

/** 示例课表：还没有学期、或本周一节课都没有时，预览用它。课名随界面语言。 */
@Composable
private fun rememberSampleWeek(): PreviewWeek {
    val names = listOf(
        stringResource(R.string.schedule_personalize_sample_course_1),
        stringResource(R.string.schedule_personalize_sample_course_2),
        stringResource(R.string.schedule_personalize_sample_course_3),
        stringResource(R.string.schedule_personalize_sample_course_4),
        stringResource(R.string.schedule_personalize_sample_course_5),
    )
    val rooms = listOf(
        stringResource(R.string.schedule_personalize_sample_room_1),
        stringResource(R.string.schedule_personalize_sample_room_2),
        stringResource(R.string.schedule_personalize_sample_room_3),
    )
    return remember(names, rooms) { sampleWeek(names, rooms, LocalDate.now()) }
}

/** 示例课程的排法：（星期, 起始节, 结束节, 课程下标, 教室下标）。 */
private val SampleSlots = listOf(
    SampleSlot(1, 1, 2, 0, 0),
    SampleSlot(1, 5, 6, 2, 1),
    SampleSlot(2, 3, 4, 1, 2),
    SampleSlot(3, 1, 2, 3, 0),
    SampleSlot(3, 7, 8, 1, 2),
    SampleSlot(4, 3, 4, 0, 0),
    SampleSlot(5, 1, 2, 4, 1),
)

/** 示例课程的颜色下标（CoursePalette）：蓝、橙、绿、紫、红。 */
private val SampleColors = listOf(5, 9, 6, 2, 0)

private data class SampleSlot(val day: Int, val start: Int, val end: Int, val course: Int, val room: Int)

private fun sampleWeek(names: List<String>, rooms: List<String>, today: LocalDate): PreviewWeek {
    val monday = today.with(DayOfWeek.MONDAY).toEpochDay()
    val term = Term(id = "preview", name = "", firstDayEpochDay = monday, totalWeeks = 1)
    val courses = names.mapIndexed { index, name ->
        Course(id = "preview-$index", name = name, colorIndex = SampleColors[index % SampleColors.size])
    }
    val layout = SampleSlots
        .map { slot ->
            PlacedBlock(
                course = courses[slot.course],
                block = ScheduleBlock(
                    startWeek = 1,
                    endWeek = 1,
                    dayOfWeek = slot.day,
                    startPeriod = slot.start,
                    endPeriod = slot.end,
                    location = rooms[slot.room],
                ),
            )
        }
        .groupBy { it.block.dayOfWeek }
        .mapValues { (_, blocks) -> blocks.sortedBy { it.block.startPeriod } }
    return PreviewWeek(
        term = term,
        week = 1,
        periodTimes = DefaultPeriodTimes.create(term.id),
        layout = layout,
        otherWeekLayout = emptyMap(),
        dayOverrides = emptyMap(),
        todayDayOfWeek = today.dayOfWeek.value,
    )
}
