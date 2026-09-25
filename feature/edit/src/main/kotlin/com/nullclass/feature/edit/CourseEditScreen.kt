package com.nullclass.feature.edit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.nullclass.core.ui.i18n.resolve
import com.nullclass.core.ui.layout.AdaptiveColumn
import com.nullclass.core.ui.R as CoreR

/** 课程编辑（新建/编辑复用）。新建默认周一 1-2 节、整学期每周。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseEditScreen(
    courseId: String?,
    onBack: () -> Unit,
    viewModel: CourseEditViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    val appBarScrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(appBarScrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (state.isNew) R.string.edit_course_title_new else R.string.edit_course_title_edit,
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
                        enabled = !state.loading && !state.termMissing && !state.saving,
                    ) { Text(saveButtonLabel(state.saving)) }
                },
                scrollBehavior = appBarScrollBehavior,
            )
        },
        // safeDrawing 而非默认的 systemBars：把 ime 一并算进 content padding，
        // 键盘弹出时滚动视口自然收缩，底部输入框滚得出来。全项目此前零处 IME
        // 处理，而 Activity 开了 enableEdgeToEdge、主题又没声明 windowSoftInputMode，
        // 系统不会再替我们收缩窗口。顺带覆盖横屏侧边刘海。
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
            ) {
                Text(
                    stringResource(R.string.edit_course_no_term),
                    style = MaterialTheme.typography.titleMedium,
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
                OutlinedTextField(
                    value = state.name,
                    onValueChange = viewModel::setName,
                    label = { Text(stringResource(R.string.edit_course_name)) },
                    singleLine = true,
                    isError = state.error != null && state.name.isBlank(),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = state.teacher,
                    onValueChange = viewModel::setTeacher,
                    label = { Text(stringResource(R.string.edit_course_teacher)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = state.note,
                    onValueChange = viewModel::setNote,
                    label = { Text(stringResource(R.string.edit_course_note)) },
                    modifier = Modifier.fillMaxWidth(),
                )

                Text(stringResource(R.string.edit_course_color), style = MaterialTheme.typography.labelLarge)
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
                    Text(stringResource(R.string.edit_course_add_block))
                }

                Button(
                    onClick = { viewModel.save(onBack) },
                    enabled = !state.saving,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                ) { Text(saveButtonLabel(state.saving)) }

                androidx.compose.foundation.layout.Spacer(Modifier.padding(bottom = 24.dp))
            }
        }
    }

    state.error?.let { message ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissError() },
            title = { Text(stringResource(R.string.edit_course_error_title)) },
            text = { Text(message.resolve()) },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissError() }) {
                    Text(stringResource(CoreR.string.common_got_it))
                }
            },
        )
    }
}

@Composable
private fun saveButtonLabel(saving: Boolean): String =
    stringResource(if (saving) CoreR.string.common_saving else CoreR.string.common_save)
