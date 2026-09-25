package com.nullclass.app.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.nullclass.app.R
import com.nullclass.feature.settings.AboutScreen
import com.nullclass.feature.settings.NotificationSettingsScreen
import com.nullclass.feature.settings.ProfileScreen
import com.nullclass.feature.settings.QuickActionsScreen
import com.nullclass.feature.settings.SettingsScreen
import com.nullclass.feature.edit.TermListScreen
import com.nullclass.feature.edit.TimetableListScreen
import com.nullclass.feature.settings.transfer.PendingImport
import com.nullclass.feature.settings.transfer.TransferScreen

/**
 * 「我的」在宽屏（Expanded，≥840dp：平板横屏 / 大平板竖屏）下的双栏形态：
 * 左边入口列表常驻，右边显示选中的子页。窄屏不走这里，仍是原来的整屏 push。
 *
 * **为什么不用 material3-adaptive 的 ListDetailPaneScaffold**：这一组的 7 个子页
 * 全都不带导航参数，详情侧只是「渲染哪一个 Composable」的单选，Row 就完全等价，
 * 而且没有嵌套返回栈。本项目的返回逻辑相当敏感（见 AppNavHost 里那段退出转场
 * 吞返回的注释），能不叠一层返回栈就不叠。
 *
 * 带导航参数的那几组（考试→考试编辑、学期→学期编辑、课表格子→课程编辑）情况不同：
 * 它们的 ViewModel 在 init 里就从 SavedStateHandle 取 id，详情侧必须能提供 nav 参数，
 * 那才真正需要嵌套 NavHost。见实现文档。
 */
@Composable
internal fun ProfileTwoPane(navController: NavHostController) {
    // 选中的子页路由；rememberSaveable 让它扛得住旋转
    var detailRoute by rememberSaveable { mutableStateOf<String?>(null) }

    // 详情栏开着时，系统返回先收详情栏，而不是直接退出「我的」
    BackHandler(enabled = detailRoute != null) { detailRoute = null }

    Row(Modifier.fillMaxSize()) {
        Box(Modifier.weight(ListPaneWeight)) {
            ProfileScreen(
                // 学期编辑带 termId，仍走整屏（ViewModel 要 nav 参数）
                onEditTerm = { termId -> navController.navigate(Routes.termEdit(termId)) },
                onOpenQuickActions = { detailRoute = Routes.QUICK_ACTIONS },
                onOpenTimetableList = { detailRoute = Routes.TIMETABLE_LIST },
                onOpenTermList = { detailRoute = Routes.TERM_LIST },
                onOpenTransfer = { detailRoute = Routes.TRANSFER },
                onOpenNotificationSettings = { detailRoute = Routes.NOTIFICATION_SETTINGS },
                onOpenSettings = { detailRoute = Routes.SETTINGS },
                onOpenAbout = { detailRoute = Routes.ABOUT },
            )
        }
        VerticalDivider()
        Box(Modifier.weight(DetailPaneWeight)) {
            val dismiss = { detailRoute = null }
            when (detailRoute) {
                Routes.SETTINGS -> SettingsScreen(
                    onBack = dismiss,
                    onOpenTransfer = { detailRoute = Routes.TRANSFER },
                )

                Routes.NOTIFICATION_SETTINGS -> NotificationSettingsScreen(onBack = dismiss)

                Routes.QUICK_ACTIONS -> QuickActionsScreen(
                    onBack = dismiss,
                    // 三级页仍走整屏：再切一栏会把可读宽度切得太碎
                    onOpenDaySwap = { navController.navigate(Routes.DAY_SWAP) },
                    onOpenCourseCleanup = { navController.navigate(Routes.COURSE_CLEANUP) },
                )

                Routes.TERM_LIST -> TermListScreen(
                    onBack = dismiss,
                    onCreateTerm = { navController.navigate(Routes.termEdit()) },
                    onEditTerm = { termId -> navController.navigate(Routes.termEdit(termId)) },
                )

                Routes.TIMETABLE_LIST -> TimetableListScreen(
                    onBack = dismiss,
                    onCreateTimetable = { navController.navigate(Routes.TIMETABLE_CREATE) },
                )

                Routes.TRANSFER -> TransferScreen(
                    onBack = dismiss,
                    pendingImport = PendingImport.uri.collectAsState(),
                    onPendingImportConsumed = { PendingImport.uri.value = null },
                )

                Routes.ABOUT -> AboutScreen(onBack = dismiss)

                else -> EmptyDetailHint()
            }
        }
    }
}

/** 还没选任何条目时右栏的占位。 */
@Composable
private fun EmptyDetailHint() {
    DetailPanePlaceholder(
        title = stringResource(R.string.app_detail_placeholder_profile_title),
        subtitle = stringResource(R.string.app_detail_placeholder_profile_desc),
    )
}

/** 考试双栏右栏的占位（[ExamTwoPane] 用）。 */
@Composable
internal fun ExamEmptyDetailHint() {
    DetailPanePlaceholder(
        title = stringResource(R.string.app_detail_placeholder_exam_title),
        subtitle = stringResource(R.string.app_detail_placeholder_exam_desc),
    )
}

@Composable
private fun DetailPanePlaceholder(title: String, subtitle: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** 列表栏偏窄、详情栏偏宽：840dp 下约 320dp + 520dp，两边都还在可读宽度内。 */
internal const val ListPaneWeight = 0.38f
internal const val DetailPaneWeight = 0.62f
