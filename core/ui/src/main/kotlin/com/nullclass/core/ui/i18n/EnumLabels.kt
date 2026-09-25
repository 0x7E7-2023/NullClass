package com.nullclass.core.ui.i18n

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.nullclass.core.model.AppLanguage
import com.nullclass.core.model.DataError
import com.nullclass.core.model.ThemeMode
import com.nullclass.core.model.WidgetFontSize
import com.nullclass.core.ui.R

/**
 * 共享枚举的显示名称。
 *
 * 枚举本身定义在纯 Kotlin 模块 `:core:model` 中，取不到 Android 资源，因此名称一律在此映射，
 * 枚举只保留与业务有关的属性。新增枚举项时编译器会在此处的 `when` 上报缺分支。
 */
@get:StringRes
val ThemeMode.labelRes: Int
    get() = when (this) {
        ThemeMode.DYNAMIC -> R.string.enum_theme_mode_dynamic
        ThemeMode.LIGHT -> R.string.enum_theme_mode_light
        ThemeMode.DARK -> R.string.enum_theme_mode_dark
    }

@get:StringRes
val WidgetFontSize.labelRes: Int
    get() = when (this) {
        WidgetFontSize.SMALL -> R.string.enum_widget_font_size_small
        WidgetFontSize.STANDARD -> R.string.enum_widget_font_size_standard
        WidgetFontSize.LARGE -> R.string.enum_widget_font_size_large
        WidgetFontSize.XLARGE -> R.string.enum_widget_font_size_xlarge
    }

@get:StringRes
val AppLanguage.labelRes: Int
    get() = when (this) {
        AppLanguage.SYSTEM -> R.string.enum_app_language_system
        AppLanguage.SIMPLIFIED_CHINESE -> R.string.enum_app_language_zh_hans
        AppLanguage.ENGLISH -> R.string.enum_app_language_en
    }

@Composable
fun ThemeMode.label(): String = stringResource(labelRes)

@Composable
fun WidgetFontSize.label(): String = stringResource(labelRes)

/**
 * 语言名称固定以该语言自身书写（简体中文、English），不随当前界面语言翻译
 * —— 用户需要在看不懂当前语言时也能找到自己的语言。
 */
@Composable
fun AppLanguage.label(): String = stringResource(labelRes)

/**
 * 数据层错误的显示文案。
 *
 * `:core:data` 只产出原因码（见 [DataError] 的注释）；映射放在这里而不是某个 feature 模块，
 * 因为 `DataException` 可能在任何界面被捕获。
 */
@get:StringRes
val DataError.messageRes: Int
    get() = when (this) {
        DataError.COURSE_NOT_FOUND -> R.string.error_data_course_not_found
        DataError.NO_TIMETABLE -> R.string.error_data_no_timetable
        DataError.TIMETABLE_NAME_EMPTY -> R.string.error_data_timetable_name_empty
    }
