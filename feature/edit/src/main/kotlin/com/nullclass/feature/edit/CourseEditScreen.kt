package com.nullclass.feature.edit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

/** 课程编辑（新建/编辑复用）。新建默认周一 1-2 节、整学期每周。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseEditScreen(
    courseId: String?,
    onBack: () -> Unit,
    viewModel: CourseEditViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.isNew) "添加课程" else "编辑课程") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(
                        onClick = { viewModel.save(onBack) },
                        enabled = !state.loading && !state.termMissing,
                    ) { Text("保存") }
                },
            )
        },
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
            ) {
                Text("请先创建学期", style = MaterialTheme.typography.titleMedium)
            }

            else -> Column(
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
                    label = { Text("课程名 *") },
                    singleLine = true,
                    isError = state.error != null && state.name.isBlank(),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = state.teacher,
                    onValueChange = viewModel::setTeacher,
                    label = { Text("教师（可选）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = state.note,
                    onValueChange = viewModel::setNote,
                    label = { Text("备注（可选）") },
                    modifier = Modifier.fillMaxWidth(),
                )

                Text("颜色", style = MaterialTheme.typography.labelLarge)
                ColorPalettePicker(selectedIndex = state.colorIndex, onSelect = viewModel::setColor)

                state.blocks.forEachIndexed { index, block ->
                    BlockEditor(
                        block = block,
                        totalWeeks = state.totalWeeks,
                        totalPeriods = state.totalPeriods,
                        onUpdate = { viewModel.updateBlock(index, it) },
                        onRemove = { viewModel.removeBlock(index) },
                    )
                }

                OutlinedButton(onClick = viewModel::addBlock, modifier = Modifier.fillMaxWidth()) {
                    Text("添加时间安排")
                }

                Button(
                    onClick = { viewModel.save(onBack) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                ) { Text("保存") }

                androidx.compose.foundation.layout.Spacer(Modifier.padding(bottom = 24.dp))
            }
        }
    }

    state.error?.let { message ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissError() },
            title = { Text("无法保存") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissError() }) { Text("知道了") }
            },
        )
    }
}
