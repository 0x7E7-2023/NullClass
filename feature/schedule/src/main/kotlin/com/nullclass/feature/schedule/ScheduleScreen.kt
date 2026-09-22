package com.nullclass.feature.schedule

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.nullclass.core.model.PlacedBlock
import com.nullclass.core.model.WeekLayout
import com.nullclass.core.ui.theme.courseColor

/**
 * 课表主界面（中间 Tab）。
 *
 * @param onCreateCourse FAB 进新建课程
 * @param onEditCourse 编辑已有课程
 * @param onEditTerm 学期设置（无学期时引导创建）
 * @param onOpenEvents 右上角进日程安排页
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleScreen(
    onCreateCourse: () -> Unit,
    onEditCourse: (courseId: String) -> Unit,
    onAddExam: (courseId: String) -> Unit,
    onEditExam: (examId: String) -> Unit,
    onEditTerm: (termId: String?) -> Unit,
    onOpenEvents: () -> Unit,
    viewModel: ScheduleViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    var detailBlock by remember { mutableStateOf<PlacedBlock?>(null) }
    var showWeekPicker by remember { mutableStateOf(false) }
    var showQuickSettings by remember { mutableStateOf(false) }
    /** 点了表头哪一列（epoch day）→ 开调课面板。 */
    var swapDay by remember { mutableStateOf<Long?>(null) }

    // 网格铺不满一屏时把顶栏钉死。enterAlways 只看手势方向，内容滚不滚得动它不管；
    // 而这一屏的行高是按可用高度撑开的 —— 顶栏一收，网格马上长高把空出来的地方填满，
    // 反向一划又缩回去，整张表跟着手指上下伸缩像弹簧（平板竖屏排满课时最扎眼）。
    // 本来就滑不动的一屏，顶栏也没有收起来的理由。
    //
    // 换成 pinned，而不是给 enterAlways 传 canScroll：canScroll 只拦得住嵌套滚动这条路，
    // TopAppBar 自身还挂着一个 draggable（M3 1.4.0 里它只看 isPinned，不看 canScroll）——
    // 手指撑在顶栏上往上拖照样把它拖收，而 canScroll=false 又把「在网格上往下划把它划回来」
    // 堵死了，顶栏就永久卡在半截。pinned 的 isPinned 是 true，那个 draggable 根本不会装上。
    var gridScrollable by remember { mutableStateOf(false) }
    val appBarState = rememberTopAppBarState()
    val appBarScrollBehavior = if (gridScrollable) {
        TopAppBarDefaults.enterAlwaysScrollBehavior(state = appBarState)
    } else {
        TopAppBarDefaults.pinnedScrollBehavior(state = appBarState)
    }
    // 钉住的那一刻顶栏可能正停在半收态（旋屏、改节次数、换课表都会碰上），
    // 而 pinned 从不动 heightOffset，不主动归位就会一直收着。
    LaunchedEffect(gridScrollable) {
        if (!gridScrollable) {
            appBarState.heightOffset = 0f
            appBarState.contentOffset = 0f
        }
    }
    Scaffold(
        modifier = Modifier.nestedScroll(appBarScrollBehavior.nestedScrollConnection),
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
                            // 多课表时前缀课表名（单课表用户界面零变化）
                            val prefix = ready.timetableName?.let { "$it · " } ?: ""
                            Text(
                                text = prefix + "第 ${ready.selectedWeek} 周" +
                                    if (ready.selectedWeek == ready.todayWeek) " · 本周" else "",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                actions = {
                    val ready = state as? ScheduleUiState.Ready
                    // 今天不在学期内时没有「本周」可回（回也是回第 1 周），按钮不显示
                    if (ready != null && ready.todayWeek != null && ready.selectedWeek != ready.todayWeek) {
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
                            Icon(Icons.Default.Settings, contentDescription = "显示设置")
                        }
                    }
                    // 日程不依赖学期，无学期时也给入口
                    IconButton(onClick = onOpenEvents) {
                        Icon(Icons.Default.Notifications, contentDescription = "日程安排")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                scrollBehavior = appBarScrollBehavior,
            )
        },
        floatingActionButton = {
            if (state is ScheduleUiState.Ready) {
                FloatingActionButton(onClick = onCreateCourse) {
                    Icon(Icons.Default.Add, contentDescription = "添加课程")
                }
            }
        },
        // 顶栏自己吃状态栏 inset；底栏在外层 NavHost，这里再垫 navigationBars
        // 会在课表和底栏之间多出一横条空白。所以垂直方向仍然归零。
        // 水平方向必须垫 safeDrawing：主题声明了 windowLayoutInDisplayCutoutMode
        // = shortEdges（内容主动延伸进刘海区），横屏时挖孔转到侧边，不补偿就会
        // 盖住课表最左或最右一整列。systemBars 不含 displayCutout，只能用 safeDrawing。
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
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
                val nowMinute = rememberNowMinute()
                // 这一屏画的是哪个学期的周次（翻页器写回时连带记下，见 viewModel.selectWeek）
                val numbering = WeekNumbering.of(ready.term)
                val pagerState = rememberPagerState(
                    initialPage = (ready.selectedWeek - 1).coerceIn(0, ready.term.totalWeeks - 1),
                    pageCount = { ready.term.totalWeeks },
                )

                // 翻页落定 → VM。用 settledPage 而非 currentPage：动画途中扫过的中间页
                // 不回写 VM，否则 selectedWeek 抖动会重启下方翻页效果、把动画拦腰取消。
                //
                // 首帧那次**不能写回**：翻页器的页码是 rememberSaveable 的，切走再切回这个 Tab
                // 会从保存态恢复当初那一页（initialPage 被忽略），照单全收就等于把「换课表 /
                // 换学期后重置翻到的周次」当场撤销——回到课表页仍停在上一个学期的周次上。
                // 跳过它之后：用户真翻页会来第二次，照常写回；重置成跟随今天时 VM 给的是
                // 另一个周次，下面那个 LaunchedEffect 会把翻页器滚过去，滚完的落定也照常写回。
                LaunchedEffect(pagerState, numbering) {
                    var firstSettleSkipped = false
                    snapshotFlow { pagerState.settledPage }.collect { page ->
                        if (!firstSettleSkipped) {
                            firstSettleSkipped = true
                            return@collect
                        }
                        viewModel.selectWeek(page + 1, numbering)
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
                    // 表头与网格必须拿同一份列（顺序、可见性都来自它），否则列会错位
                    val weekDays = ready.term.visibleWeekDays(ready.showWeekend)
                    WeekHeader(
                        term = ready.term,
                        week = ready.selectedWeek,
                        weekDays = weekDays,
                        todayDayOfWeek = if (ready.selectedWeek == ready.todayWeek) {
                            ready.todayDayOfWeek
                        } else {
                            null
                        },
                        showTimeInCards = ready.showTimeInCards,
                        // 无水平 padding：与 WeekGrid 总宽严格一致，分栏才能逐列对齐
                        modifier = Modifier.fillMaxWidth(),
                        dayOverrides = ready.dayOverrides,
                        onDayClick = { swapDay = it },
                    )

                    // 行高按可用高度自适应，但不低于 56dp。
                    // 必须在 verticalScroll **外面** 量：滚动链路里 maxHeight 是无穷，
                    // WeekGrid 内部再怎么 BoxWithConstraints 也只会拿到无界约束
                    // （WeekGrid 自己的注释也记着这件事）。
                    // 同样放在翻页器**外面**：每页各量一次的话，下面那一下 heightOffset
                    // 读数会把每一页都拖着跟顶栏动画逐帧重组，顺带重算相邻页的 layout。
                    // 平板竖屏净高一千多 dp：固定 56dp 会让 12 节只占 672dp，下方空出一大片；
                    // 手机横屏净高不到 200dp：取 56dp 下限，照旧靠滚动看全。
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        val totalPeriods = ready.periodTimes.size.coerceAtLeast(1)
                        // 顶栏收起时 Scaffold 的顶部内边距变小，这里的 maxHeight 反倒变大。
                        // 直接拿它算行高，网格就会跟着顶栏一起伸缩；「够不够滚」的判断也会
                        // 随之自激（收起→变高→不用滚→展开→又要滚→收起……）。
                        // heightOffset 是顶栏当前收起的像素数（≤0），加回去正好得到顶栏
                        // 完全展开时的净高 —— 与顶栏状态无关，行高和判断因此都是恒定的。
                        val expandedHeight = maxHeight + with(LocalDensity.current) {
                            appBarState.heightOffset.toDp()
                        }
                        val cellHeight = maxOf(PeriodCellHeight, expandedHeight / totalPeriods)
                        SideEffect { gridScrollable = PeriodCellHeight * totalPeriods > expandedHeight }

                        HorizontalPager(
                            state = pagerState,
                            modifier = Modifier.fillMaxSize(),
                        ) { page ->
                            val week = page + 1
                            val layout = if (week == ready.selectedWeek) {
                                ready.layout
                            } else {
                                WeekLayout.layoutForWeek(ready.schedule, week, ready.term, ready.dayOverrides)
                            }
                            // 灰块按「当前这一页的周」算：翻页动画里扫过的中间页也得各画各的
                            val otherWeekLayout = when {
                                !ready.showOtherWeek -> emptyMap<Int, List<PlacedBlock>>()
                                week == ready.selectedWeek -> ready.otherWeekLayout
                                else -> WeekLayout.otherWeekLayout(
                                    ready.schedule,
                                    week,
                                    ready.term,
                                    ready.dayOverrides,
                                )
                            }
                            Column(
                                Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState()),
                            ) {
                                WeekGrid(
                                    periodTimes = ready.periodTimes,
                                    layout = layout,
                                    weekDays = weekDays,
                                    todayDayOfWeek = if (week == ready.todayWeek) ready.todayDayOfWeek else null,
                                    showTimeInCards = ready.showTimeInCards,
                                    showGridLines = ready.showGridLines,
                                    nowMinuteOfDay = if (week == ready.todayWeek && ready.showNowLine) nowMinute else null,
                                    onBlockClick = { detailBlock = it },
                                    otherWeekLayout = otherWeekLayout,
                                    cellHeight = cellHeight,
                                )
                            }
                        }
                    }
                }

                detailBlock?.let { placed ->
                    val courseWithBlocks = ready.schedule.firstOrNull { it.course.id == placed.course.id }
                    CourseDetailSheet(
                        placed = placed,
                        courseWithBlocks = courseWithBlocks,
                        onAddExam = onAddExam,
                        onEditExam = onEditExam,
                        onEdit = { onEditCourse(placed.course.id) },
                        onDelete = { viewModel.deleteCourse(placed.course.id) },
                        onDismiss = { detailBlock = null },
                    )
                }

                swapDay?.let { day ->
                    DaySwapDialog(
                        term = ready.term,
                        epochDay = day,
                        sourceEpochDay = ready.dayOverrides[day],
                        onSelect = { source ->
                            viewModel.setDayOverride(day, source)
                            swapDay = null
                        },
                        onClear = {
                            viewModel.clearDayOverride(day)
                            swapDay = null
                        },
                        onDismiss = { swapDay = null },
                    )
                }

                if (showWeekPicker) {
                    WeekPickerDialog(
                        totalWeeks = ready.term.totalWeeks,
                        currentWeek = ready.todayWeek,
                        selectedWeek = ready.selectedWeek,
                        onSelect = {
                            viewModel.selectWeek(it, numbering)
                            showWeekPicker = false
                        },
                        onDismiss = { showWeekPicker = false },
                    )
                }

                if (showQuickSettings) {
                    AlertDialog(
                        onDismissRequest = { showQuickSettings = false },
                        title = { Text("显示设置") },
                        text = {
                            Column {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { viewModel.setShowNowLine(!ready.showNowLine) },
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text("显示时间线")
                                    Checkbox(
                                        checked = ready.showNowLine,
                                        onCheckedChange = { viewModel.setShowNowLine(it) },
                                    )
                                }
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
                                        .clickable { viewModel.setShowGridLines(!ready.showGridLines) },
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text("显示网格线")
                                    Checkbox(
                                        checked = ready.showGridLines,
                                        onCheckedChange = { viewModel.setShowGridLines(it) },
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
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { viewModel.setShowOtherWeek(!ready.showOtherWeek) },
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text("显示非本周课程", modifier = Modifier.weight(1f))
                                    Switch(
                                        checked = ready.showOtherWeek,
                                        onCheckedChange = { viewModel.setShowOtherWeek(it) },
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
    /** 今天所在的周；今天不在学期内时为 null（没有哪一格标「本周」）。 */
    currentWeek: Int?,
    selectedWeek: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    // 弹窗高度自适应：格子少时按内容收缩，多时封顶（约屏高一半，余量留给
    // 标题/按钮/弹窗内边距），超出部分滚动。原先固定 320dp，20 周学期只占
    // 四行、弹窗下半截全是空白，超长学期又不够放。
    val maxGridHeight = (LocalConfiguration.current.screenHeightDp * 0.5f).dp
        .coerceIn(180.dp, 420.dp)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择周次") },
        text = {
            // verticalScroll + heightIn(max)：内容不满封顶值时按内容收缩，
            // 溢出时容器停在封顶值、内部滚动——这是「收缩包裹但封顶」的唯一
            // 可靠写法（LazyVerticalGrid 遇有界主轴会直接填满，做不到收缩）。
            Column(
                modifier = Modifier
                    .heightIn(max = maxGridHeight)
                    .verticalScroll(rememberScrollState()),
            ) {
                // 每行格数随弹窗宽度自适应：手机竖屏约 5 个，横屏/平板/折叠屏
                // 展开态放得下更多就自动多排。格子定宽 48dp——两位数周次加内边距
                // 足够，也是可点击区域的最小舒适宽度。
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (week in 1..totalWeeks) {
                        WeekChip(
                            week = week,
                            selected = week == selectedWeek,
                            isCurrent = week == currentWeek,
                            onClick = { onSelect(week) },
                            modifier = Modifier.width(48.dp),
                        )
                    }
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
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.small,
        color = if (isCurrent) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = modifier,
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

/** 当前分钟数与今日页共用，见 NowMinute.kt 的 rememberNowMinute()。 */
