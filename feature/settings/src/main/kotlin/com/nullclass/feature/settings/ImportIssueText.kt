package com.nullclass.feature.settings

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import com.nullclass.core.ui.R as CoreR
import com.nullclass.core.ui.i18n.UiText
import com.nullclass.importer.ImportNotice
import com.nullclass.importer.ImportNoticeEntry
import com.nullclass.importer.ScheduleFileError
import com.nullclass.importer.ScheduleFileException

/**
 * 导入解析时产生的提示与错误。
 *
 * `:importer` 是纯 Kotlin 模块，只产出标识与参数（见 `ImportNotice` 的注释），
 * 词条映射放在这里。新增枚举项时下面的 `when` 会缺分支，编译器会提醒补上。
 */
private val ImportNotice.text: NoticeText
    get() = when (this) {
        // 拾光课程表
        ImportNotice.SHIGUANG_ROW_NOT_OBJECT -> Str(R.string.settings_notice_shiguang_row_not_object)
        ImportNotice.SHIGUANG_ROW_NO_NAME -> Str(R.string.settings_notice_shiguang_row_no_name)
        ImportNotice.SHIGUANG_ROW_NO_DAY -> Str(R.string.settings_notice_shiguang_row_no_day)
        ImportNotice.SHIGUANG_ROW_BAD_DAY -> Str(R.string.settings_notice_shiguang_row_bad_day)
        ImportNotice.SHIGUANG_WEEKS_TRUNCATED -> Str(R.string.settings_notice_shiguang_weeks_truncated)
        ImportNotice.SHIGUANG_WEEKS_EXCEED_DECLARED -> Str(R.string.settings_notice_shiguang_weeks_exceed_declared)
        ImportNotice.SHIGUANG_NO_START_DATE -> Str(R.string.settings_notice_shiguang_no_start_date)
        ImportNotice.SHIGUANG_START_DATE_UNREADABLE -> Str(R.string.settings_notice_shiguang_start_date_unreadable)
        ImportNotice.SHIGUANG_NO_PERIOD_TABLE -> Str(R.string.settings_notice_shiguang_no_period_table)
        ImportNotice.SHIGUANG_PERIOD_ROW_INVALID -> Str(R.string.settings_notice_shiguang_period_row_invalid)
        ImportNotice.SHIGUANG_PERIOD_OUT_OF_RANGE -> Str(R.string.settings_notice_shiguang_period_out_of_range)
        ImportNotice.SHIGUANG_PERIOD_TIME_INVALID -> Str(R.string.settings_notice_shiguang_period_time_invalid)
        ImportNotice.SHIGUANG_COURSE_BEYOND_LAST_PERIOD ->
            Str(R.string.settings_notice_shiguang_course_beyond_last_period)
        ImportNotice.SHIGUANG_COURSE_NO_TIME -> Str(R.string.settings_notice_shiguang_course_no_time)
        ImportNotice.SHIGUANG_CUSTOM_TIME_NO_NEAREST ->
            Str(R.string.settings_notice_shiguang_custom_time_no_nearest)
        ImportNotice.SHIGUANG_CUSTOM_TIME_NEAREST -> Str(R.string.settings_notice_shiguang_custom_time_nearest)
        ImportNotice.SHIGUANG_CUSTOM_TIME_DROPPED ->
            Quantity(R.plurals.settings_notice_shiguang_custom_time_dropped, quantityArg = 0)
        ImportNotice.SHIGUANG_PERIODS_EXTENDED -> Str(R.string.settings_notice_shiguang_periods_extended)
        ImportNotice.SHIGUANG_WEEKS_OVERFLOW_DROPPED ->
            Str(R.string.settings_notice_shiguang_weeks_overflow_dropped)
        ImportNotice.SHIGUANG_WEEKS_MISSING -> Str(R.string.settings_notice_shiguang_weeks_missing)
        ImportNotice.SHIGUANG_WEEKS_ALL_OUT_OF_RANGE ->
            Str(R.string.settings_notice_shiguang_weeks_all_out_of_range)

        // WakeUp
        ImportNotice.WAKEUP_LINE_UNPARSABLE -> Str(R.string.settings_notice_wakeup_line_unparsable)
        ImportNotice.WAKEUP_LINE_NOT_OBJECT -> Str(R.string.settings_notice_wakeup_line_not_object)
        ImportNotice.WAKEUP_LINE_NOT_ARRAY -> Str(R.string.settings_notice_wakeup_line_not_array)
        ImportNotice.WAKEUP_WEEKS_TRUNCATED -> Str(R.string.settings_notice_wakeup_weeks_truncated)
        ImportNotice.WAKEUP_PERIOD_ROW_INVALID -> Str(R.string.settings_notice_wakeup_period_row_invalid)
        ImportNotice.WAKEUP_NO_PERIOD_TABLE -> Str(R.string.settings_notice_wakeup_no_period_table)
        ImportNotice.WAKEUP_COURSE_NO_NAME -> Str(R.string.settings_notice_wakeup_course_no_name)
        ImportNotice.WAKEUP_BLOCK_MISSING_FIELD -> Str(R.string.settings_notice_wakeup_block_missing_field)
        ImportNotice.WAKEUP_BLOCK_BAD_STEP -> Str(R.string.settings_notice_wakeup_block_bad_step)
        ImportNotice.WAKEUP_BLOCK_BAD_DAY -> Str(R.string.settings_notice_wakeup_block_bad_day)
        ImportNotice.WAKEUP_BLOCK_BAD_START -> Str(R.string.settings_notice_wakeup_block_bad_start)

        // 教务：图片 OCR / 页面文字还原表格
        ImportNotice.OCR_NO_TEXT -> Str(R.string.settings_notice_ocr_no_text)
        ImportNotice.OCR_NO_DAY_HEADER -> Str(R.string.settings_notice_ocr_no_day_header)
        ImportNotice.OCR_DAY_COLUMNS_OUT_OF_RANGE -> Quantity(R.plurals.settings_notice_ocr_day_columns_out_of_range, quantityArg = 0)
        ImportNotice.OCR_DAY_COLUMNS_MISMATCH -> Str(R.string.settings_notice_ocr_day_columns_mismatch)
        ImportNotice.OCR_DAY_ORDER_ODD -> Str(R.string.settings_notice_ocr_day_order_odd)
        ImportNotice.OCR_PERIODS_BY_TIME -> Str(R.string.settings_notice_ocr_periods_by_time)
        ImportNotice.OCR_NO_PERIOD_COLUMN -> Quantity(R.plurals.settings_notice_ocr_no_period_column, quantityArg = 0)
        ImportNotice.OCR_PERIOD_ROWS_MISMATCH -> Str(R.string.settings_notice_ocr_period_rows_mismatch)
        ImportNotice.OCR_UNASSIGNED_BOXES -> Quantity(R.plurals.settings_notice_ocr_unassigned_boxes, quantityArg = 0)
        ImportNotice.OCR_NO_WEEK_INFO -> Str(R.string.settings_notice_ocr_no_week_info)
        ImportNotice.OCR_COLUMN_LINES_SKIPPED -> Quantity(R.plurals.settings_notice_ocr_column_lines_skipped, quantityArg = 1)
        ImportNotice.OCR_CELL_NO_GRID -> Str(R.string.settings_notice_ocr_cell_no_grid)
        ImportNotice.OCR_CELL_NO_WEEKS -> Str(R.string.settings_notice_ocr_cell_no_weeks)
        ImportNotice.OCR_NO_COURSES -> Str(R.string.settings_notice_ocr_no_courses)
    }

