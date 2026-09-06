package com.nullclass.app.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.nullclass.feature.edit.CourseEditScreen
import com.nullclass.feature.schedule.ScheduleScreen

/** 全局路由表。feature 模块保持导航无关，由 :app 统一组装。 */
object Routes {
    const val SCHEDULE = "schedule"
    const val COURSE_EDIT = "course_edit"
}

@Composable
fun AppNavHost() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Routes.SCHEDULE) {
        composable(Routes.SCHEDULE) {
            ScheduleScreen()
        }
        composable(Routes.COURSE_EDIT) {
            CourseEditScreen()
        }
    }
}
