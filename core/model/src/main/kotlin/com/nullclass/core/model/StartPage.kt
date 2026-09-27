package com.nullclass.core.model

/**
 * 打开应用时落在哪个底部标签页。默认周课表。
 *
 * 显示名称见 `:core:ui` 的 `StartPage.labelRes` —— 本模块是纯 Kotlin 模块，取不到 Android 资源。
 */
enum class StartPage {
    TODAY,
    SCHEDULE;

    companion object {
        fun fromName(name: String?): StartPage =
            entries.firstOrNull { it.name == name } ?: SCHEDULE
    }
}
