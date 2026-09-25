package com.nullclass.feature.settings

import androidx.compose.ui.res.pluralStringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DisplayMode
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.nullclass.core.model.ScheduleBlock
import com.nullclass.core.ui.R as CoreR
import com.nullclass.core.ui.i18n.dayOfWeekLabel
import com.nullclass.core.ui.i18n.periodRangeLabel
import com.nullclass.core.ui.layout.AdaptiveWidthWrapper
import com.nullclass.core.ui.layout.LocalWindowSize

/**
 * 「我的 → 快捷操作 → 快速删课」：按周次或某一天筛出课，勾选批量删除。
 *
 * 面向「这学期有几门课退掉了 / 教务导多了」这类收尾场景：课表页长按一门删一门要先
 * 翻到那一周那一格，而用户记得的往往是「周三下午那门」或「第 10 周之后就没课了」。
 *
 * 删除粒度是**整门课**（与课表页长按删除同一口径：软删除 + 级联其时间安排与关联考试）。
 * 只想某一天不上课是另一件事，页头会把人指去「通知与提醒 → 跳过日期」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseCleanupScreen(
    onBack: () -> Unit,
    viewModel: CourseCleanupViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    // 勾选存 saveable：转屏/进程重建后还记得勾了哪几门（Set 本身不可保存，存成 List）
    val selectionSaver = listSaver<Set<String>, String>(save = { it.toList() }, restore = { it.toSet() })
    var selected: Set<String> by rememberSaveable(stateSaver = selectionSaver) {
        mutableStateOf(emptySet())
    }
    var confirming by remember { mutableStateOf(false) }
    var pickingDate by remember { mutableStateOf(false) }

    // 勾过但已经不在当前筛选里的课不算数：换了周次之后「删除(3)」却只看得见 1 门，
    // 按下去会删掉屏幕上没有的两门。切回去仍然记得勾选，所以只过滤不清空。
    val visibleRows = state.rows
    val selectedRows = visibleRows.filter { it.course.id in selected }

    val appBarScrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(appBarScrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_cleanup_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(CoreR.string.common_back),
                        )
                    }
                },
                actions = {
                    if (visibleRows.isNotEmpty()) {
                        val allSelected = selectedRows.size == visibleRows.size
                        TextButton(
                            onClick = {
                                selected = if (allSelected) {
                                    selected - visibleRows.map { it.course.id }.toSet()
                                } else {
                                    selected + visibleRows.map { it.course.id }
                                }
                            },
                        ) {
                            Text(
                                stringResource(
                                    if (allSelected) {
                                        CoreR.string.common_deselect_all
                                    } else {
                                        CoreR.string.common_select_all
                                    },
                                ),
                            )
                        }
                    }
                },
                scrollBehavior = appBarScrollBehavior,
            )
        },
        bottomBar = {
            if (selectedRows.isNotEmpty()) {
                Surface(tonalElevation = 3.dp) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            pluralStringResource(R.plurals.settings_cleanup_selected, selectedRows.size, selectedRows.size),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Button(onClick = { confirming = true }) {
                            Text(stringResource(CoreR.string.common_delete))
                        }
                    }
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        val term = state.term
        if (term == null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (state.loading) {
                        stringResource(CoreR.string.common_loading)
                    } else {
                        stringResource(R.string.settings_cleanup_no_term)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }

        AdaptiveWidthWrapper(modifier = Modifier.padding(padding)) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
        ) {
            Text(
                stringResource(R.string.settings_cleanup_hint) +
                    stringResource(R.string.settings_cleanup_hint_alt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
            )

            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                val options = listOf(
                    CleanupFilter.WEEK to R.string.settings_cleanup_by_week,
                    CleanupFilter.DATE to R.string.settings_cleanup_by_date,
                )
                options.forEachIndexed { index, (value, labelRes) ->
                    SegmentedButton(
                        selected = state.filter == value,
                        onClick = { viewModel.setFilter(value) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                    ) { Text(stringResource(labelRes)) }
                }
            }

            when (state.filter) {
                CleanupFilter.WEEK -> {
                    val weekListState = rememberLazyListState()
                    // 只在进页/切模式时把当前周滚进视野；用户自己点芯片时不抢滚动位置
                    LaunchedEffect(term.id, state.filter) {
                        weekListState.scrollToItem((state.week - 2).coerceAtLeast(0))
                    }
                    LazyRow(
                        state = weekListState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(term.totalWeeks) { index ->
                            val week = index + 1
                            FilterChip(
                                selected = state.week == week,
                                onClick = { viewModel.setWeek(week) },
                                label = {
                                    Text(
                                        stringResource(
                                            if (week == state.todayWeek) {
                                                R.string.settings_cleanup_week_current
                                            } else {
                                                R.string.settings_cleanup_week
                                            },
                                            week,
                                        ),
                                    )
                                },
                            )
                        }
                    }
                }

                CleanupFilter.DATE -> {
                    OutlinedButton(
                        onClick = { pickingDate = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                    ) { Text(dayLabel(state.date)) }
                    val swappedFrom = state.swappedFrom
                    val hint = when {
                        state.dateOutOfTerm -> stringResource(R.string.settings_cleanup_out_of_term)
                        swappedFrom != null -> stringResource(
                            R.string.settings_cleanup_swapped,
                            dayLabel(swappedFrom),
                        )
                        else -> null
                    }
                    hint?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }

            if (visibleRows.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        when (state.filter) {
                            CleanupFilter.WEEK ->
                                stringResource(R.string.settings_cleanup_week_empty, state.week)
                            CleanupFilter.DATE -> stringResource(R.string.settings_cleanup_day_empty)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    Modifier
                        .fillMaxSize()
                        .padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) {
                    items(visibleRows, key = { it.course.id }) { row ->
                        CleanupCourseRow(
                            row = row,
                            showDayOfWeek = state.filter == CleanupFilter.WEEK,
                            checked = row.course.id in selected,
                            onToggle = {
                                selected = if (row.course.id in selected) {
                                    selected - row.course.id
                                } else {
                                    selected + row.course.id
                                }
                            },
                        )
                    }
                }
            }
        }
        }

        if (pickingDate) {
            // key(state.date)：rememberDatePickerState 内部是无 key 的 rememberSaveable，
            // 换了天再开对话框时不换组合键就会沿用上一次选中的那天（见 DaySwapScreen 同款注释）
            key(state.date) {
                val pickerState = rememberDatePickerState(
                    initialSelectedDateMillis = state.date * MILLIS_PER_DAY,
                    initialDisplayedMonthMillis = state.date * MILLIS_PER_DAY,
                    selectableDates = remember(term) { termSelectableDates(term) },
                    // 矮屏（手机横屏）放不下 568dp 的日历，直接开输入模式
                    initialDisplayMode = if (LocalWindowSize.current.isCompactHeight) {
                        DisplayMode.Input
                    } else {
                        DisplayMode.Picker
                    },
                )
                DatePickerDialog(
                    onDismissRequest = { pickingDate = false },
                    confirmButton = {
                        val picked = pickerState.selectedDateMillis?.let { epochDayOfMillis(it) }
                        TextButton(
                            enabled = picked != null,
                            onClick = {
                                picked?.let { viewModel.setDate(it) }
                                pickingDate = false
                            },
                        ) { Text(stringResource(CoreR.string.common_confirm)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { pickingDate = false }) {
                            Text(stringResource(CoreR.string.common_cancel))
                        }
                    },
                ) {
                    DatePicker(state = pickerState)
                }
            }
        }
    }

    if (confirming) {
        // 只删「此刻屏幕上勾着的」那几门：勾过但已被筛选挡掉的课不在其中
        val doomed = selectedRows
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(pluralStringResource(R.plurals.settings_cleanup_confirm_title, doomed.size, doomed.size)) },
            text = {
                Text(
                    doomed.joinToString(stringResource(CoreR.string.common_list_separator)) { it.course.name } + "\n\n" +
                        stringResource(R.string.settings_cleanup_confirm_desc),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirming = false
                        val ids = doomed.map { it.course.id }
                        viewModel.deleteCourses(ids)
                        selected = selected - ids.toSet()
                    },
                ) {
                    Text(
                        stringResource(CoreR.string.common_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) {
                    Text(stringResource(CoreR.string.common_cancel))
                }
            },
        )
    }
}

/** 一门课一行：勾选框 + 课名/老师 + 命中的时间安排。 */
@Composable
private fun CleanupCourseRow(
    row: CleanupRow,
    showDayOfWeek: Boolean,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        onClick = onToggle,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = checked, onCheckedChange = { onToggle() })
            Column(Modifier.weight(1f)) {
                Text(
                    row.course.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                row.course.teacher?.takeIf { it.isNotBlank() }?.let { teacher ->
                    Text(
                        teacher,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                row.matched.forEach { block ->
                    Text(
                        blockLabel(block, showDayOfWeek),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (row.totalBlocks > row.matched.size) {
                    Text(
                        (row.totalBlocks - row.matched.size).let { others ->
                            pluralStringResource(R.plurals.settings_cleanup_other_blocks, others, others)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

/** 「周二 · 3-4节 · A101」；按日期筛时星期是废话，省掉。 */
@Composable
private fun blockLabel(block: ScheduleBlock, showDayOfWeek: Boolean): String = buildList {
    if (showDayOfWeek) add(dayOfWeekLabel(block.dayOfWeek))
    add(periodRangeLabel(block))
    block.location?.takeIf { it.isNotBlank() }?.let { add(it) }
}.joinToString(stringResource(CoreR.string.common_separator))
