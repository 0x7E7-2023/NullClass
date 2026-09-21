package com.nullclass.app.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.nullclass.feature.exam.ExamEditScreen
import com.nullclass.feature.exam.ExamScreen

/** 详情栏的空态路由，只在这个嵌套图里用。 */
private const val DETAIL_EMPTY = "detail_empty"

/**
 * 「考试」在宽屏（Expanded，≥840dp）下的双栏：左边考试列表，右边编辑。
 * 窄屏不走这里，仍是整屏 push。
 *
 * **详情侧是一个嵌套 NavHost，而不是直接渲染 ExamEditScreen**：
 * ExamEditViewModel 在 init 里就 `savedStateHandle.get<String>("examId")` 取 id
 * （TermEditScreen 那个 `termId` 形参其实是死参数，压根没被用上，别被它误导）。
 * 直接嵌入的话 ViewModel 拿不到 nav 参数，会把「编辑」当成「新建」。
 * 走嵌套 NavHost 则 nav 参数天然可用，ViewModel 与 Screen 都一行不用改。
 *
 * 返回语义：详情栏有内容时，系统返回先收详情栏；收干净了才轮到外层退出「考试」。
 */
@Composable
internal fun ExamTwoPane(outerNav: NavHostController) {
    val detailNav = rememberNavController()
    val detailEntry by detailNav.currentBackStackEntryAsState()
    val hasDetail = detailEntry?.destination?.route != DETAIL_EMPTY

    BackHandler(enabled = hasDetail) {
        detailNav.popBackStack(DETAIL_EMPTY, inclusive = false)
    }

    fun openDetail(route: String) {
        detailNav.navigate(route) {
            // 详情栏只保留一层：连着点两条考试不该堆出一摞返回栈
            popUpTo(DETAIL_EMPTY) { inclusive = false }
            launchSingleTop = true
        }
    }

    Row(Modifier.fillMaxSize()) {
        Box(Modifier.weight(ListPaneWeight)) {
            ExamScreen(
                onAddExam = { openDetail(Routes.examEdit()) },
                onEditExam = { examId -> openDetail(Routes.examEdit(examId = examId)) },
                // 学期编辑不属于这一栏的范畴，仍走整屏
                onEditTerm = { termId -> outerNav.navigate(Routes.termEdit(termId)) },
            )
        }
        VerticalDivider()
        Box(Modifier.weight(DetailPaneWeight)) {
            NavHost(
                navController = detailNav,
                startDestination = DETAIL_EMPTY,
            ) {
                composable(DETAIL_EMPTY) { ExamEmptyDetailHint() }
                composable(
                    route = Routes.EXAM_EDIT,
                    arguments = listOf(
                        navArgument("examId") { type = NavType.StringType; defaultValue = "" },
                        navArgument("courseId") { type = NavType.StringType; defaultValue = "" },
                    ),
                ) {
                    ExamEditScreen(
                        onBack = { detailNav.popBackStack(DETAIL_EMPTY, inclusive = false) },
                    )
                }
            }
        }
    }
}
