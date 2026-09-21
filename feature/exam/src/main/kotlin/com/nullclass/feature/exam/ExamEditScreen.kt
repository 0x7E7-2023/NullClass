package com.nullclass.feature.exam

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.nullclass.core.model.ExamFormat
import com.nullclass.core.ui.layout.AdaptiveColumn
import com.nullclass.core.ui.layout.LocalWindowSize
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** 新增/编辑一场考试；课程必须从当前学期已有课程中选择。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExamEditScreen(
    onBack: () -> Unit,
    viewModel: ExamEditViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    var showCoursePicker by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }

    val appBarScrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(appBarScrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(if (state.isNew) "添加考试" else "编辑考试") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(
                        onClick = { viewModel.save(onBack) },
                        enabled = !state.loading && !state.termMissing && !state.noCourses && !state.saving,
                    ) { Text(if (state.saving) "保存中…" else "保存") }
                },
                scrollBehavior = appBarScrollBehavior,
            )
        },
        // safeDrawing：把 ime 算进 content padding，键盘弹出时滚动视口收缩，
        // 底部输入框滚得出来；顺带覆盖横屏侧边刘海。
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        when {
            state.loading -> Unit

            state.termMissing -> Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) { Text("请先创建学期", style = MaterialTheme.typography.titleMedium) }

            state.noCourses -> Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("还没有课程", style = MaterialTheme.typography.titleMedium)
                Text(
                    "考试需要挂在具体课程上，请先添加课程。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            else -> AdaptiveColumn(
                modifier = Modifier.padding(padding),
                scrollState = rememberScrollState(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                // ime 已由 Scaffold 的 safeDrawing 算进 padding，这里不再重复垫
                imePadding = false,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                val selectedCourse = state.courses.firstOrNull { it.id == state.courseId }
                OutlinedButton(
                    onClick = { showCoursePicker = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("所属课程：${selectedCourse?.name ?: "请选择"}")
                }
                Text(
                    "考试会保存为这门课程的关联信息",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                OutlinedTextField(
                    value = state.title,
                    onValueChange = viewModel::setTitle,
                    label = { Text("考试名称 *") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = state.dateText,
                    onValueChange = viewModel::setDateText,
                    label = { Text("日期 *") },
                    supportingText = { Text("格式：yyyy-MM-dd") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(
                    onClick = { showDatePicker = true },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("选择日期${state.dateText.toDateLabelSuffix()}") }

                Text("时间（可选）", style = MaterialTheme.typography.labelLarge)
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = state.startText,
                        onValueChange = viewModel::setStartText,
                        label = { Text("开始时间") },
                        placeholder = { Text("例如 09:00") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = state.endText,
                        onValueChange = viewModel::setEndText,
                        label = { Text("结束时间") },
                        placeholder = { Text("例如 11:00") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                OutlinedTextField(
                    value = state.location,
                    onValueChange = viewModel::setLocation,
                    label = { Text("考场（可选）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = state.seat,
                    onValueChange = viewModel::setSeat,
                    label = { Text("座位号（可选）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = state.note,
                    onValueChange = viewModel::setNote,
                    label = { Text("备注（可选）") },
                    modifier = Modifier.fillMaxWidth(),
                )

                Button(
                    onClick = { viewModel.save(onBack) },
                    enabled = !state.saving,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                ) { Text(if (state.saving) "保存中…" else "保存") }
            }
        }
    }

    if (showCoursePicker) {
        AlertDialog(
            onDismissRequest = { showCoursePicker = false },
            title = { Text("选择所属课程") },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    state.courses.forEach { course ->
                        TextButton(
                            onClick = {
                                viewModel.setCourseId(course.id)
                                showCoursePicker = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(course.name) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showCoursePicker = false }) { Text("取消") } },
        )
    }

    if (showDatePicker) {
        val initialDate = runCatching { LocalDate.parse(state.dateText) }.getOrDefault(LocalDate.now())
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = initialDate
                .atStartOfDay()
                .toInstant(ZoneOffset.UTC)
                .toEpochMilli(),
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
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { millis ->
                            viewModel.setDateText(
                                Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toString(),
                            )
                        }
                        showDatePicker = false
                    },
                ) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("取消") } },
        ) { DatePicker(state = datePickerState) }
    }

    state.error?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::dismissError,
            title = { Text("无法保存") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = viewModel::dismissError) { Text("知道了") } },
        )
    }
}

private fun String.toDateLabelSuffix(): String =
    runCatching { " · ${ExamFormat.dateLabel(LocalDate.parse(this).toEpochDay())}" }.getOrDefault("")
