package com.nullclass.feature.edit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
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
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.nullclass.core.model.ScheduleFormat
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

    Scaffold(
        topBar = {
            if (!standalone) {
                TopAppBar(
                    title = { Text("新建课表") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    },
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (standalone) {
                Spacer(Modifier.height(16.dp))
                Text(
                    "欢迎使用空课",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "先创建一张课表。不同课表的课程、节次时间各自独立，" +
                        "互不影响——给自己和家里人各建一张都行。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            OutlinedTextField(
                value = state.timetableName,
                onValueChange = viewModel::setTimetableName,
                label = { Text("课表名称 *") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Text("第一个学期", style = MaterialTheme.typography.labelLarge)

            OutlinedTextField(
                value = state.termName,
                onValueChange = viewModel::setTermName,
                label = { Text("学期名 *") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            val firstDay = LocalDate.ofEpochDay(state.firstDayEpochDay)
            OutlinedButton(onClick = { showDatePicker = true }, modifier = Modifier.fillMaxWidth()) {
                Text(
                    if (state.firstDayEpochDay > 0L) {
                        "第 1 周第 1 天：${firstDay.year}年${firstDay.monthValue}月${firstDay.dayOfMonth}日"
                    } else {
                        "第 1 周第 1 天"
                    },
                )
            }
            if (state.firstDayEpochDay > 0L) {
                Text(
                    "第 1 周：${firstDay.monthValue}月${firstDay.dayOfMonth}日" +
                        "（${ScheduleFormat.dayOfWeekLabel(firstDay.dayOfWeek.value)}）起，共 7 天。" +
                        "节次时间先用默认模板，之后可以在学期管理里改。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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

            Button(
                onClick = { viewModel.save(onDone) },
                enabled = !state.saving && state.firstDayEpochDay > 0L,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
            ) { Text(if (state.saving) "创建中…" else "创建课表") }

            if (!standalone) {
                Text(
                    "创建后自动切到这张课表。之后可在「我的 → 课表管理」切换。",
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
            title = { Text("无法创建") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissError) { Text("知道了") }
            },
        )
    }
}
