package com.nullclass.feature.schedule

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DisplayMode
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nullclass.core.model.Term
import com.nullclass.core.ui.i18n.dayOfWeekLabel
import com.nullclass.core.ui.i18n.monthDayLabel
import com.nullclass.core.ui.layout.LocalWindowSize
import com.nullclass.core.ui.R as CoreR
import java.time.LocalDate

/** DatePicker 的毫秒口径是 UTC 零点，与 epochDay 的换算只在这一处出现。 */
private const val MILLIS_PER_DAY = 86_400_000L

/**
 * 调课（串课）面板：把 [epochDay] 这天设成上另一天的课。
 *
 * 调休的常见说法是「这周六上周五的课」，所以主入口是同一周里的 7 个日子；
 * 跨周（补上上周的课、提前上下周的课）走「其它日期」。
 *
 * @param sourceEpochDay 当前已设的来源日；没调课为 null
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DaySwapDialog(
    term: Term,
    epochDay: Long,
    sourceEpochDay: Long?,
    onSelect: (sourceEpochDay: Long) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val date = LocalDate.ofEpochDay(epochDay)
    val week = term.weekOf(epochDay)
    var showDatePicker by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    R.string.schedule_day_swap_title,
                    monthDayLabel(epochDay),
                    dayOfWeekLabel(date.dayOfWeek.value),
                ),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = if (sourceEpochDay == null) {
                        stringResource(R.string.schedule_day_swap_desc_none)
                    } else {
                        val source = LocalDate.ofEpochDay(sourceEpochDay)
                        stringResource(
                            R.string.schedule_day_swap_desc_current,
                            monthDayLabel(sourceEpochDay),
                            dayOfWeekLabel(source.dayOfWeek.value),
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (week != null) {
                    Text(
                        stringResource(R.string.schedule_day_swap_pick_in_week),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        for (day in term.daysInWeekOrder) {
                            val candidate = term.epochDayOf(week, day)
                            val isSelf = candidate == epochDay
                            FilterChip(
                                // 选中自己 = 不调课，与「恢复原课表」同义（仓库层同一口径）
                                selected = if (isSelf) sourceEpochDay == null else candidate == sourceEpochDay,
                                onClick = { onSelect(candidate) },
                                label = {
                                    val label = dayOfWeekLabel(day)
                                    Text(
                                        if (isSelf) {
                                            stringResource(R.string.schedule_day_swap_self, label)
                                        } else {
                                            label
                                        },
                                    )
                                },
                            )
                        }
                    }
                }
                TextButton(onClick = { showDatePicker = true }) {
                    Text(stringResource(R.string.schedule_day_swap_other_date))
                }
            }
        },
        confirmButton = {
            if (sourceEpochDay != null) {
                TextButton(onClick = onClear) { Text(stringResource(R.string.schedule_day_swap_reset)) }
            } else {
                TextButton(onClick = onDismiss) { Text(stringResource(CoreR.string.common_done)) }
            }
        },
        dismissButton = {
            if (sourceEpochDay != null) {
                TextButton(onClick = onDismiss) { Text(stringResource(CoreR.string.common_done)) }
            }
        },
    )

    if (showDatePicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = (sourceEpochDay ?: epochDay) * MILLIS_PER_DAY,
            // 学期外的日子没有课表可借，选了也是空白一天——直接不让选
            selectableDates = remember(term) { termSelectableDates(term) },
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
                        // 向下取整而不是整除：1970 年前的日期会被整除截断成后一天
                        onSelect(Math.floorDiv(millis, MILLIS_PER_DAY))
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
}

/** 日期选择器限制在学期覆盖的日子内（串课的两端都必须在学期里才有意义）。 */
@OptIn(ExperimentalMaterial3Api::class)
private fun termSelectableDates(term: Term): SelectableDates = object : SelectableDates {
    override fun isSelectableDate(utcTimeMillis: Long): Boolean =
        term.weekOf(Math.floorDiv(utcTimeMillis, MILLIS_PER_DAY)) != null

    override fun isSelectableYear(year: Int): Boolean =
        year in LocalDate.ofEpochDay(term.firstDayEpochDay).year..
            LocalDate.ofEpochDay(term.firstDayEpochDay + term.totalWeeks * 7L - 1).year
}
