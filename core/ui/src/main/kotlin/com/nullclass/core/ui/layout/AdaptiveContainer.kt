package com.nullclass.core.ui.layout

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 正文可读宽度上限。
 *
 * 再宽下去，设置项的标题在最左、开关甩到最右，一行要扫过整块屏幕才看得全；
 * 输入框横跨 1000dp 也难以定位光标。700dp 约等于 Material3 对正文栏的建议上限。
 */
val ReadableMaxWidth: Dp = 700.dp

/**
 * 限宽居中的可滚动列。用来替换各屏「fillMaxSize + verticalScroll + 水平 padding」
 * 的朴素根容器。
 *
 * 窄屏（手机竖屏）下 widthIn 不生效，布局与改造前逐像素一致；宽屏上内容停在
 * [maxWidth] 并居中。
 *
 * [imePadding] 默认开：全项目此前零处 IME inset 处理，而 Activity 开了
 * enableEdgeToEdge、主题又没声明 windowSoftInputMode，键盘会直接盖住底部输入框。
 * 垫在滚动容器上（而非内容上），键盘弹出时视口收缩、滚动范围变大，焦点才滚得出来。
 */
@Composable
fun AdaptiveColumn(
    modifier: Modifier = Modifier,
    scrollState: ScrollState? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp),
    maxWidth: Dp = ReadableMaxWidth,
    imePadding: Boolean = true,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .then(if (imePadding) Modifier.imePadding() else Modifier)
            .then(if (scrollState != null) Modifier.verticalScroll(scrollState) else Modifier),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = maxWidth)
                .fillMaxWidth()
                .padding(contentPadding),
            horizontalAlignment = horizontalAlignment,
            verticalArrangement = verticalArrangement,
            content = content,
        )
    }
}

/**
 * 限宽居中的通用包装。给 LazyColumn 这类自带滚动、不便换成 [AdaptiveColumn]
 * 的内容用：原样包一层即可，窄屏无变化。
 *
 * 内层用 fillMaxSize 而不是 wrap content——否则里面的 LazyColumn 会拿到无界
 * 高度，`fillMaxSize` 失效、列表塌成内容高度。
 */
@Composable
fun AdaptiveWidthWrapper(
    modifier: Modifier = Modifier,
    maxWidth: Dp = ReadableMaxWidth,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(Modifier.widthIn(max = maxWidth).fillMaxSize()) {
            content()
        }
    }
}

/**
 * 直接给元素限宽。配合父容器的居中对齐使用，适合不想多包一层 Box 的场合。
 */
fun Modifier.adaptiveWidth(maxWidth: Dp = ReadableMaxWidth): Modifier = widthIn(max = maxWidth)
