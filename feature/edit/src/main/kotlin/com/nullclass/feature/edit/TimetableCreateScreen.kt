package com.nullclass.feature.edit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DisplayMode
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.nullclass.core.model.MAX_TOTAL_WEEKS
import com.nullclass.core.ui.i18n.dateLabel
import com.nullclass.core.ui.i18n.dayOfWeekLabel
import com.nullclass.core.ui.i18n.monthDayLabel
import com.nullclass.core.ui.i18n.resolve
import com.nullclass.core.ui.layout.AdaptiveColumn
import com.nullclass.core.ui.layout.LocalWindowSize
import com.nullclass.core.ui.R as CoreR
import java.time.LocalDate

/**
 * 创建课表：首次启动引导（[standalone] = true，全屏无返回）与「课表管理 → 新建」共用。
 * 建完顺手建第一个学期，开学即有可用课表；节次时间用默认模板，之后在学期管理里改。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimetableCreateScreen(
    onDone: () -> Unit,
    /** 首次启动引导：没有可返回的地方，不画顶栏返回键，改用大标题。 */
    standalone: Boolean = false,
    onBack: () -> Unit = onDone,
    viewModel: TimetableCreateViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    var showDatePicker by remember { mutableStateOf(false) }

    val appBarScrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(appBarScrollBehavior.nestedScrollConnection),
        topBar = {
            if (!standalone) {
                TopAppBar(
                    title = { Text(stringResource(R.string.edit_timetable_create_title)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(CoreR.string.common_back),
                            )
                        }
                    },
                    scrollBehavior = appBarScrollBehavior,
                )
            }
        },
        // safeDrawing：把 ime 算进 content padding，键盘弹出时滚动视口收缩；
        // 首启引导页尤其重要——它是新用户第一个要填的表单。
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        AdaptiveColumn(
            modifier = Modifier.padding(padding),
            scrollState = rememberScrollState(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            imePadding = false,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (standalone) {
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.edit_timetable_welcome),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    stringResource(R.string.edit_timetable_welcome_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            OutlinedTextField(
                value = state.timetableName,
                onValueChange = viewModel::setTimetableName,
                label = { Text(stringResource(R.string.edit_timetable_create_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Text(
                stringResource(R.string.edit_timetable_create_first_term),
                style = MaterialTheme.typography.labelLarge,
            )

            OutlinedTextField(
                value = state.termName,
                onValueChange = viewModel::setTermName,
                label = { Text(stringResource(R.string.edit_timetable_create_term_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            val firstDay = LocalDate.ofEpochDay(state.firstDayEpochDay)
            OutlinedButton(onClick = { showDatePicker = true }, modifier = Modifier.fillMaxWidth()) {
                Text(
                    if (state.firstDayEpochDay > 0L) {
                        stringResource(
                            R.string.edit_term_first_day,
                            dateLabel(state.firstDayEpochDay),
                        )
                    } else {
                        stringResource(R.string.edit_term_first_day_unset)
                    },
                )
            }
            if (state.firstDayEpochDay > 0L) {
                Text(
                    stringResource(
                        R.string.edit_timetable_create_first_week,
                        monthDayLabel(state.firstDayEpochDay),
                        dayOfWeekLabel(firstDay.dayOfWeek.value),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

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

            Button(
                onClick = { viewModel.save(onDone) },
                enabled = !state.saving && state.firstDayEpochDay > 0L,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
            ) {
                Text(
                    stringResource(
                        if (state.saving) {
                            R.string.edit_timetable_creating
                        } else {
                            R.string.edit_timetable_create_action
                        },
                    ),
                )
            }

            if (!standalone) {
                Text(
                    stringResource(R.string.edit_timetable_create_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showDatePicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = state.firstDayEpochDay.takeIf { it > 0L }?.times(86_400_000L),
            // 矮屏（手机横屏）放不下 568dp 的日历，直接开输入模式
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

    state.error?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::dismissError,
            title = { Text(stringResource(R.string.edit_timetable_create_error_title)) },
            text = { Text(message.resolve()) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissError) {
                    Text(stringResource(CoreR.string.common_got_it))
                }
            },
        )
    }
}
