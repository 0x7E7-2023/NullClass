package com.nullclass.feature.exam

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.nullclass.core.model.ExamFormat
import com.nullclass.core.model.ExamWithCourse
import com.nullclass.core.ui.i18n.dateLabel
import com.nullclass.core.ui.i18n.examRelativeLabel
import com.nullclass.core.ui.i18n.resolve
import com.nullclass.core.ui.layout.AdaptiveWidthWrapper
import com.nullclass.core.ui.theme.courseColor
import com.nullclass.core.ui.R as CoreR

/** 当前学期考试汇总页；考试数据仍通过 courseId 归属于课程。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExamScreen(
    onAddExam: () -> Unit,
    onEditExam: (examId: String) -> Unit,
    onEditTerm: (termId: String?) -> Unit,
    viewModel: ExamViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val message by viewModel.message.collectAsState()
    var deleteTarget by remember { mutableStateOf<ExamWithCourse?>(null) }

    val appBarScrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(appBarScrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.exam_title)) },
                actions = {
                    if (state is ExamUiState.Ready) {
                        IconButton(onClick = onAddExam) {
                            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.exam_add))
                        }
                    }
                },
                scrollBehavior = appBarScrollBehavior,
            )
        },
        // 垂直方向归零（顶栏自吃状态栏，底栏在外层 NavHost）；水平方向垫
        // safeDrawing，避开横屏时转到侧边的刘海/挖孔——主题声明了
        // windowLayoutInDisplayCutoutMode = shortEdges，不补偿会盖住列表内容。
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        when (val s = state) {
            ExamUiState.Loading -> Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            ExamUiState.NoTerm -> Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(stringResource(R.string.exam_no_term_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.exam_no_term_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = { onEditTerm(null) }) { Text(stringResource(R.string.exam_create_term)) }
                }
            }

            is ExamUiState.Ready -> {
                val upcoming = s.exams.filter { it.exam.dateEpochDay >= s.todayEpochDay }
                val past = s.exams.filter { it.exam.dateEpochDay < s.todayEpochDay }
                val upcomingGroups = upcoming.groupBy { it.exam.dateEpochDay }.toSortedMap()
                val pastGroups = past.groupBy { it.exam.dateEpochDay }.toSortedMap(compareByDescending { it })
                val next = upcoming.firstOrNull()

                AdaptiveWidthWrapper(modifier = Modifier.padding(padding)) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(s.term.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            Text(
                                if (s.exams.isEmpty()) {
                                    stringResource(R.string.exam_summary_empty)
                                } else {
                                    stringResource(R.string.exam_summary_count, s.exams.size)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    if (next != null) {
                        item { NextExamCard(next = next, todayEpochDay = s.todayEpochDay) }
                    } else if (s.exams.isNotEmpty()) {
                        item {
                            Text(
                                stringResource(R.string.exam_no_upcoming),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    if (upcomingGroups.isNotEmpty()) {
                        item { SectionTitle(stringResource(R.string.exam_section_upcoming)) }
                        items(
                            items = upcomingGroups.entries.toList(),
                            key = { "upcoming-${it.key}" },
                        ) { (date, exams) ->
                            ExamDateGroup(
                                dateEpochDay = date,
                                exams = exams,
                                todayEpochDay = s.todayEpochDay,
                                onEditExam = onEditExam,
                                onDeleteExam = { deleteTarget = it },
                            )
                        }
                    }

                    if (pastGroups.isNotEmpty()) {
                        item { SectionTitle(stringResource(R.string.exam_section_past)) }
                        items(
                            items = pastGroups.entries.toList(),
                            key = { "past-${it.key}" },
                        ) { (date, exams) ->
                            ExamDateGroup(
                                dateEpochDay = date,
                                exams = exams,
                                todayEpochDay = s.todayEpochDay,
                                onEditExam = onEditExam,
                                onDeleteExam = { deleteTarget = it },
                            )
                        }
                    }
                }
                }
            }
        }
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.exam_delete)) },
            text = {
                Text(stringResource(R.string.exam_delete_confirm, target.course.name, target.exam.title))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteExam(target.exam.id)
                        deleteTarget = null
                    },
                ) { Text(stringResource(CoreR.string.common_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text(stringResource(CoreR.string.common_cancel)) }
            },
        )
    }

    message?.let {
        AlertDialog(
            onDismissRequest = viewModel::dismissMessage,
            title = { Text(stringResource(CoreR.string.common_failed)) },
            text = { Text(it.resolve()) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissMessage) { Text(stringResource(CoreR.string.common_got_it)) }
            },
        )
    }
}

@Composable
private fun NextExamCard(next: ExamWithCourse, todayEpochDay: Long) {
    val color = courseColor(next.course.colorIndex)
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .width(6.dp)
                    .height(82.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(color.content),
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(stringResource(R.string.exam_next), style = MaterialTheme.typography.labelMedium)
                Text(next.course.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(next.exam.title, style = MaterialTheme.typography.bodyMedium)
                Text(
                    listOf(
                        dateLabel(next.exam.dateEpochDay),
                        ExamFormat.timeRange(next.exam) ?: stringResource(R.string.exam_time_undecided),
                        examRelativeLabel(next.exam.dateEpochDay, todayEpochDay),
                    ).joinToString(stringResource(CoreR.string.common_separator)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun ExamDateGroup(
    dateEpochDay: Long,
    exams: List<ExamWithCourse>,
    todayEpochDay: Long,
    onEditExam: (String) -> Unit,
    onDeleteExam: (ExamWithCourse) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(dateLabel(dateEpochDay), style = MaterialTheme.typography.labelLarge)
            Text(
                examRelativeLabel(dateEpochDay, todayEpochDay),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        exams.forEach { exam ->
            ExamCard(
                item = exam,
                onClick = { onEditExam(exam.exam.id) },
                onDelete = { onDeleteExam(exam) },
            )
        }
    }
}

@Composable
private fun ExamCard(
    item: ExamWithCourse,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val color = courseColor(item.course.colorIndex)
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .width(5.dp)
                    .height(72.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(color.content),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(item.course.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(item.exam.title, style = MaterialTheme.typography.bodyMedium)
                Text(
                    buildList {
                        ExamFormat.timeRange(item.exam)?.let { add(it) }
                        item.exam.location?.takeIf { it.isNotBlank() }?.let { add(it) }
                        item.exam.seat?.takeIf { it.isNotBlank() }
                            ?.let { add(stringResource(R.string.exam_seat, it)) }
                    }.ifEmpty { listOf(stringResource(R.string.exam_detail_empty)) }
                        .joinToString(stringResource(CoreR.string.common_separator)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = stringResource(R.string.exam_delete),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
