package com.nullclass.feature.schedule

import androidx.compose.ui.res.pluralStringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nullclass.core.model.CourseWithBlocks
import com.nullclass.core.model.ExamFormat
import com.nullclass.core.model.ExamWithCourse
import com.nullclass.core.model.PlacedBlock
import com.nullclass.core.ui.i18n.blockSummaryLabel
import com.nullclass.core.ui.i18n.dateLabel
import com.nullclass.core.ui.theme.courseColor
import com.nullclass.core.ui.R as CoreR
import kotlinx.coroutines.launch

/**
 * 课程详情弹层：信息 + 全部时间安排 + 编辑/删除。
 *
 * 各操作回调在弹层收起动画播完、[onDismiss] 之后才调用，调用方无需自行关闭弹层。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CourseDetailSheet(
    placed: PlacedBlock,
    courseWithBlocks: CourseWithBlocks?,
    onEdit: () -> Unit,
    onAddExam: (courseId: String) -> Unit,
    onEditExam: (examId: String) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
    viewModel: CourseDetailViewModel = androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel(),
) {
    var confirmDelete = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val exams by viewModel.exams.collectAsState()

    LaunchedEffect(placed.course.id) {
        viewModel.selectCourse(placed.course.id)
    }

    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    var closing by remember { mutableStateOf(false) }

    // 先播收起动画再执行操作。直接导航的话弹层窗口会随页面转场结束被连带销毁：
    // 先卡住再突然消失，之后再打开弹层也不播入场动画。closing 防连点重复导航。
    fun closeThen(action: () -> Unit) {
        if (closing) return
        closing = true
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            onDismiss()
            action()
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // 内容含两段长度不定的列表（时间安排 / 考试），课程安排多或带考试时
                // 底部「编辑 / 删除」会被推出可视区。手机横屏可用高度只有约 400dp，
                // 不给滚动就等于按钮点不到，用户只能取消退出。
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
        ) {
            if (courseWithBlocks == null) return@Column

            val course = courseWithBlocks.course
            val color = courseColor(course.colorIndex)

            Text(course.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Row(
                modifier = Modifier.padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                course.teacher?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    pluralStringResource(R.plurals.schedule_course_block_count, courseWithBlocks.blocks.size, courseWithBlocks.blocks.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            course.note?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            courseWithBlocks.blocks.forEach { block ->
                val isThis = block.id == placed.block.id
                Text(
                    text = blockSummaryLabel(block),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isThis) color.content else MaterialTheme.colorScheme.onSurface,
                    fontWeight = if (isThis) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    stringResource(R.string.schedule_course_exams),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                IconButton(onClick = { closeThen { onAddExam(course.id) } }) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = stringResource(R.string.schedule_course_add_exam),
                    )
                }
            }
            if (exams.isEmpty()) {
                Text(
                    stringResource(R.string.schedule_course_no_exam),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            } else {
                exams.forEach { item ->
                    ExamSummaryRow(item = item, onClick = { closeThen { onEditExam(item.exam.id) } })
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FilledTonalButton(onClick = { closeThen(onEdit) }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(CoreR.string.common_edit))
                }
                TextButton(
                    onClick = { confirmDelete.value = true },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(CoreR.string.common_delete), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    if (confirmDelete.value) {
        AlertDialog(
            onDismissRequest = { confirmDelete.value = false },
            title = { Text(stringResource(R.string.schedule_course_delete)) },
            text = {
                Text(
                    stringResource(
                        R.string.schedule_course_delete_confirm,
                        courseWithBlocks?.course?.name.orEmpty(),
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete.value = false
                    closeThen(onDelete)
                }) {
                    Text(stringResource(CoreR.string.common_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete.value = false }) {
                    Text(stringResource(CoreR.string.common_cancel))
                }
            },
        )
    }
}

@Composable
private fun ExamSummaryRow(item: ExamWithCourse, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(item.exam.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    dateLabel(item.exam.dateEpochDay),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            buildList {
                ExamFormat.timeRange(item.exam)?.let { add(it) }
                item.exam.location?.takeIf { it.isNotBlank() }?.let { add(it) }
                item.exam.seat?.takeIf { it.isNotBlank() }
                    ?.let { add(stringResource(R.string.schedule_course_seat, it)) }
            }.takeIf { it.isNotEmpty() }?.let { details ->
                Text(
                    details.joinToString(stringResource(CoreR.string.common_separator)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
    }
}
