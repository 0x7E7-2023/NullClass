package com.nullclass.feature.settings

import com.nullclass.core.ui.i18n.totalWeeksLabel
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.nullclass.core.ui.layout.AdaptiveColumn

/**
 * 「我的」Tab：入口中枢——学期卡片、快捷操作、学期管理、导入/导出、应用设置、关于（子页）。
 * 长表单类内容（WebDAV、提醒、小组件）保留在应用设置子页，中枢只做导航聚合。
 */
@Composable
fun ProfileScreen(
    onEditTerm: (termId: String?) -> Unit,
    onOpenQuickActions: () -> Unit,
    onOpenTimetableList: () -> Unit,
    onOpenTermList: () -> Unit,
    onOpenTransfer: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAbout: () -> Unit,
    viewModel: ProfileViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        // 同 TodayScreen：主题是 shortEdges，横屏侧边刘海要靠 safeDrawing 才补偿得到；
        // 这页也没有 TopAppBar，所以取完整的 safeDrawing 以保留顶部状态栏 inset。
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        AdaptiveColumn(
            modifier = Modifier.padding(padding),
            scrollState = rememberScrollState(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            // 这页没有输入框，保持原有 inset 行为
            imePadding = false,
        ) {
            Text(
                stringResource(R.string.settings_profile_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
            )

            // ---- 学期卡片 ----
            val term = state.term
            Surface(
                onClick = { onEditTerm(term?.id) },
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    state.timetableName?.let { timetableName ->
                        Text(
                            stringResource(R.string.settings_profile_timetable, timetableName),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f),
                        )
                    }
                    Text(
                        term?.name ?: stringResource(R.string.settings_profile_no_term_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    // state 是委托属性，不能智能转换，先把当前周收进局部变量
                    val currentWeek = state.currentWeek
                    Text(
                        when {
                            term == null -> stringResource(R.string.settings_profile_no_term_desc)
                            currentWeek != null -> stringResource(
                                R.string.settings_profile_week,
                                currentWeek,
                                totalWeeksLabel(term.totalWeeks),
                            )
                            else -> stringResource(R.string.settings_profile_out_of_term, totalWeeksLabel(term.totalWeeks))
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            // ---- 入口分组 ----
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                EntryRow(
                    icon = Icons.Default.Refresh,
                    title = stringResource(R.string.settings_profile_quick_actions),
                    subtitle = if (state.upcomingDaySwaps > 0) {
                        pluralStringResource(
                            R.plurals.settings_profile_quick_actions_desc_with_count,
                            state.upcomingDaySwaps,
                            state.upcomingDaySwaps,
                        )
                    } else {
                        stringResource(R.string.settings_profile_quick_actions_desc)
                    },
                    onClick = onOpenQuickActions,
                )
                EntryRow(
                    icon = Icons.AutoMirrored.Filled.List,
                    title = stringResource(R.string.settings_profile_timetables),
                    subtitle = stringResource(
                        R.string.settings_profile_timetables_desc,
                        state.timetableName ?: "—",
                    ),
                    onClick = onOpenTimetableList,
                )
                EntryRow(
                    icon = Icons.Default.DateRange,
                    title = stringResource(R.string.settings_profile_terms),
                    subtitle = stringResource(R.string.settings_profile_terms_desc),
                    onClick = onOpenTermList,
                )
                EntryRow(
                    icon = Icons.Default.Share,
                    title = stringResource(R.string.settings_transfer_entry),
                    subtitle = stringResource(R.string.settings_profile_transfer_desc),
                    onClick = onOpenTransfer,
                )
                EntryRow(
                    icon = Icons.Default.Notifications,
                    title = stringResource(R.string.settings_profile_notification),
                    subtitle = stringResource(R.string.settings_profile_notification_desc),
                    onClick = onOpenNotificationSettings,
                )
                EntryRow(
                    icon = Icons.Default.Settings,
                    title = stringResource(R.string.settings_title),
                    subtitle = stringResource(R.string.settings_profile_settings_desc),
                    onClick = onOpenSettings,
                )
                EntryRow(
                    icon = Icons.Default.Info,
                    title = stringResource(R.string.settings_profile_about),
                    subtitle = stringResource(R.string.settings_profile_about_desc),
                    onClick = onOpenAbout,
                )
            }
        }
    }
}
