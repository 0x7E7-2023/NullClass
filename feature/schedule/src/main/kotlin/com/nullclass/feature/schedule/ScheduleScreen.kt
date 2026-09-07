package com.nullclass.feature.schedule

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.nullclass.core.model.PlacedBlock
import com.nullclass.core.ui.theme.courseColor

/**
 * 课表主界面。
 *
 * @param onCreateCourse FAB 进新建课程
 * @param onEditCourse 编辑已有课程
 * @param onEditTerm 学期设置
 * @param onOpenSettings 应用设置（WebDAV 同步等）
 * @param onOpenTransfer 导入/导出中心
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleScreen(
    onCreateCourse: () -> Unit,
    onEditCourse: (courseId: String) -> Unit,
    onEditTerm: (termId: String?) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTransfer: () -> Unit = {},
    viewModel: ScheduleViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    var detailBlock by remember { mutableStateOf<PlacedBlock?>(null) }
    var showWeekPicker by remember { mutableStateOf(false) }
    var showQuickSettings by remember { mutableStateOf(false) }

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
                    if (ready != null) {
                        IconButton(onClick = { showWeekPicker = true }) {
                            Icon(Icons.Default.DateRange, contentDescription = "选择周次")
                        }
                    }
                    if (ready != null) {
                        IconButton(onClick = { showQuickSettings = true }) {
                            Icon(Icons.Default.Settings, contentDescription = "设置")
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
                FloatingActionButton(onClick = onCreateCourse) {
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

                // 翻页落定 → VM。用 settledPage 而非 currentPage：动画途中扫过的中间页
                // 不回写 VM，否则 selectedWeek 抖动会重启下方翻页效果、把动画拦腰取消
                LaunchedEffect(pagerState) {
                    snapshotFlow { pagerState.settledPage }.collect { page ->
                        viewModel.selectWeek(page + 1)
                    }
                }
                // 周次选择器/回本周 → 翻页
                LaunchedEffect(ready.selectedWeek, ready.term.totalWeeks) {
                    val target = (ready.selectedWeek - 1).coerceIn(0, ready.term.totalWeeks - 1)
                    if (pagerState.settledPage != target && !pagerState.isScrollInProgress) {
                        pagerState.animateScrollToPage(target)
                    }
                }

                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(padding),
                ) {
                    WeekHeader(
                        term = ready.term,
                        week = ready.selectedWeek,
                        todayDayOfWeek = if (ready.selectedWeek == ready.currentWeek) {
                            ready.todayDayOfWeek
                        } else {
                            null
                        },
                        showWeekend = ready.showWeekend,
                        showTimeInCards = ready.showTimeInCards,
                        // 无水平 padding：与 WeekGrid 总宽严格一致，分栏才能逐列对齐
                        modifier = Modifier.fillMaxWidth(),
                    )

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
                                showWeekend = ready.showWeekend,
                                showTimeInCards = ready.showTimeInCards,
                                onBlockClick = { detailBlock = it },
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

                if (showQuickSettings) {
                    AlertDialog(
                        onDismissRequest = { showQuickSettings = false },
                        title = { Text("设置") },
                        text = {
                            Column {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { viewModel.setShowWeekend(!ready.showWeekend) },
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text("显示周末")
                                    Checkbox(
                                        checked = ready.showWeekend,
                                        onCheckedChange = { viewModel.setShowWeekend(it) },
                                    )
                                }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { viewModel.setShowTimeInCards(!ready.showTimeInCards) },
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text("起止时间标在课块上")
                                    Switch(
                                        checked = ready.showTimeInCards,
                                        onCheckedChange = { viewModel.setShowTimeInCards(it) },
                                    )
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { showQuickSettings = false }) { Text("完成") }
                        },
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
                    WeekChip(
                        week = week,
                        selected = week == selectedWeek,
                        isCurrent = week == currentWeek,
                        onClick = { onSelect(week) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 周次格子：常态无边框无填充；选中周次描边；当今周次填充背景色（两者可叠加）。 */
@Composable
private fun WeekChip(
    week: Int,
    selected: Boolean,
    isCurrent: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.small,
        color = if (isCurrent) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "$week",
                fontSize = 12.sp,
                color = if (isCurrent) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        }
    }
}
