package com.nullclass.feature.schedule

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nullclass.core.model.CourseWithBlocks
import com.nullclass.core.model.ExamFormat
import com.nullclass.core.model.ExamWithCourse
import com.nullclass.core.model.PlacedBlock
import com.nullclass.core.model.ScheduleFormat
import com.nullclass.core.ui.theme.courseColor

/** 课程详情弹层：信息 + 全部时间安排 + 编辑/删除。 */
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

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
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
                    "共 ${courseWithBlocks.blocks.size} 个安排",
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
                    text = ScheduleFormat.blockSummary(block),
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
                Text("考试安排", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                IconButton(onClick = { onAddExam(course.id) }) {
                    Icon(Icons.Default.Add, contentDescription = "添加考试")
                }
            }
            if (exams.isEmpty()) {
                Text(
                    "尚未设置考试",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            } else {
                exams.forEach { item ->
                    ExamSummaryRow(item = item, onClick = { onEditExam(item.exam.id) })
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FilledTonalButton(onClick = onEdit, modifier = Modifier.weight(1f)) {
                    Text("编辑")
                }
                TextButton(
                    onClick = { confirmDelete.value = true },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    if (confirmDelete.value) {
        AlertDialog(
            onDismissRequest = { confirmDelete.value = false },
            title = { Text("删除课程") },
            text = { Text("确定删除「${courseWithBlocks?.course?.name}」吗？该操作会同步到其他设备。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete.value = false
                    onDelete()
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete.value = false }) { Text("取消") }
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
                    ExamFormat.dateLabel(item.exam.dateEpochDay),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            buildList {
                ExamFormat.timeRange(item.exam)?.let { add(it) }
                item.exam.location?.takeIf { it.isNotBlank() }?.let { add(it) }
                item.exam.seat?.takeIf { it.isNotBlank() }?.let { add("座位 $it") }
            }.takeIf { it.isNotEmpty() }?.let { details ->
                Text(
                    details.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
    }
}
