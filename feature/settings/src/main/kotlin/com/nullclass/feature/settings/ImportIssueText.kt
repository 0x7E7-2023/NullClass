package com.nullclass.feature.settings

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
@get:StringRes
val ImportNotice.messageRes: Int
    get() = when (this) {
        // 拾光课程表
        ImportNotice.SHIGUANG_ROW_NOT_OBJECT -> R.string.settings_notice_shiguang_row_not_object
        ImportNotice.SHIGUANG_ROW_NO_NAME -> R.string.settings_notice_shiguang_row_no_name
        ImportNotice.SHIGUANG_ROW_BAD_DAY -> R.string.settings_notice_shiguang_row_bad_day
        ImportNotice.SHIGUANG_WEEKS_TRUNCATED -> R.string.settings_notice_shiguang_weeks_truncated
        ImportNotice.SHIGUANG_WEEKS_EXCEED_DECLARED -> R.string.settings_notice_shiguang_weeks_exceed_declared
        ImportNotice.SHIGUANG_NO_START_DATE -> R.string.settings_notice_shiguang_no_start_date
        ImportNotice.SHIGUANG_START_DATE_UNREADABLE -> R.string.settings_notice_shiguang_start_date_unreadable
        ImportNotice.SHIGUANG_NO_PERIOD_TABLE -> R.string.settings_notice_shiguang_no_period_table
        ImportNotice.SHIGUANG_PERIOD_ROW_INVALID -> R.string.settings_notice_shiguang_period_row_invalid
        ImportNotice.SHIGUANG_PERIOD_OUT_OF_RANGE -> R.string.settings_notice_shiguang_period_out_of_range
        ImportNotice.SHIGUANG_PERIOD_TIME_INVALID -> R.string.settings_notice_shiguang_period_time_invalid
        ImportNotice.SHIGUANG_COURSE_BEYOND_LAST_PERIOD ->
            R.string.settings_notice_shiguang_course_beyond_last_period
        ImportNotice.SHIGUANG_COURSE_NO_TIME -> R.string.settings_notice_shiguang_course_no_time
        ImportNotice.SHIGUANG_CUSTOM_TIME_NO_NEAREST ->
            R.string.settings_notice_shiguang_custom_time_no_nearest
        ImportNotice.SHIGUANG_CUSTOM_TIME_NEAREST -> R.string.settings_notice_shiguang_custom_time_nearest
        ImportNotice.SHIGUANG_CUSTOM_TIME_DROPPED ->
            R.string.settings_notice_shiguang_custom_time_dropped
        ImportNotice.SHIGUANG_PERIODS_EXTENDED -> R.string.settings_notice_shiguang_periods_extended
        ImportNotice.SHIGUANG_WEEKS_OVERFLOW_DROPPED ->
            R.string.settings_notice_shiguang_weeks_overflow_dropped
        ImportNotice.SHIGUANG_WEEKS_MISSING -> R.string.settings_notice_shiguang_weeks_missing
        ImportNotice.SHIGUANG_WEEKS_ALL_OUT_OF_RANGE ->
            R.string.settings_notice_shiguang_weeks_all_out_of_range

        // WakeUp
        ImportNotice.WAKEUP_LINE_UNPARSABLE -> R.string.settings_notice_wakeup_line_unparsable
        ImportNotice.WAKEUP_LINE_NOT_OBJECT -> R.string.settings_notice_wakeup_line_not_object
        ImportNotice.WAKEUP_LINE_NOT_ARRAY -> R.string.settings_notice_wakeup_line_not_array
        ImportNotice.WAKEUP_WEEKS_TRUNCATED -> R.string.settings_notice_wakeup_weeks_truncated
        ImportNotice.WAKEUP_PERIOD_ROW_INVALID -> R.string.settings_notice_wakeup_period_row_invalid
        ImportNotice.WAKEUP_NO_PERIOD_TABLE -> R.string.settings_notice_wakeup_no_period_table
        ImportNotice.WAKEUP_COURSE_NO_NAME -> R.string.settings_notice_wakeup_course_no_name
        ImportNotice.WAKEUP_BLOCK_MISSING_FIELD -> R.string.settings_notice_wakeup_block_missing_field
        ImportNotice.WAKEUP_BLOCK_BAD_STEP -> R.string.settings_notice_wakeup_block_bad_step
        ImportNotice.WAKEUP_BLOCK_BAD_DAY -> R.string.settings_notice_wakeup_block_bad_day
        ImportNotice.WAKEUP_BLOCK_BAD_START -> R.string.settings_notice_wakeup_block_bad_start

        // 教务：图片 OCR / 页面文字还原表格
        ImportNotice.OCR_NO_TEXT -> R.string.settings_notice_ocr_no_text
        ImportNotice.OCR_NO_DAY_HEADER -> R.string.settings_notice_ocr_no_day_header
        ImportNotice.OCR_DAY_COLUMNS_OUT_OF_RANGE -> R.string.settings_notice_ocr_day_columns_out_of_range
        ImportNotice.OCR_DAY_COLUMNS_MISMATCH -> R.string.settings_notice_ocr_day_columns_mismatch
        ImportNotice.OCR_DAY_ORDER_ODD -> R.string.settings_notice_ocr_day_order_odd
        ImportNotice.OCR_PERIODS_BY_TIME -> R.string.settings_notice_ocr_periods_by_time
        ImportNotice.OCR_NO_PERIOD_COLUMN -> R.string.settings_notice_ocr_no_period_column
        ImportNotice.OCR_PERIOD_ROWS_MISMATCH -> R.string.settings_notice_ocr_period_rows_mismatch
        ImportNotice.OCR_UNASSIGNED_BOXES -> R.string.settings_notice_ocr_unassigned_boxes
        ImportNotice.OCR_NO_WEEK_INFO -> R.string.settings_notice_ocr_no_week_info
        ImportNotice.OCR_COLUMN_LINES_SKIPPED -> R.string.settings_notice_ocr_column_lines_skipped
        ImportNotice.OCR_CELL_NO_GRID -> R.string.settings_notice_ocr_cell_no_grid
        ImportNotice.OCR_CELL_NO_WEEKS -> R.string.settings_notice_ocr_cell_no_weeks
        ImportNotice.OCR_NO_COURSES -> R.string.settings_notice_ocr_no_courses
    }

fun ImportNoticeEntry.toUiText(): UiText = UiText.Res(notice.messageRes, *args.toTypedArray())

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
