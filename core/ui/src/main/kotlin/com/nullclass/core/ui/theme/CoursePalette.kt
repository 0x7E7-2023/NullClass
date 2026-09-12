package com.nullclass.core.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * 课程色板：12 色，[Course.colorIndex] 取模循环。
 * 教务导入的下标由 [com.nullclass.core.model.CourseColorKeywords] 按课名关键词写入。
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

/**
 * 「非本周」灰块的配色：不跟课程色走，一律主题灰。
 *
 * 灰块是给「这一格为什么空着」作注解的背景信息，不该和真课块争视线 ——
 * 所以它没有课程色可认，也就不能和任何一门课混淆。
 *
 * **框和字分开调，因为它们要的东西相反**：
 * - 框（容器 + 描边）压到很淡，只留一圈描边撑住轮廓 —— 这是要的「低可视度」；
 * - 字（「非本周」、课名、周次）不能跟着一起淡，那正是这个功能要传达的信息。
 *   0.7 对灰块底分别是深色 5.0:1 / 浅色 3.8:1，高过应用里最淡的既有标注
 *   （节次列 9sp @0.65 ≈ 3.5:1），又明显低于真课块的字（满 alpha 的 onSurface）——
 *   「比真课块淡一档，但读得清」。早先跟着容器一起压到 0.5（浅色只剩 2.4:1），
 *   结果是一块灰、看不清是哪门课。
 */
@Composable
fun otherWeekBlockColor(): CourseColor {
    val base = MaterialTheme.colorScheme.onSurfaceVariant
    // 0.08 而不是更小：深色主题下再低这块就彻底融进背景了，连描边都撑不住轮廓
    val containerAlpha = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) 0.08f else 0.06f
    return CourseColor(
        container = base.copy(alpha = containerAlpha),
        content = base.copy(alpha = 0.7f),
        border = base.copy(alpha = 0.14f),
    )
}