/** 一条提示用哪个词条：普通词条，或按第 [Quantity.quantityArg] 个参数取单复数的数量词条。 */
private sealed interface NoticeText

private data class Str(@StringRes val id: Int) : NoticeText

private data class Quantity(@PluralsRes val id: Int, val quantityArg: Int) : NoticeText

fun ImportNoticeEntry.toUiText(): UiText {
    // 列表参数（如一串周次）按当前语言的列举分隔符连起来 —— :importer 不知道该用「、」还是 ", "
    val values = args.map { arg ->
        if (arg is List<*>) UiText.Joined(arg.filterNotNull(), CoreR.string.common_list_separator) else arg
    }.toTypedArray()
    return when (val text = notice.text) {
        is Str -> UiText.Res(text.id, *values)
        is Quantity -> UiText.Plural(text.id, args[text.quantityArg] as Int, *values)
    }
}

/**
 * 导入文件本身不合法的原因码 → 词条。
 *
 * 放在 `:core:ui` 也能用，但那会逼着 `:core:ui` 依赖 `:importer`；
 * 目前只有导入导出页需要，就近放在这里。
 */
@get:StringRes
val ScheduleFileError.messageRes: Int
    get() = when (this) {
        ScheduleFileError.NULLCLASS_INVALID_FILE -> CoreR.string.error_import_invalid_file
        ScheduleFileError.QR_NOT_NULLCLASS -> CoreR.string.error_import_qr_no_prefix
        ScheduleFileError.QR_NO_SHAREABLE_TERM -> CoreR.string.error_import_qr_no_term
        ScheduleFileError.QR_CORRUPT -> CoreR.string.error_import_qr_corrupt
        ScheduleFileError.QR_GUNZIP_FAILED -> CoreR.string.error_import_qr_gunzip_failed
        ScheduleFileError.SHIGUANG_BAD_JSON -> CoreR.string.error_import_shiguang_bad_json
        ScheduleFileError.SHIGUANG_NOT_OBJECT -> CoreR.string.error_import_shiguang_not_object
        ScheduleFileError.SHIGUANG_NO_COURSES -> CoreR.string.error_import_shiguang_no_courses
        ScheduleFileError.SHIGUANG_EMPTY_COURSES -> CoreR.string.error_import_shiguang_empty
        ScheduleFileError.SHIGUANG_NO_ROWS -> CoreR.string.error_import_shiguang_no_rows
        ScheduleFileError.SHIGUANG_NO_PLACEABLE ->
            CoreR.string.error_import_shiguang_no_placeable
        ScheduleFileError.SHIGUANG_WEEKS_OUT_OF_RANGE ->
            CoreR.string.error_import_shiguang_weeks_out_of_range
        ScheduleFileError.WAKEUP_NO_SCHEDULE -> CoreR.string.error_import_wakeup_no_schedule
        ScheduleFileError.WAKEUP_NO_COURSES -> CoreR.string.error_import_wakeup_no_courses
        ScheduleFileError.WAKEUP_EMPTY_COURSES ->
            CoreR.string.error_import_wakeup_empty_courses
    }

/** [ScheduleFileException] 的显示文案。 */
fun ScheduleFileException.toUiText(): UiText = UiText.Res(error.messageRes)
