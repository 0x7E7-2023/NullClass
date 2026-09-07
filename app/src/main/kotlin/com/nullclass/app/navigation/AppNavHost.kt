package com.nullclass.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.nullclass.feature.edit.CourseEditScreen
import com.nullclass.feature.edit.TermEditScreen
import com.nullclass.feature.schedule.ScheduleScreen
import com.nullclass.feature.settings.SettingsScreen
import com.nullclass.feature.settings.transfer.PendingImport
import com.nullclass.feature.settings.transfer.TransferScreen

/** 全局路由表。feature 模块保持导航无关，由 :app 统一组装。 */
object Routes {
    const val SCHEDULE = "schedule"
    const val COURSE_EDIT = "course_edit?courseId={courseId}"
    const val TERM_EDIT = "term_edit?termId={termId}"
    const val SETTINGS = "settings"
    const val TRANSFER = "transfer"

    fun courseEdit(courseId: String? = null): String = "course_edit?courseId=${courseId ?: ""}"

    fun termEdit(termId: String? = null): String = "term_edit?termId=${termId ?: ""}"
}

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

    NavHost(navController = navController, startDestination = Routes.SCHEDULE) {
        composable(Routes.SCHEDULE) {
            ScheduleScreen(
                onCreateCourse = { navController.navigate(Routes.courseEdit()) },
                onEditCourse = { courseId ->
                    navController.navigate(Routes.courseEdit(courseId = courseId))
                },
                onEditTerm = { termId -> navController.navigate(Routes.termEdit(termId)) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenTransfer = { navController.navigate(Routes.TRANSFER) },
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
        composable(Routes.TRANSFER) {
            TransferScreen(
                onBack = ::back,
                pendingImport = PendingImport.uri.collectAsState(),
                onPendingImportConsumed = { PendingImport.uri.value = null },
            )
        }
    }
}
