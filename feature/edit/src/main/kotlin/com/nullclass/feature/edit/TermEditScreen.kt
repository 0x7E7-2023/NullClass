package com.nullclass.feature.edit

import androidx.compose.ui.res.pluralStringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DisplayMode
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.nullclass.core.model.MAX_TOTAL_WEEKS
import com.nullclass.core.model.Session
import com.nullclass.core.ui.i18n.dateLabel
import com.nullclass.core.ui.i18n.dayOfWeekLabel
import com.nullclass.core.ui.i18n.monthDayLabel
import com.nullclass.core.ui.i18n.resolve
import com.nullclass.core.ui.layout.AdaptiveColumn
import com.nullclass.core.ui.layout.LocalWindowSize
import com.nullclass.core.ui.R as CoreR
import java.time.LocalDate

/** 学期编辑：基本信息 + 节次时间表 + （新建时）从上学期复制课程 + 清空本学期课程。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TermEditScreen(
    termId: String?,
    onBack: () -> Unit,
    viewModel: TermEditViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    var showDatePicker by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }

    val appBarScrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(appBarScrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (state.isNew) R.string.edit_term_title_new else R.string.edit_term_title_edit,
                        ),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(CoreR.string.common_back),
                        )
                    }
                },
                actions = {
                    TextButton(
                        onClick = { viewModel.save(onBack) },
                        enabled = !state.loading && !state.saving && !state.clearing,
                    ) { Text(stringResource(CoreR.string.common_save)) }
                },
                scrollBehavior = appBarScrollBehavior,
            )
        },
        // safeDrawing：把 ime 算进 content padding，键盘弹出时滚动视口收缩，
        // 底部输入框滚得出来；顺带覆盖横屏侧边刘海。
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        if (state.loading) return@Scaffold

        AdaptiveColumn(
            modifier = Modifier.padding(padding),
            scrollState = rememberScrollState(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            imePadding = false,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            OutlinedTextField(
                value = state.name,
                onValueChange = viewModel::setName,
                label = { Text(stringResource(R.string.edit_term_name)) },
                placeholder = { Text(stringResource(R.string.edit_term_name_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            // 每周起始日：一周从哪天算起 = 第 1 周从哪天开始（同一个值的两种说法）
            val firstDay = LocalDate.ofEpochDay(state.firstDayEpochDay)
            Text(
                stringResource(R.string.edit_term_week_start),
                style = MaterialTheme.typography.labelLarge,
            )
            WeekStartDaySelector(
                selected = firstDay.dayOfWeek.value,
                onSelect = viewModel::setWeekStartDay,
            )
            if (firstDay.dayOfWeek.value != 1 && firstDay.dayOfWeek.value != 7) {
                Text(
                    stringResource(
                        R.string.edit_term_week_start_custom,
                        dayOfWeekLabel(firstDay.dayOfWeek.value),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            OutlinedButton(onClick = { showDatePicker = true }, modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(
                        R.string.edit_term_first_day,
                        dateLabel(state.firstDayEpochDay),
                    ),
                )
            }

            Text(
                stringResource(
                    R.string.edit_term_first_week_range,
                    dayWithWeekdayLabel(state.firstDayEpochDay),
                    dayWithWeekdayLabel(state.firstDayEpochDay + 6),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.edit_term_week_start_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(R.string.edit_term_total_weeks),
                    style = MaterialTheme.typography.labelLarge,
                )
                NumberStepper(
                    label = "",
                    value = state.totalWeeks,
                    range = 1..MAX_TOTAL_WEEKS,
                    onChange = viewModel::setTotalWeeks,
                )
                Text(
                    stringResource(R.string.edit_term_total_weeks_unit),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // 从上学期复制
            if (state.isNew && state.previousTermName != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column {
                        Text(
                            stringResource(R.string.edit_term_copy_previous),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            stringResource(
                                R.string.edit_term_copy_previous_source,
                                state.previousTermName.orEmpty(),
                            ),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = state.copyFromPrevious,
                        onCheckedChange = viewModel::setCopyFromPrevious,
                    )
                }
            }

            // 快速设定：只按「单节课时长 + 大节内课间」重排，各大节的开课时刻原地不动
            Text(
                stringResource(R.string.edit_term_quick_section),
                style = MaterialTheme.typography.labelLarge,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedTextField(
                    value = state.quickLessonText,
                    onValueChange = viewModel::setQuickLessonText,
                    label = { Text(stringResource(R.string.edit_term_quick_lesson)) },
                    suffix = { Text(stringResource(R.string.edit_term_quick_minutes)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = state.quickBreakText,
                    onValueChange = viewModel::setQuickBreakText,
                    label = { Text(stringResource(R.string.edit_term_quick_break)) },
                    suffix = { Text(stringResource(R.string.edit_term_quick_minutes)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                stringResource(R.string.edit_term_quick_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = viewModel::applyQuickTimes, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.edit_term_quick_apply))
            }
            state.quickNotice?.let { notice ->
                Text(
                    notice.resolve(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            // 节次时间表
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(
                        R.string.edit_term_periods,
                        pluralStringResource(R.plurals.edit_term_period_count, state.periods.size, state.periods.size),
                        (state.periods.size / 2).let { pluralStringResource(R.plurals.edit_term_section_count, it, it) },
                    ),
                    style = MaterialTheme.typography.labelLarge,
                )
                IconButton(onClick = viewModel::resetDefaultPeriods) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = stringResource(R.string.edit_term_reset_periods),
                    )
                }
            }

            state.periods.forEachIndexed { index, period ->
                PeriodRow(
                    period = period,
                    onUpdate = { viewModel.updatePeriod(index, it) },
                    onRemove = { viewModel.removePeriod(index) },
                )
            }

            OutlinedButton(onClick = viewModel::addPeriod, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.edit_term_add_period))
            }

            Button(
                onClick = { viewModel.save(onBack) },
                enabled = !state.saving && !state.clearing,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
            ) {
                Text(
                    stringResource(
                        if (state.saving) CoreR.string.common_saving else CoreR.string.common_save,
                    ),
                )
            }

            if (!state.isNew) {
                Text(
                    stringResource(R.string.edit_term_clear_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = { showClearConfirm = true },
                    enabled = !state.saving && !state.clearing && state.courseCount > 0,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text(
                        stringResource(
                            when {
                                state.clearing -> R.string.edit_term_clearing
                                state.courseCount == 0 -> R.string.edit_term_clear_empty
                                else -> R.string.edit_term_clear
                            },
                        ),
                    )
                }
            }

            androidx.compose.foundation.layout.Spacer(Modifier.padding(bottom = 24.dp))
        }
    }

    if (showDatePicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = state.firstDayEpochDay * 86_400_000L,
            // 手机横屏可用高度约 400dp，日历式选择器约 568dp 放不下，确定按钮会被裁掉。
            // 矮屏直接开输入模式（手打日期）——比给 568dp 的日历套滚动条好用得多。
            initialDisplayMode = if (LocalWindowSize.current.isCompactHeight) {
                DisplayMode.Input
            } else {
                DisplayMode.Picker
            },
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        viewModel.setFirstDay(millis / 86_400_000L)
                    }
                    showDatePicker = false
                }) { Text(stringResource(CoreR.string.common_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text(stringResource(CoreR.string.common_cancel))
                }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text(stringResource(R.string.edit_term_clear)) },
            text = {
                Text(pluralStringResource(R.plurals.edit_term_clear_confirm, state.courseCount, state.courseCount))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearConfirm = false
                        viewModel.clearCourses()
                    },
                ) {
                    Text(
                        stringResource(R.string.edit_term_clear_action),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text(stringResource(CoreR.string.common_cancel))
                }
            },
        )
    }

    state.error?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::dismissError,
            title = { Text(stringResource(R.string.edit_term_error_title)) },
            text = { Text(message.resolve()) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissError) {
                    Text(stringResource(CoreR.string.common_got_it))
                }
            },
        )
    }
}

@Composable
private fun WeekStartDaySelector(selected: Int, onSelect: (Int) -> Unit) {
    // 只给周一 / 周日：学校的教学周几乎只有这两种排法，中间那五天真要改，直接改第 1 周的日期更直接
    val options = listOf(
        1 to stringResource(R.string.edit_term_week_start_monday),
        7 to stringResource(R.string.edit_term_week_start_sunday),
    )
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (dayOfWeek, label) ->
            SegmentedButton(
                selected = selected == dayOfWeek,
                onClick = { onSelect(dayOfWeek) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
            ) {
                Text(label, fontSize = 13.sp)
            }
        }
    }
}

/** 「9月7日（周一）」。 */
@Composable
private fun dayWithWeekdayLabel(epochDay: Long): String = stringResource(
    R.string.edit_term_day_with_weekday,
    monthDayLabel(epochDay),
    dayOfWeekLabel(LocalDate.ofEpochDay(epochDay).dayOfWeek.value),
)

