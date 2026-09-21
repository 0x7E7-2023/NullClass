package com.nullclass.core.ui.layout

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 宽度断点，与 Material3 WindowSizeClass 同口径（600dp / 840dp）。 */
enum class WidthClass { Compact, Medium, Expanded }

/**
 * 高度断点，只分「矮 / 正常」两档，阈值 480dp。
 *
 * 官方 WindowSizeClass 的高度分级对手机横屏区分度不够，而本次大量分支正是冲着
 * 手机横屏（约 890×400dp）去的：那里上下固定 chrome 会吃掉一半屏高，
 * 顶栏要收起、底栏要换侧栏、日历选择器要换输入模式。
 */
enum class HeightClass { Compact, Normal }

/**
 * 当前窗口尺寸档位。
 *
 * 同时保留原始 dp 值：弹窗限高这类场景需要按比例算（如窗口高度的 0.8），
 * 只有档位不够用。
 */
data class WindowSize(
    val width: WidthClass,
    val height: HeightClass,
    val widthDp: Dp,
    val heightDp: Dp,
) {
    /** 手机横屏。顶栏收起、底栏换侧栏、长弹窗换紧凑形态都看它。 */
    val isCompactHeight: Boolean get() = height == HeightClass.Compact

    /** 平板横屏 / 大平板竖屏。双栏只在这一档开。 */
    val isExpandedWidth: Boolean get() = width == WidthClass.Expanded

    /** 宽度够摆下限宽正文（含小平板竖屏与手机横屏）。 */
    val isAtLeastMediumWidth: Boolean get() = width != WidthClass.Compact
}

/**
 * 默认值按最保守的手机竖屏算：Preview、测试、或忘记包 [ProvideWindowSize] 的场景
 * 一律走窄屏分支，不会误入大屏布局。
 */
val LocalWindowSize = compositionLocalOf {
    WindowSize(WidthClass.Compact, HeightClass.Normal, 0.dp, 0.dp)
}

/**
 * 在窗口根部算一次尺寸档位并向下提供。
 *
 * **尺寸取自 `LocalConfiguration` 而不是 `BoxWithConstraints`**，这点很关键：
 * BoxWithConstraints 会在同一个 composition 内跟着约束实时翻转，而档位翻转会让
 * 上层按档位分支的地方（单栏 ⇄ 双栏、底栏 ⇄ 侧栏）**整棵子树被替换**——子树一旦
 * dispose，挂在它下面的 NavBackStackEntry 连同 ViewModelStore 一起销毁，详情栏里
 * 没点保存的编辑就静默没了。
 *
 * 改用 Configuration 后，档位只会随配置变更翻转；而两个 Activity 都没有声明
 * configChanges，配置变更必然走 Activity 重建，此时 NavController 会恢复返回栈、
 * NavBackStackEntry 的 ViewModelStore 跨配置变更存活，编辑内容得以保住。
 *
 * 也不用 material3-adaptive 的 `currentWindowAdaptiveInfo()`：那套 API 在版本间有过
 * 形态变动，而这里只要两个阈值判断；顺带把官方没有的「高度矮」语义一并算出来。
 */
@Composable
fun ProvideWindowSize(content: @Composable () -> Unit) {
    val configuration = LocalConfiguration.current
    val width = configuration.screenWidthDp.dp
    val height = configuration.screenHeightDp.dp
    val size = remember(width, height) {
        WindowSize(
            width = when {
                width >= 840.dp -> WidthClass.Expanded
                width >= 600.dp -> WidthClass.Medium
                else -> WidthClass.Compact
            },
            height = if (height < 480.dp) HeightClass.Compact else HeightClass.Normal,
            widthDp = width,
            heightDp = height,
        )
    }
    CompositionLocalProvider(LocalWindowSize provides size, content = content)
}
