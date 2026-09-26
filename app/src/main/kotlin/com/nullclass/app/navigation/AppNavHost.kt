package com.nullclass.app.navigation

import android.annotation.SuppressLint
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.NamedNavArgument
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.nullclass.core.ui.layout.LocalWindowSize
import com.nullclass.feature.edit.CourseEditScreen
import com.nullclass.feature.edit.TermEditScreen
import com.nullclass.feature.edit.TermListScreen
import com.nullclass.feature.edit.TimetableCreateScreen
import com.nullclass.feature.edit.TimetableListScreen
import com.nullclass.feature.exam.ExamEditScreen
import com.nullclass.feature.exam.ExamScreen
import com.nullclass.app.R
import com.nullclass.feature.schedule.EventScreen
import com.nullclass.feature.schedule.PersonalizationScreen
import com.nullclass.feature.schedule.ScheduleScreen
import com.nullclass.feature.schedule.TodayScreen
import com.nullclass.feature.settings.AboutScreen
import com.nullclass.feature.settings.CourseCleanupScreen
import com.nullclass.feature.settings.DaySwapScreen
import com.nullclass.feature.settings.QuickActionsScreen
import com.nullclass.feature.settings.NotificationSettingsScreen
import com.nullclass.feature.settings.ProfileScreen
import com.nullclass.feature.settings.SettingsScreen
import com.nullclass.feature.settings.transfer.PendingImport
import com.nullclass.feature.settings.transfer.TransferScreen

/** 全局路由表。feature 模块保持导航无关，由 :app 统一组装。 */
object Routes {
    const val TODAY = "today"
    const val SCHEDULE = "schedule"
    const val EXAMS = "exams"
    const val PROFILE = "profile"
    const val COURSE_EDIT = "course_edit?courseId={courseId}"
    const val TERM_EDIT = "term_edit?termId={termId}"
    const val TERM_LIST = "term_list"
    const val TIMETABLE_LIST = "timetable_list"
    const val TIMETABLE_CREATE = "timetable_create"
    const val SETTINGS = "settings"
    const val PERSONALIZATION = "personalization"
    const val NOTIFICATION_SETTINGS = "notification_settings"
    const val QUICK_ACTIONS = "quick_actions"
    const val DAY_SWAP = "day_swap"
    const val COURSE_CLEANUP = "course_cleanup"
    const val ABOUT = "about"
    const val TRANSFER = "transfer"
    const val EXAM_EDIT = "exam_edit?examId={examId}&courseId={courseId}"
    const val EVENTS = "events"

    fun courseEdit(courseId: String? = null): String = "course_edit?courseId=${courseId ?: ""}"

    fun termEdit(termId: String? = null): String = "term_edit?termId=${termId ?: ""}"

    fun examEdit(examId: String? = null, courseId: String? = null): String =
        "exam_edit?examId=${examId ?: ""}&courseId=${courseId ?: ""}"
}

/** 底部 Tab：今日 / 课表（start destination）/ 考试 / 我的。 */
private data class TopTab(
    val route: String,
    @StringRes val label: Int,
    val icon: ImageVector,
)

private val TopTabs = listOf(
    TopTab(Routes.TODAY, R.string.app_tab_today, Icons.Filled.DateRange),
    TopTab(Routes.SCHEDULE, R.string.app_tab_schedule, Icons.Filled.Home),
    TopTab(Routes.EXAMS, R.string.app_tab_exams, Icons.Filled.DateRange),
    TopTab(Routes.PROFILE, R.string.app_tab_profile, Icons.Filled.Person),
)

private const val PageFadeDurationMillis = 220

/**
 * 退出转场期间的触摸护罩。
 *
 * AnimatedContent 里正在退出的页面在转场结束前仍参与命中测试，而且 pop 时
 * NavHost 转场把目标页 zIndex 压到 -1——退出的那页压在上面吃触摸。从「关于」
 * 返回「我的」后快速点「导入/导出」「应用设置」，点到的其实是还没退干净的
 * 「关于」里同一位置的「源码仓库/反馈问题」链接行，直接跳浏览器（真机
 * release 偶发跳浏览器的根因；默认 700ms 淡出把这个窗口拉得很长）。
 * 转场一结束护罩消失；进入中的目标页不受影响，点击照常落在新页面上。
 */