@Composable
private fun PeriodRow(
    period: EditablePeriod,
    onUpdate: (EditablePeriod) -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            stringResource(R.string.edit_term_period_index, period.periodIndex),
            fontSize = 13.sp,
            modifier = Modifier.width(48.dp),
        )
        OutlinedTextField(
            value = period.startText,
            onValueChange = { onUpdate(period.copy(startText = it.take(5))) },
            singleLine = true,
            modifier = Modifier.weight(1f),
            textStyle = MaterialTheme.typography.bodyMedium,
        )
        Text(
            stringResource(R.string.edit_block_range_separator),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = period.endText,
            onValueChange = { onUpdate(period.copy(endText = it.take(5))) },
            singleLine = true,
            modifier = Modifier.weight(1f),
            textStyle = MaterialTheme.typography.bodyMedium,
        )
        // 会话分组
        val sessions = listOf(
            Session.MORNING to stringResource(R.string.edit_term_session_morning),
            Session.AFTERNOON to stringResource(R.string.edit_term_session_afternoon),
            Session.EVENING to stringResource(R.string.edit_term_session_evening),
        )
        sessions.firstOrNull { it.first == period.session }?.let { pair ->
            FilterChip(
                selected = true,
                onClick = {
                    val next = sessions[(sessions.indexOf(pair) + 1) % sessions.size]
                    onUpdate(period.copy(session = next.first))
                },
                label = { Text(pair.second, fontSize = 11.sp) },
            )
        }
        IconButton(onClick = onRemove, modifier = Modifier.width(32.dp)) {
            Icon(
                Icons.Default.Delete,
                contentDescription = stringResource(R.string.edit_term_remove_period),
                modifier = Modifier.width(18.dp),
            )
        }
    }
}
