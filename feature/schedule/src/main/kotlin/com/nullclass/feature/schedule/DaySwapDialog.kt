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
import androidx.compose.ui.unit.dp
import com.nullclass.core.model.ScheduleFormat
import com.nullclass.core.model.Term
import com.nullclass.core.ui.layout.LocalWindowSize
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
            Text("调课 · ${date.monthValue}月${date.dayOfMonth}日 ${ScheduleFormat.dayOfWeekLabel(date.dayOfWeek.value)}")
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = if (sourceEpochDay == null) {
                        "这天按课表原样上课。调休时可以把它设成上另一天的课——今日页、" +
                            "周视图、小组件和课前提醒会一起跟着改，上课时间仍按这天的作息。"
                    } else {
                        val source = LocalDate.ofEpochDay(sourceEpochDay)
                        "当前：这天上 ${source.monthValue}月${source.dayOfMonth}日" +
                            "（${ScheduleFormat.dayOfWeekLabel(source.dayOfWeek.value)}）的课。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (week != null) {
                    Text("上本周哪天的课", style = MaterialTheme.typography.labelMedium)
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
                                    Text(
                                        if (isSelf) {
                                            "周${ScheduleFormat.dayOfWeekShortLabel(day)}(本身)"
                                        } else {
                                            "周${ScheduleFormat.dayOfWeekShortLabel(day)}"
                                        },
                                    )
                                },
                            )
                        }
                    }
                }
                TextButton(onClick = { showDatePicker = true }) { Text("上其它日期的课…") }
            }
        },
        confirmButton = {
            if (sourceEpochDay != null) {
                TextButton(onClick = onClear) { Text("恢复原课表") }
            } else {
                TextButton(onClick = onDismiss) { Text("完成") }
            }
        },
        dismissButton = {
            if (sourceEpochDay != null) {
                TextButton(onClick = onDismiss) { Text("完成") }
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
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("取消") }
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