@Composable
private fun AnimatedContentScope.ExitingTouchShield(content: @Composable () -> Unit) {
    val exiting by remember {
        derivedStateOf { transition.targetState == EnterExitState.PostExit }
    }
    Box {
        content()
        if (exiting) {
            // 透明护罩：吃掉手势里的每个事件（含按下和抬起），下层所有可点元素
            // 都会因事件被消费而取消
            Box(
                Modifier
                    .matchParentSize()
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            do {
                                val event = awaitPointerEvent()
                                event.changes.forEach { it.consume() }
                            } while (event.changes.any { it.pressed })
                        }
                    },
            )
        }
    }
}

/** 同 [composable]，但内容在退出转场期间屏蔽触摸（见 [ExitingTouchShield]）。 */
private fun NavGraphBuilder.screen(
    route: String,
    arguments: List<NamedNavArgument> = emptyList(),
    bottomBar: (@Composable () -> Unit)? = null,
    bottomBarHeight: () -> Dp = { 0.dp },
    /** true = 导航条在侧边（手机横屏的 NavigationRail），预留的是宽度而不是高度。 */
    railMode: () -> Boolean = { false },
    content: @Composable AnimatedContentScope.(NavBackStackEntry) -> Unit,
) {
    composable(route, arguments) { entry ->
        ExitingTouchShield {
            // 仅顶层页预留底栏高度；子页进出期间旧页面的尺寸始终稳定。
            Box(Modifier.fillMaxSize()) {
                val rail = railMode()
                val barSize = bottomBarHeight()
                // start/bottom 而非 left/top：RTL 下自动镜像到右侧
                val padding = if (rail) {
                    PaddingValues(start = barSize)
                } else {
                    PaddingValues(bottom = barSize)
                }
                Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                    content(this@composable, entry)
                }
                if (bottomBar != null) {
                    val align = if (rail) Alignment.CenterStart else Alignment.BottomCenter
                    Box(Modifier.align(align)) { bottomBar() }
                }
            }
        }
    }
}

