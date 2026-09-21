package com.nullclass.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.nullclass.core.ui.layout.AdaptiveColumn
import java.time.LocalDate

/**
 * 「我的 → 快捷操作」：学期当中随时会用一下的操作，目前是调课与快速删课。
 *
 * 独立成页而不是散在设置里：这些是「今天要做的事」，不是配一次就不动的开关。
 * 这一层只做导航聚合，每个操作各自占一个子页（[DaySwapScreen]、[CourseCleanupScreen]）——
 * 两件事都带日期筛选和一份列表，堆在同一页里会互相抢滚动位置。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickActionsScreen(
    onBack: () -> Unit,
    onOpenDaySwap: () -> Unit,
    onOpenCourseCleanup: () -> Unit,
    viewModel: DaySwapViewModel = hiltViewModel(),
) {
    val overrides by viewModel.dayOverrides.collectAsState()
    val today = remember { LocalDate.now().toEpochDay() }
    // 过去的调课留在库里（回看历史周次仍要用），但对「接下来要做什么」没意义
    val upcomingSwaps = overrides.count { it.epochDay >= today }

    val appBarScrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(appBarScrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text("快捷操作") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                scrollBehavior = appBarScrollBehavior,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        AdaptiveColumn(
            modifier = Modifier.padding(padding),
            scrollState = rememberScrollState(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            imePadding = false,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            EntryRow(
                icon = Icons.Default.Refresh,
                title = "调课（串课）",
                subtitle = if (upcomingSwaps > 0) {
                    "调休时把某一天设成上另一天的课 · 今天及以后有 $upcomingSwaps 条"
                } else {
                    "调休时把某一天设成上另一天的课"
                },
                onClick = onOpenDaySwap,
            )
            EntryRow(
                icon = Icons.Default.Delete,
                title = "快速删课",
                subtitle = "按周次或某一天筛出课，勾选批量删除",
                onClick = onOpenCourseCleanup,
            )
        }
    }
}
