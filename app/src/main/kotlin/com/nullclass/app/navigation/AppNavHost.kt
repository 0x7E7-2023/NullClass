package com.nullclass.app.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
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
import com.nullclass.feature.edit.CourseEditScreen
import com.nullclass.feature.edit.TermEditScreen
import com.nullclass.feature.edit.TermListScreen
import com.nullclass.feature.edit.TimetableCreateScreen
import com.nullclass.feature.edit.TimetableListScreen
import com.nullclass.feature.schedule.ScheduleScreen
import com.nullclass.feature.schedule.TodayScreen
import com.nullclass.feature.settings.AboutScreen
import com.nullclass.feature.settings.ProfileScreen
import com.nullclass.feature.settings.SettingsScreen
import com.nullclass.feature.settings.transfer.PendingImport
import com.nullclass.feature.settings.transfer.TransferScreen

/** 全局路由表。feature 模块保持导航无关，由 :app 统一组装。 */
object Routes {
    const val TODAY = "today"
    const val SCHEDULE = "schedule"
    const val PROFILE = "profile"
    const val COURSE_EDIT = "course_edit?courseId={courseId}"
    const val TERM_EDIT = "term_edit?termId={termId}"
    const val TERM_LIST = "term_list"
    const val TIMETABLE_LIST = "timetable_list"
    const val TIMETABLE_CREATE = "timetable_create"
    const val SETTINGS = "settings"
    const val ABOUT = "about"
    const val TRANSFER = "transfer"

    fun courseEdit(courseId: String? = null): String = "course_edit?courseId=${courseId ?: ""}"

    fun termEdit(termId: String? = null): String = "term_edit?termId=${termId ?: ""}"
}

/** 底部 Tab：今日（左）/ 课表（中，start destination）/ 我的（右）。 */
private data class TopTab(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

private val TopTabs = listOf(
    TopTab(Routes.TODAY, "今日", Icons.Filled.DateRange),
    TopTab(Routes.SCHEDULE, "课表", Icons.Filled.Home),
    TopTab(Routes.PROFILE, "我的", Icons.Filled.Person),
)

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
    content: @Composable AnimatedContentScope.(NavBackStackEntry) -> Unit,
) {
    composable(route, arguments) { entry ->
        ExitingTouchShield { content(this, entry) }
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
    // 底栏只在三个顶层页显示；详情/子页推入后隐藏，返回键自然恢复
    val showTabBar = currentRoute in TopTabs.map { it.route }

    // —— 预测返回尾部竞态兜底（必须组合在下面的 Scaffold/NavHost 之前）——
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

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        // 外层不声明 systemBars：否则 contentPadding 带状态栏高度，内层各页 TopAppBar
        // 再消费一遍同一 inset → 顶部双倍空隙。底栏高度由 bottomBar 测量垫上
        // （已含系统导航栏）；再 consumeWindowInsets，避免内层 Scaffold 把
        // navigationBars 又垫一次——课表网格和底栏之间会多出一横条空白。
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            if (showTabBar) {
                NavigationBar {
                    TopTabs.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = {
                                if (currentRoute == tab.route) return@NavigationBarItem
                                // 标准底栏模式：save/restore 保住各 Tab 的 ViewModel、
                                // 选中周次与滚动位置；回退键从任意 Tab 回到课表再退出
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.SCHEDULE,
            // 默认转场是 700ms 的 tween 淡入淡出（DefaultNavTransitions），退出
            // 转场尾部拖得越长，上面兜底注释里「返回手势落到系统手里」的竞态
            // 窗口就越宽。220ms 是 Compose 常规动效时长，观感不变、窗口缩到 1/3。
            // 预测返回手势拖拽时的预览走 predictivePop* 默认值（spring 淡入 +
            // 缩小），与这里的按钮/三键返回转场互不干扰。
            enterTransition = { fadeIn(tween(220)) },
            exitTransition = { fadeOut(tween(220)) },
            popEnterTransition = { fadeIn(tween(220)) },
            popExitTransition = { fadeOut(tween(220)) },
            modifier = Modifier
                .padding(padding)
                .consumeWindowInsets(padding),
        ) {
            screen(Routes.TODAY) {
                TodayScreen(
                    onEditCourse = { courseId ->
                        navController.navigate(Routes.courseEdit(courseId = courseId))
                    },
                    onEditTerm = { termId -> navController.navigate(Routes.termEdit(termId)) },
                )
            }
            screen(Routes.SCHEDULE) {
                ScheduleScreen(
                    onCreateCourse = { navController.navigate(Routes.courseEdit()) },
                    onEditCourse = { courseId ->
                        navController.navigate(Routes.courseEdit(courseId = courseId))
                    },
                    onEditTerm = { termId -> navController.navigate(Routes.termEdit(termId)) },
                )
            }
            screen(Routes.PROFILE) {
                ProfileScreen(
                    onEditTerm = { termId -> navController.navigate(Routes.termEdit(termId)) },
                    onOpenTimetableList = { navController.navigate(Routes.TIMETABLE_LIST) },
                    onOpenTermList = { navController.navigate(Routes.TERM_LIST) },
                    onOpenTransfer = { navController.navigate(Routes.TRANSFER) },
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                    onOpenAbout = { navController.navigate(Routes.ABOUT) },
                )
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
            screen(Routes.SETTINGS) {
                SettingsScreen(
                    onBack = ::back,
                    onOpenTransfer = { navController.navigate(Routes.TRANSFER) },
                )
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
    }
}
