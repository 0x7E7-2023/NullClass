package com.nullclass.core.model

/**
 * 应用内配色模式。只作用于应用界面，桌面小组件仍跟随系统深浅色。
 *
 * - [DYNAMIC]：Material You 壁纸取色，深浅跟随系统；Android 11 及以下无取色，回退内置色板（仍跟随系统深浅）。
 * - [LIGHT] / [DARK]：固定浅色 / 深色，用内置 indigo 色板。
 *
 * 显示名称见 `:core:ui` 的 `ThemeMode.labelRes` —— 本模块是纯 Kotlin 模块，取不到 Android 资源。
 */
enum class ThemeMode {
    DYNAMIC,
    LIGHT,
    DARK;

    companion object {
        fun fromName(name: String?): ThemeMode =
            entries.firstOrNull { it.name == name } ?: DYNAMIC
    }
}
