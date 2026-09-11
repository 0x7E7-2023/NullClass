package com.nullclass.app.navigation

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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.nullclass.feature.edit.CourseEditScreen
import com.nullclass.feature.edit.TermEditScreen
import com.nullclass.feature.edit.TermListScreen
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

@Composable
fun AppNavHost() {
    val navController = rememberNavController()
    val pendingImportUri by PendingImport.uri.collectAsState()

    // 「用其他应用打开」.nullclass → 直达导入页
    LaunchedEffect(pendingImportUri) {
        if (pendingImportUri != null && navController.currentDestination?.route != Routes.TRANSFER) {
            navController.navigate(Routes.TRANSFER)
        }
    }

    fun back() {
        // 防弹空：popBackStack() 会把栈顶弹掉、包括 start destination。
        // 保存/返回按钮在退出转场（默认 700ms fade）期间仍可点击，连点会逐层下探
        // 把返回栈清空 → NavHost 渲染空 → 永久卡在纯色背景页（v0.4.x 白屏卡死根因）。
        // 栈里只剩当前页时不再弹，由系统返回手势走正常退出。
        if (navController.previousBackStackEntry != null) {
            navController.popBackStack()
        }
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    // 底栏只在三个顶层页显示；详情/子页推入后隐藏，返回键自然恢复
    val showTabBar = currentRoute in TopTabs.map { it.route }

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
            modifier = Modifier
                .padding(padding)
                .consumeWindowInsets(padding),
        ) {
            composable(Routes.TODAY) {
                TodayScreen(
                    onEditCourse = { courseId ->
                        navController.navigate(Routes.courseEdit(courseId = courseId))
                    },
                    onEditTerm = { termId -> navController.navigate(Routes.termEdit(termId)) },
                )
            }
            composable(Routes.SCHEDULE) {
                ScheduleScreen(
                    onCreateCourse = { navController.navigate(Routes.courseEdit()) },
                    onEditCourse = { courseId ->
                        navController.navigate(Routes.courseEdit(courseId = courseId))
                    },
                    onEditTerm = { termId -> navController.navigate(Routes.termEdit(termId)) },
                )
            }
            composable(Routes.PROFILE) {
                ProfileScreen(
                    onEditTerm = { termId -> navController.navigate(Routes.termEdit(termId)) },
                    onOpenTermList = { navController.navigate(Routes.TERM_LIST) },
                    onOpenTransfer = { navController.navigate(Routes.TRANSFER) },
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                    onOpenAbout = { navController.navigate(Routes.ABOUT) },
                )
            }
            composable(
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
            composable(Routes.TERM_LIST) {
                TermListScreen(
                    onBack = ::back,
                    onCreateTerm = { navController.navigate(Routes.termEdit()) },
                )
            }
            composable(
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
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onBack = ::back,
                    onOpenTransfer = { navController.navigate(Routes.TRANSFER) },
                )
            }
            composable(Routes.ABOUT) {
                AboutScreen(onBack = ::back)
            }
            composable(Routes.TRANSFER) {
                TransferScreen(
                    onBack = ::back,
                    pendingImport = PendingImport.uri.collectAsState(),
                    onPendingImportConsumed = { PendingImport.uri.value = null },
                )
            }
        }
    }
}
