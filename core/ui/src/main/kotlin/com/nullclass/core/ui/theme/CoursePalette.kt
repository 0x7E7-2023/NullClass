package com.nullclass.core.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * 课程色板：12 色，[Course.colorIndex] 取模循环。
 * 课块渲染用 [courseColor]（容器极低饱和 + 彩色边框勾勒颜色，文字用主题灰）；
 * [CourseColor.content] 保留全饱和原色，供详情页等需要强颜色识别的场景。
 * 色板选择器直接用 [CoursePalette]。
 */
val CoursePalette = listOf(
    Color(0xFFE8595B), // 红
    Color(0xFFE64980), // 玫红
    Color(0xFF9C36B5), // 紫
    Color(0xFF6741D9), // 深紫
    Color(0xFF3B5BDB), // 靛蓝
    Color(0xFF1C7ED6), // 蓝
    Color(0xFF0CA678), // 绿
    Color(0xFF2F9E44), // 深绿
    Color(0xFF74B816), // 黄绿
    Color(0xFFF59F00), // 橙
    Color(0xFFE8590C), // 深橙
    Color(0xFF846358), // 棕
)

data class CourseColor(
    val container: Color,
    val content: Color,
    val border: Color,
)

@Composable
fun courseColor(colorIndex: Int): CourseColor {
    val base = CoursePalette[colorIndex.mod(CoursePalette.size)]
    // 深色主题底色暗，透明度再低课块会隐形；浅色主题压到 5% 只留一点色底
    val containerAlpha = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) 0.18f else 0.15f
    return CourseColor(
        container = base.copy(alpha = containerAlpha),
        content = base,
        border = base.copy(alpha = 0.4f),
    )
}
