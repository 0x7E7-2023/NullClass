package com.nullclass.app.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.nullclass.feature.edit.CourseEditScreen
import com.nullclass.feature.edit.TermEditScreen
import com.nullclass.feature.schedule.ScheduleScreen
import com.nullclass.feature.settings.SettingsScreen

/** 全局路由表。feature 模块保持导航无关，由 :app 统一组装。 */
object Routes {
    const val SCHEDULE = "schedule"
    const val COURSE_EDIT = "course_edit?courseId={courseId}&day={day}&period={period}"
    const val TERM_EDIT = "term_edit?termId={termId}"
    const val SETTINGS = "settings"

    /** Int 参数用 -1 表示未提供。 */
    fun courseEdit(courseId: String? = null, day: Int? = null, period: Int? = null): String =
        "course_edit?courseId=${courseId ?: ""}&day=${day ?: -1}&period=${period ?: -1}"

    fun termEdit(termId: String? = null): String = "term_edit?termId=${termId ?: ""}"
}

@Composable
fun AppNavHost() {
    val navController = rememberNavController()

    fun back() = navController.popBackStack()

    NavHost(navController = navController, startDestination = Routes.SCHEDULE) {
        composable(Routes.SCHEDULE) {
            ScheduleScreen(
                onCreateCourse = { day, period ->
                    navController.navigate(Routes.courseEdit(day = day, period = period))
                },
                onEditCourse = { courseId ->
                    navController.navigate(Routes.courseEdit(courseId = courseId))
                },
                onEditTerm = { termId -> navController.navigate(Routes.termEdit(termId)) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }
        composable(
            route = Routes.COURSE_EDIT,
            arguments = listOf(
                navArgument("courseId") { type = NavType.StringType; defaultValue = "" },
                navArgument("day") { type = NavType.IntType; defaultValue = -1 },
                navArgument("period") { type = NavType.IntType; defaultValue = -1 },
            ),
        ) { entry ->
            CourseEditScreen(
                courseId = entry.arguments?.getString("courseId")?.takeIf { it.isNotBlank() },
                prefillDay = entry.arguments?.getInt("day")?.takeIf { it > 0 },
                prefillPeriod = entry.arguments?.getInt("period")?.takeIf { it > 0 },
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
            SettingsScreen(onBack = ::back)
        }
    }
}
