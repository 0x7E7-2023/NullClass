package com.nullclass.feature.edit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
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
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.nullclass.core.model.ScheduleFormat
import com.nullclass.core.model.Session
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.isNew) "创建学期" else "编辑学期") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(
                        onClick = { viewModel.save(onBack) },
                        enabled = !state.loading && !state.saving && !state.clearing,
                    ) { Text("保存") }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        if (state.loading) return@Scaffold

        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            OutlinedTextField(
                value = state.name,
                onValueChange = viewModel::setName,
                label = { Text("学期名 *") },
                placeholder = { Text("如 2026-2027-1") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            // 每周起始日：一周从哪天算起 = 第 1 周从哪天开始（同一个值的两种说法）
            val firstDay = LocalDate.ofEpochDay(state.firstDayEpochDay)
            Text("每周起始日", style = MaterialTheme.typography.labelLarge)
            WeekStartDaySelector(
                selected = firstDay.dayOfWeek.value,
                onSelect = viewModel::setWeekStartDay,
            )
            if (firstDay.dayOfWeek.value != 1 && firstDay.dayOfWeek.value != 7) {
                Text(
                    "当前从${ScheduleFormat.dayOfWeekLabel(firstDay.dayOfWeek.value)}算起，" +
                        "两个选项都不亮；保持原样就行，点任一个会把它挪到最近的周一 / 周日。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            OutlinedButton(onClick = { showDatePicker = true }, modifier = Modifier.fillMaxWidth()) {
                Text("第 1 周第 1 天：${firstDay.year}年${firstDay.monthValue}月${firstDay.dayOfMonth}日")
            }

            Text(
                "第 1 周：${dateLabel(state.firstDayEpochDay)} – ${dateLabel(state.firstDayEpochDay + 6)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "一周从哪天算起，第 1 周就从哪天开始，周视图的列顺序与「第几周」都跟着走。" +
                    "点上面那排会把日期挪到最近的对应星期几（周一 ↔ 周日 来回切也不会跑偏）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("总周数", style = MaterialTheme.typography.labelLarge)
                NumberStepper(
                    label = "",
                    value = state.totalWeeks,
                    range = 1..25,
                    onChange = viewModel::setTotalWeeks,
                )
                Text("周", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            // 从上学期复制
            if (state.isNew && state.previousTermName != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column {
                        Text("从上学期复制课程", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "来源：${state.previousTermName}",
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
            Text("快速设定", style = MaterialTheme.typography.labelLarge)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedTextField(
                    value = state.quickLessonText,
                    onValueChange = viewModel::setQuickLessonText,
                    label = { Text("单节课") },
                    suffix = { Text("分钟") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = state.quickBreakText,
                    onValueChange = viewModel::setQuickBreakText,
                    label = { Text("课间休息") },
                    suffix = { Text("分钟") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                "课间休息指一个大节里两节之间的休息（默认模板是 10 分钟）；大节与大节之间的休息" +
                    "（上午大课间那种，20 分钟）不归它管 —— 套用时只按每个大节现有的开课时刻重排内部，" +
                    "所以对着默认模板套 45 + 10 等于没改。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = viewModel::applyQuickTimes, modifier = Modifier.fillMaxWidth()) {
                Text("套用")
            }
            state.quickNotice?.let { notice ->
                Text(
                    notice,
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
                Text("节次时间（${state.periods.size} 节 = ${state.periods.size / 2} 大节）", style = MaterialTheme.typography.labelLarge)
                IconButton(onClick = viewModel::resetDefaultPeriods) {
                    Icon(Icons.Default.Refresh, contentDescription = "恢复默认模板")
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
                Text("添加节次")
            }

            Button(
                onClick = { viewModel.save(onBack) },
                enabled = !state.saving && !state.clearing,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
            ) { Text(if (state.saving) "保存中…" else "保存") }

            if (!state.isNew) {
                Text(
                    "清空本学期全部课程，学期和节次时间保留。删除会同步到其他设备。",
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
                        when {
                            state.clearing -> "清空中…"
                            state.courseCount == 0 -> "本学期没有课程"
                            else -> "清空本学期课程"
                        },
                    )
                }
            }

            androidx.compose.foundation.layout.Spacer(Modifier.padding(bottom = 24.dp))
        }
    }

    if (showDatePicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = state.firstDayEpochDay * 86_400_000L,
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        viewModel.setFirstDay(millis / 86_400_000L)
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

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("清空本学期课程") },
            text = {
                Text("将删除本学期的全部 ${state.courseCount} 门课，学期和节次时间保留。删除会同步到其他设备。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearConfirm = false
                        viewModel.clearCourses()
                    },
                ) { Text("清空", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("取消") }
            },
        )
    }

    state.error?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::dismissError,
            title = { Text("无法保存") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissError) { Text("知道了") }
            },
        )
    }
}

@Composable
private fun WeekStartDaySelector(selected: Int, onSelect: (Int) -> Unit) {
    // 只给周一 / 周日：学校的教学周几乎只有这两种排法，中间那五天真要改，直接改第 1 周的日期更直接
    val options = listOf(1 to "周一", 7 to "周日")
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

/** "9月7日（周一）"。 */
private fun dateLabel(epochDay: Long): String {
    val date = LocalDate.ofEpochDay(epochDay)
    return "${date.monthValue}月${date.dayOfMonth}日（${ScheduleFormat.dayOfWeekLabel(date.dayOfWeek.value)}）"
}

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
            "第${period.periodIndex}节",
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
        Text("—", color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            value = period.endText,
            onValueChange = { onUpdate(period.copy(endText = it.take(5))) },
            singleLine = true,
            modifier = Modifier.weight(1f),
            textStyle = MaterialTheme.typography.bodyMedium,
        )
        // 会话分组
        val sessions = listOf(Session.MORNING to "上午", Session.AFTERNOON to "下午", Session.EVENING to "晚上")
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
            Icon(Icons.Default.Delete, contentDescription = "删除节次", modifier = Modifier.width(18.dp))
        }
    }
}
