package com.nullclass.core.ui.layout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 弹窗内容的限高滚动包装。
 *
 * 起因：全项目 31 个弹层里有 21 个内容没有滚动容器。手机横屏可用高度只有约
 * 400dp，而日历选择器约 568dp、显示设置 5 行开关约 356dp、周次选择 20+ 周，
 * 统统放不下——内容不是被裁掉，就是把确认按钮挤出可视区，用户只能取消退出。
 *
 * 包一层即可：限高取窗口高度的一个比例，超出部分滚动。矮屏收得更紧一些，
 * 给弹窗外的留白和标题留位置。
 *
 * 注意日历式的 DatePickerDialog 不要靠这个救：568dp 的日历塞进 280dp 的滚动区
 * 仍然难用，那边应在矮屏直接切 DisplayMode.Input（见各调用点）。
 */
@Composable
fun AdaptiveDialogContent(
    modifier: Modifier = Modifier,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable ColumnScope.() -> Unit,
) {
    val windowSize = LocalWindowSize.current
    val fraction = if (windowSize.isCompactHeight) 0.70f else 0.80f
    // heightDp 为 0 表示没包 ProvideWindowSize（Preview / 测试）：不限高，退回原行为
    val cap = windowSize.heightDp * fraction

    Column(
        modifier = modifier
            .then(if (windowSize.heightDp > 0.dp) Modifier.heightIn(max = cap) else Modifier)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = horizontalAlignment,
        verticalArrangement = verticalArrangement,
        content = content,
    )
}
