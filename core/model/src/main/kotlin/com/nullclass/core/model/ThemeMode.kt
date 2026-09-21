package com.nullclass.core.model

/**
 * 应用内配色模式。只作用于应用界面，桌面小组件仍跟随系统深浅色。
 *
 * - [DYNAMIC]：Material You 壁纸取色，深浅跟随系统；Android 11 及以下无取色，回退内置色板（仍跟随系统深浅）。
 * - [LIGHT] / [DARK]：固定浅色 / 深色，用内置 indigo 色板。
 */
enum class ThemeMode(val label: String) {
    DYNAMIC("Material 取色"),
    LIGHT("浅色"),
    DARK("深色");

    companion object {
        fun fromName(name: String?): ThemeMode =
            entries.firstOrNull { it.name == name } ?: DYNAMIC
    }
}
