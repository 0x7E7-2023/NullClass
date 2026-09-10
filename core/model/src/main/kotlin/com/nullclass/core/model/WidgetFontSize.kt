package com.nullclass.core.model

/**
 * 桌面小组件字号档。只作用于 Glance，应用内课表/今日页不读这个值。
 *
 * Glance 的 Text 没有省略号：特大档长课名和时间列更容易裁切，由用户自己权衡。
 */
enum class WidgetFontSize(val scale: Float, val label: String) {
    SMALL(0.9f, "小"),
    STANDARD(1.0f, "标准"),
    LARGE(1.25f, "大"),
    XLARGE(1.5f, "特大");

    companion object {
        fun fromName(name: String?): WidgetFontSize =
            entries.firstOrNull { it.name == name } ?: STANDARD
    }
}
