package com.nullclass.feature.edit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.nullclass.core.model.Session
import java.time.LocalDate

/** 学期编辑：基本信息 + 节次时间表 + （新建时）从上学期复制课程。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TermEditScreen(
    termId: String?,
    onBack: () -> Unit,
    viewModel: TermEditViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    var showDatePicker by remember { mutableStateOf(false) }

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
                        enabled = !state.loading && !state.saving,
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

            // 开学日期（第一周周一）
            val firstDay = LocalDate.ofEpochDay(state.firstDayEpochDay)
            OutlinedButton(onClick = { showDatePicker = true }, modifier = Modifier.fillMaxWidth()) {
                Text("开学第一周周一：${firstDay.year}年${firstDay.monthValue}月${firstDay.dayOfMonth}日")
            }

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
                enabled = !state.saving,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
            ) { Text(if (state.saving) "保存中…" else "保存") }

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
