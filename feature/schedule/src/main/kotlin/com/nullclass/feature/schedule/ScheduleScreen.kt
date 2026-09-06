package com.nullclass.feature.schedule

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.nullclass.core.model.PlacedBlock
import com.nullclass.core.ui.theme.courseColor

/**
 * 课表主界面。
 *
 * @param onCreateCourse 点空白格/FAB 进新建课程；空白格带预填 (day, period)
 * @param onEditCourse 编辑已有课程
 * @param onEditTerm 学期设置
 * @param onOpenSettings 应用设置（WebDAV 同步等）
 * @param onOpenTransfer 导入/导出中心
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleScreen(
    onCreateCourse: (dayOfWeek: Int?, startPeriod: Int?) -> Unit,
    onEditCourse: (courseId: String) -> Unit,
    onEditTerm: (termId: String?) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTransfer: () -> Unit = {},
    viewModel: ScheduleViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    var detailBlock by remember { mutableStateOf<PlacedBlock?>(null) }
    var showWeekPicker by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        val title = when (val s = state) {
                            is ScheduleUiState.Ready -> s.term.name
                            else -> "空课"
                        }
                        Text(title, style = MaterialTheme.typography.titleMedium)
                        if (state is ScheduleUiState.Ready) {
                            val ready = state as ScheduleUiState.Ready
                            Text(
                                text = "第 ${ready.selectedWeek} 周" +
                                    if (ready.selectedWeek == ready.currentWeek) " · 本周" else "",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                actions = {
                    val ready = state as? ScheduleUiState.Ready
                    if (ready != null && ready.selectedWeek != ready.currentWeek) {
                        TextButton(onClick = { viewModel.backToCurrentWeek() }) {
                            Text("回本周")
                        }
                    }
                    var menuOpen by remember { mutableStateOf(false) }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "更多")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("导入 / 导出") },
                            onClick = {
                                menuOpen = false
                                onOpenTransfer()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("学期设置") },
                            onClick = {
                                menuOpen = false
                                onEditTerm((state as? ScheduleUiState.Ready)?.term?.id)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("设置") },
                            onClick = {
                                menuOpen = false
                                onOpenSettings()
                            },
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        floatingActionButton = {
            if (state is ScheduleUiState.Ready) {
                FloatingActionButton(onClick = { onCreateCourse(null, null) }) {
                    Icon(Icons.Default.Add, contentDescription = "添加课程")
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        when (val s = state) {
            ScheduleUiState.Loading -> Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            ScheduleUiState.NoTerm -> Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("还没有学期", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "创建一个学期开始排课",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = { onEditTerm(null) }) { Text("创建学期") }
                }
            }

            is ScheduleUiState.Ready -> {
                val ready = s
                val pagerState = rememberPagerState(
                    initialPage = (ready.selectedWeek - 1).coerceIn(0, ready.term.totalWeeks - 1),
                    pageCount = { ready.term.totalWeeks },
                )

                // 翻页 → VM
                LaunchedEffect(pagerState) {
                    snapshotFlow { pagerState.currentPage }.collect { page ->
                        viewModel.selectWeek(page + 1)
                    }
                }
                // 周次选择器/回本周 → 翻页
                LaunchedEffect(ready.selectedWeek, ready.term.totalWeeks) {
                    val target = (ready.selectedWeek - 1).coerceIn(0, ready.term.totalWeeks - 1)
                    if (pagerState.currentPage != target && !pagerState.isScrollInProgress) {
                        pagerState.animateScrollToPage(target)
                    }
                }

                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(padding),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        WeekHeader(
                            term = ready.term,
                            week = ready.selectedWeek,
                            todayDayOfWeek = if (ready.selectedWeek == ready.currentWeek) {
                                ready.todayDayOfWeek
                            } else {
                                null
                            },
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { showWeekPicker = true }) {
                            Text("选周", fontSize = 12.sp)
                        }
                    }

                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize(),
                    ) { page ->
                        val week = page + 1
                        val layout = if (week == ready.selectedWeek) {
                            ready.layout
                        } else {
                            com.nullclass.core.model.WeekLayout.layoutForWeek(ready.schedule, week)
                        }
                        Column(
                            Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState()),
                        ) {
                            WeekGrid(
                                periodTimes = ready.periodTimes,
                                layout = layout,
                                todayDayOfWeek = if (week == ready.currentWeek) ready.todayDayOfWeek else null,
                                onBlockClick = { detailBlock = it },
                                onCellClick = { day, period -> onCreateCourse(day, period) },
                            )
                        }
                    }
                }

                detailBlock?.let { placed ->
                    val courseWithBlocks = ready.schedule.firstOrNull { it.course.id == placed.course.id }
                    CourseDetailSheet(
                        placed = placed,
                        courseWithBlocks = courseWithBlocks,
                        onEdit = {
                            detailBlock = null
                            onEditCourse(placed.course.id)
                        },
                        onDelete = {
                            viewModel.deleteCourse(placed.course.id)
                            detailBlock = null
                        },
                        onDismiss = { detailBlock = null },
                    )
                }

                if (showWeekPicker) {
                    WeekPickerDialog(
                        totalWeeks = ready.term.totalWeeks,
                        currentWeek = ready.currentWeek,
                        selectedWeek = ready.selectedWeek,
                        onSelect = {
                            viewModel.selectWeek(it)
                            showWeekPicker = false
                        },
                        onDismiss = { showWeekPicker = false },
                    )
                }
            }
        }
    }
}

@Composable
private fun WeekPickerDialog(
    totalWeeks: Int,
    currentWeek: Int,
    selectedWeek: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择周次") },
        text = {
            LazyVerticalGrid(
                columns = GridCells.Fixed(5),
                modifier = Modifier.height(320.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items((1..totalWeeks).toList()) { week ->
                    FilterChip(
                        selected = week == selectedWeek,
                        onClick = { onSelect(week) },
                        label = {
                            Text(
                                if (week == currentWeek) "$week·今" else "$week",
                                fontSize = 12.sp,
                            )
                        },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