@Composable
fun AppNavHost() {
    val navController = rememberNavController()
    val pendingImportUri by PendingImport.uri.collectAsState()

    // 首次启动闸门：没有课表 → 整棵导航树不渲染，只渲染创建页（创建后流自动放行）
    val gateViewModel: AppGateViewModel = hiltViewModel()
    val gateOverviews = gateViewModel.overviews.collectAsState().value
    val gated = gateOverviews == null || gateOverviews.isEmpty()
    val showExamTab by gateViewModel.showExamTab.collectAsState()

    // 「用其他应用打开」.nullclass → 直达导入页。被首启引导闸住时**先不导航**：
    // 闸门期间 NavHost 没被组合、graph 未设，navigate 会直接抛异常当场崩溃；
    // 闸门放行后 key（gated）变化令本 effect 重跑，导入页照常直达——
    // 冷启动带着导入 Intent 的新装用户：先建课表，建完直接落进导入预览。
    LaunchedEffect(pendingImportUri, gated) {
        if (pendingImportUri != null && !gated &&
            navController.currentDestination?.route != Routes.TRANSFER
        ) {
            navController.navigate(Routes.TRANSFER)
        }
    }

    fun back() {
        // 防弹空：popBackStack() 会把栈顶弹掉、包括 start destination。
        // 保存/返回按钮在退出转场（220ms fade，见下方 NavHost 转场注释）期间仍可点击，
        // 连点会逐层下探把返回栈清空 → NavHost 渲染空 → 永久卡在纯色背景页
        // （v0.4.x 白屏卡死根因）。栈里只剩当前页时不再弹，由系统返回手势走正常退出。
        if (navController.previousBackStackEntry != null) {
            navController.popBackStack()
        }
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    // 底栏只在四个顶层页显示；详情/子页推入后隐藏，返回键自然恢复
    val showTabBar = currentRoute in TopTabs.map { it.route }
    // 手机横屏：底部 NavigationBar 的 80dp 要吃掉 400dp 可用高度的 20%，而横向
    // 反而多出几百 dp 没处用。换成侧边 NavigationRail，把高度尽数还给内容，
    // 顺便消化掉横屏多出来的宽度——课表一屏可见节次能从约 3 节回到约 6 节。
    val useRail = LocalWindowSize.current.isCompactHeight
    // 宽屏（≥840dp，平板横屏 / 大平板竖屏）下「我的」与「考试」走双栏
    val twoPaneProfile = LocalWindowSize.current.isExpandedWidth
    // 按 useRail 作键重置：Rail 模式量的是宽度、Bar 模式量的是高度，两者语义不同。
    // 窗口尺寸变化未必都走 Activity 重建（LocalWindowSize 来自 BoxWithConstraints，
    // 同一 composition 内就能翻转），不重置的话会拿上一个方向的尺寸当这个方向用。
    var bottomBarHeightPx by remember(useRail) { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val defaultBottomBarHeight = if (useRail) {
        // Rail 量的是宽度，系统栏由 NavigationRail 自己的 windowInsets 处理
        80.dp
    } else {
        80.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    }
    val bottomBarHeight = {
        if (bottomBarHeightPx == 0) defaultBottomBarHeight
        else with(density) { bottomBarHeightPx.toDp() }
    }

    // —— 返回尾部竞态兜底（必须组合在下面的 NavHost 之前）——
    // NavHost 2.10 只在返回栈深度 > 1 时拦截返回手势（源码 NavHost.kt：
    // backHandler.isBackEnabled = currentBackStack.size > 1）。pop 提交的
    // 一瞬间被弹条目就离开了返回栈，但它的退出转场还要再播一段；这个尾部
    // 窗口里 NavHost 已放弃拦截，快速连滑的第二次返回手势会落到系统手里，
    // 插播「退到桌面」的系统预测动画、和还在播的应用转场叠在一起打架，
    // 松手甚至会直接退出应用（targetSdk 36 起预测返回默认开启，Android 14+
    // 真机偶发返回手势冲突的根因）。
    // 这里有页面正处在退出转场（visibleEntries 里出现 CREATED 态条目 =
    // 已被弹出、动画未完）时吞掉返回，转场落定后自动放行；栈在根上静止时
    // 的正常「返回退到桌面」不受影响。BackHandler 注册在 NavHost 之前 =
    // 同一 NavigationEventDispatcher 里优先级更低（LIFO），NavHost 拦截期间
    // 完全轮不到它。
    val visibleEntries by navController.visibleEntries.collectAsState()
    // 组合期读 currentState 本身不会触发重组，但这里的重组由 visibleEntries 驱动：
    // 条目被弹出（→ CREATED）与转场落定（→ DESTROYED、移出列表）时 NavController
    // 都会先改生命周期、再发新的 visibleEntries，读到的总是最新状态。
    @SuppressLint("LifecycleCurrentStateInComposition")
    val exitTransitionRunning =
        visibleEntries.any { it.lifecycle.currentState == Lifecycle.State.CREATED }
    BackHandler(enabled = exitTransitionRunning) { /* 吞掉，等退出转场播完 */ }

    if (gated) {
        // null = 首帧还没读到：什么都不画（外层已垫背景色）；空 = 全新安装，进引导
        if (gateOverviews != null) {
            TimetableCreateScreen(onDone = {}, standalone = true)
        }
        return
    }

    val transitionWithChild = visibleEntries.any { entry ->
        TopTabs.none { it.route == entry.destination.route }
    }
    val selectedRoute = if (showTabBar) currentRoute else
        visibleEntries.lastOrNull { entry ->
            TopTabs.any { it.route == entry.destination.route }
        }?.destination?.route
    val onTabClick: (TopTab) -> Unit = { tab ->
        if (showTabBar && currentRoute != tab.route) {
            navController.navigate(tab.route) {
                popUpTo(navController.graph.findStartDestination().id) {
                    saveState = true
                }
                launchSingleTop = true
                restoreState = true
            }
        }
    }
    // 「考试」标签页可在设置里关掉；其余标签始终常驻。
    val visibleTabs = if (showExamTab) TopTabs else TopTabs.filterNot { it.route == Routes.EXAMS }
    val tabBar: @Composable (Modifier) -> Unit = { modifier ->
        if (useRail) {
            // 量宽度：外层给内容预留的是 start padding
            NavigationRail(modifier.onSizeChanged { bottomBarHeightPx = it.width }) {
                visibleTabs.forEach { tab ->
                    NavigationRailItem(
                        selected = selectedRoute == tab.route,
                        onClick = { onTabClick(tab) },
                        icon = { Icon(tab.icon, contentDescription = stringResource(tab.label)) },
                        label = { Text(stringResource(tab.label)) },
                    )
                }
            }
        } else {
            NavigationBar(modifier.onSizeChanged { bottomBarHeightPx = it.height }) {
                visibleTabs.forEach { tab ->
                    NavigationBarItem(
                        selected = selectedRoute == tab.route,
                        onClick = { onTabClick(tab) },
                        icon = { Icon(tab.icon, contentDescription = stringResource(tab.label)) },
                        label = { Text(stringResource(tab.label)) },
                    )
                }
            }
        }
    }
    // 子页进出时，底栏进入页面的同一个动画图层（包含遮挡关系），不独立计时。
    // 同级 Tab 切换时只保留外层底栏，避免随页面淡入淡出；两处共用相同高度占位。
    val pageBottomBar: @Composable () -> Unit = {
        if (transitionWithChild) tabBar(Modifier)
    }

    Box(Modifier.fillMaxSize()) {
        NavHost(
            navController = navController,
            startDestination = Routes.SCHEDULE,
            // 默认转场是 700ms 的 tween 淡入淡出（DefaultNavTransitions），退出
            // 转场尾部拖得越长，上面兜底注释里「返回手势落到系统手里」的竞态
            // 窗口就越宽。220ms 是 Compose 常规动效时长，观感不变、窗口缩到 1/3。
            // 子页进出时底栏在顶层页内部，直接共享整页转场。
            enterTransition = { fadeIn(tween(PageFadeDurationMillis)) },
            exitTransition = { fadeOut(tween(PageFadeDurationMillis)) },
            popEnterTransition = { fadeIn(tween(PageFadeDurationMillis)) },
            popExitTransition = { fadeOut(tween(PageFadeDurationMillis)) },
            modifier = Modifier.fillMaxSize(),
        ) {
            screen(Routes.TODAY, bottomBar = pageBottomBar, bottomBarHeight = bottomBarHeight, railMode = { useRail }) {
                TodayScreen(
                    onEditCourse = { courseId ->
                        navController.navigate(Routes.courseEdit(courseId = courseId))
                    },
                    onAddExam = { courseId -> navController.navigate(Routes.examEdit(courseId = courseId)) },
                    onEditExam = { examId -> navController.navigate(Routes.examEdit(examId = examId)) },
                    onEditTerm = { termId -> navController.navigate(Routes.termEdit(termId)) },
                )
            }
            screen(Routes.SCHEDULE, bottomBar = pageBottomBar, bottomBarHeight = bottomBarHeight, railMode = { useRail }) {
                ScheduleScreen(
                    onCreateCourse = { navController.navigate(Routes.courseEdit()) },
                    onEditCourse = { courseId ->
                        navController.navigate(Routes.courseEdit(courseId = courseId))
                    },
                    onAddExam = { courseId -> navController.navigate(Routes.examEdit(courseId = courseId)) },
                    onEditExam = { examId -> navController.navigate(Routes.examEdit(examId = examId)) },
                    onEditTerm = { termId -> navController.navigate(Routes.termEdit(termId)) },
                    onOpenEvents = { navController.navigate(Routes.EVENTS) { launchSingleTop = true } },
                    onOpenPersonalization = {
                        navController.navigate(Routes.PERSONALIZATION) { launchSingleTop = true }
                    },
                )
            }
            screen(Routes.EXAMS, bottomBar = pageBottomBar, bottomBarHeight = bottomBarHeight, railMode = { useRail }) {
                if (twoPaneProfile) {
                    ExamTwoPane(outerNav = navController)
                } else {
                    ExamScreen(
                        onAddExam = { navController.navigate(Routes.examEdit()) },
                        onEditExam = { examId -> navController.navigate(Routes.examEdit(examId = examId)) },
                        onEditTerm = { termId -> navController.navigate(Routes.termEdit(termId)) },
                    )
                }
            }
            screen(Routes.PROFILE, bottomBar = pageBottomBar, bottomBarHeight = bottomBarHeight, railMode = { useRail }) {
                // 宽屏（≥840dp）走双栏：左边入口常驻、右边显示子页。
                // 这 8 个子页都不带导航参数，所以详情侧能直接渲染，不必嵌套 NavHost。
                if (twoPaneProfile) {
                    ProfileTwoPane(navController = navController)
                } else {
                    ProfileScreen(
                        onEditTerm = { termId -> navController.navigate(Routes.termEdit(termId)) },
                        onOpenQuickActions = { navController.navigate(Routes.QUICK_ACTIONS) },
                        onOpenTimetableList = { navController.navigate(Routes.TIMETABLE_LIST) },
                        onOpenTermList = { navController.navigate(Routes.TERM_LIST) },
                        onOpenTransfer = { navController.navigate(Routes.TRANSFER) },
                        onOpenNotificationSettings = {
                            navController.navigate(Routes.NOTIFICATION_SETTINGS)
                        },
                        onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                        onOpenPersonalization = { navController.navigate(Routes.PERSONALIZATION) },
                        onOpenAbout = { navController.navigate(Routes.ABOUT) },
                    )
                }
            }
            screen(
                route = Routes.COURSE_EDIT,
                arguments = listOf(
                    navArgument("courseId") { type = NavType.StringType; defaultValue = "" },
                ),
            ) { entry ->
                CourseEditScreen(
                    courseId = entry.arguments?.getString("courseId")?.takeIf { it.isNotBlank() },
                    onBack = ::back,
                )
            }
            screen(Routes.TERM_LIST) {
                TermListScreen(
                    onBack = ::back,
                    onCreateTerm = { navController.navigate(Routes.termEdit()) },
                    onEditTerm = { termId -> navController.navigate(Routes.termEdit(termId)) },
                )
            }
            screen(Routes.TIMETABLE_LIST) {
                TimetableListScreen(
                    onBack = ::back,
                    onCreateTimetable = { navController.navigate(Routes.TIMETABLE_CREATE) },
                )
            }
            screen(Routes.TIMETABLE_CREATE) {
                TimetableCreateScreen(
                    onDone = ::back,
                    onBack = ::back,
                )
            }
            screen(
                route = Routes.TERM_EDIT,
                arguments = listOf(
                    navArgument("termId") { type = NavType.StringType; defaultValue = "" },
                ),
            ) { entry ->
                TermEditScreen(
                    termId = entry.arguments?.getString("termId")?.takeIf { it.isNotBlank() },
                    onBack = ::back,
                )
            }
            screen(
                route = Routes.EXAM_EDIT,
                arguments = listOf(
                    navArgument("examId") { type = NavType.StringType; defaultValue = "" },
                    navArgument("courseId") { type = NavType.StringType; defaultValue = "" },
                ),
            ) {
                ExamEditScreen(onBack = ::back)
            }
            screen(Routes.SETTINGS) {
                SettingsScreen(
                    onBack = ::back,
                    onOpenTransfer = { navController.navigate(Routes.TRANSFER) },
                )
            }
            screen(Routes.PERSONALIZATION) {
                PersonalizationScreen(onBack = ::back)
            }
            screen(Routes.NOTIFICATION_SETTINGS) {
                NotificationSettingsScreen(onBack = ::back)
            }
            screen(Routes.QUICK_ACTIONS) {
                QuickActionsScreen(
                    onBack = ::back,
                    onOpenDaySwap = { navController.navigate(Routes.DAY_SWAP) },
                    onOpenEvents = { navController.navigate(Routes.EVENTS) { launchSingleTop = true } },
                    onOpenCourseCleanup = { navController.navigate(Routes.COURSE_CLEANUP) },
                )
            }
            screen(Routes.DAY_SWAP) {
                DaySwapScreen(onBack = ::back)
            }
            screen(Routes.COURSE_CLEANUP) {
                CourseCleanupScreen(onBack = ::back)
            }
            screen(Routes.EVENTS) {
                EventScreen(onBack = ::back)
            }
            screen(Routes.ABOUT) {
                AboutScreen(onBack = ::back)
            }
            screen(Routes.TRANSFER) {
                TransferScreen(
                    onBack = ::back,
                    pendingImport = PendingImport.uri.collectAsState(),
                    onPendingImportConsumed = { PendingImport.uri.value = null },
                )
            }
        }

        if (showTabBar && !transitionWithChild) {
            tabBar(Modifier.align(if (useRail) Alignment.CenterStart else Alignment.BottomCenter))
        }
    }
}
