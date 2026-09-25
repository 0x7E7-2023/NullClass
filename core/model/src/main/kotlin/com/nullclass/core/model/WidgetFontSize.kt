package com.nullclass.core.model

/**
 * 桌面小组件字号档。只作用于 Glance，应用内课表/今日页不读这个值。
 *
 * Glance 的 Text 没有省略号：特大档长课名和时间列更容易裁切，由用户自行权衡。
 *
 * 显示名称见 `:core:ui` 的 `WidgetFontSize.labelRes` —— 本模块是纯 Kotlin 模块，取不到 Android 资源。
 */
enum class WidgetFontSize(val scale: Float) {
    SMALL(0.9f),
    STANDARD(1.0f),
    LARGE(1.25f),
    XLARGE(1.5f);

    companion object {
        fun fromName(name: String?): WidgetFontSize =
            entries.firstOrNull { it.name == name } ?: STANDARD
    }
}
