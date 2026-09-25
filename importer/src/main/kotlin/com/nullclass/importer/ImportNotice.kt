package com.nullclass.importer

/**
 * 导入过程中「跳过了什么、替成了什么」的一条提示。
 *
 * `:importer` 是纯 Kotlin 模块，取不到 Android 资源，因此这里只产出标识与参数，
 * 文案由界面层按当前语言取（[ImportNoticeEntry] 到词条的映射见
 * `:feature:settings` 的 `ImportIssueText.kt`）。
 *
 * 这些提示会显示在导入预览里（「⚠ …」那几行），所以是**用户可见**的文案，
 * 不能写成中文字面量 —— 这也是本枚举存在的原因。
 * 新增枚举项时映射处的 `when` 会缺分支，编译器会提醒补词条。
 */
enum class ImportNotice {

    // ---- 拾光课程表 ----
    /** 课程行不是对象。 */
    SHIGUANG_ROW_NOT_OBJECT,

    /** 课程行没有课名。 */
    SHIGUANG_ROW_NO_NAME,

    /** 课程行没有 day。 */
    SHIGUANG_ROW_NO_DAY,

    /** 课程行的 day 不是 1..7。 */
    SHIGUANG_ROW_BAD_DAY,

    /** 声明的学期周数超出上限，已截断。 */
    SHIGUANG_WEEKS_TRUNCATED,

    /** 课程里出现比配置声明的更多的周次。 */
    SHIGUANG_WEEKS_EXCEED_DECLARED,

    /** 导出文件里没有开学日期。 */
    SHIGUANG_NO_START_DATE,

    /** 开学日期无法识别。 */
    SHIGUANG_START_DATE_UNREADABLE,

    /** 导出文件里没有作息表。 */
    SHIGUANG_NO_PERIOD_TABLE,

    /** 作息表某条格式非法。 */
    SHIGUANG_PERIOD_ROW_INVALID,

    /** 作息表某节节号超出上限。 */
    SHIGUANG_PERIOD_OUT_OF_RANGE,

    /** 作息表某节结束时间不晚于开始时间。 */
    SHIGUANG_PERIOD_TIME_INVALID,

    /** 课程排到的节次超过一天的上限。 */
    SHIGUANG_COURSE_BEYOND_LAST_PERIOD,

    /** 课程既没有节次也没有可用的自定义时间。 */
    SHIGUANG_COURSE_NO_TIME,

    /** 只有自定义时间，作息表里找不到接近的节次。 */
    SHIGUANG_CUSTOM_TIME_NO_NEAREST,

    /** 只有自定义时间，已按最接近的节次放入。 */
    SHIGUANG_CUSTOM_TIME_NEAREST,

    /** 若干条课程设了自定义上下课时间，未保留。 */
    SHIGUANG_CUSTOM_TIME_DROPPED,

    /** 课程用到的节次超过作息表长度，已按默认课长补齐。 */
    SHIGUANG_PERIODS_EXTENDED,

    /** 某门课有周次超出学期总周数，超出部分已丢弃。 */
    SHIGUANG_WEEKS_OVERFLOW_DROPPED,

    /** 某门课没有周次信息，已按整学期导入。 */
    SHIGUANG_WEEKS_MISSING,

    /** 某门课的周次全在学期之外，已跳过。 */
    SHIGUANG_WEEKS_ALL_OUT_OF_RANGE,

    // ---- WakeUp ----
    /** 某行无法解析。 */
    WAKEUP_LINE_UNPARSABLE,

    /** 某行是无法识别的对象。 */
    WAKEUP_LINE_NOT_OBJECT,

    /** 某行是无法识别的数组。 */
    WAKEUP_LINE_NOT_ARRAY,

    /** 学期周数超出范围，已截断。 */
    WAKEUP_WEEKS_TRUNCATED,

    /** 节次时间某条格式非法。 */
    WAKEUP_PERIOD_ROW_INVALID,

    /** WakeUp 文件无节次时间表，已用默认模板。 */
    WAKEUP_NO_PERIOD_TABLE,

    /** 某门课缺少课名信息，已按 id 生成。 */
    WAKEUP_COURSE_NO_NAME,

    /** 某条安排缺少 id/day/startNode。 */
    WAKEUP_BLOCK_MISSING_FIELD,

    /** 某条安排的 step 非法，已按 1 节处理。 */
    WAKEUP_BLOCK_BAD_STEP,

    /** 某条安排的 day 非法。 */
    WAKEUP_BLOCK_BAD_DAY,

    /** 某条安排的 startNode 非法。 */
    WAKEUP_BLOCK_BAD_START,

    // ---- 教务：图片 OCR / 页面文字还原表格（JwTableAligner、JwOcrScheduleBuilder） ----
    // 这组显示在教务导入的状态行与「核对识别结果」弹窗里。

    /** 图片里没有识别到任何文字。 */
    OCR_NO_TEXT,

    /** 没有找到星期表头行。 */
    OCR_NO_DAY_HEADER,

    /** 星期列数不在 5..7 之内。参数：识别到的列数。 */
    OCR_DAY_COLUMNS_OUT_OF_RANGE,

    /** 星期列数与适配器声明的不符。参数：识别到的列数、预期列数。 */
    OCR_DAY_COLUMNS_MISMATCH,

    /** 星期表头顺序异常（需用户核对）。 */
    OCR_DAY_ORDER_ODD,

    /** 节次列按上课时间认出，节次号按行序推断（需用户核对）。 */
    OCR_PERIODS_BY_TIME,

    /** 没有找到节次列。参数：识别到的行数。 */
    OCR_NO_PERIOD_COLUMN,

    /** 节次行数与适配器声明的不符。参数：识别到的行数、预期行数。 */
    OCR_PERIOD_ROWS_MISMATCH,

    /** 有文本块没能归入网格。参数：块数、占比（整数百分数）。 */
    OCR_UNASSIGNED_BOXES,

    /** 格子里没有周次信息，已逐格还原。 */
    OCR_NO_WEEK_INFO,

    /** 某列有几行文字没归到任何课程上。参数：列号（从 1 起）、行数。 */
    OCR_COLUMN_LINES_SKIPPED,

    /** 某格网格坐标缺失。参数：行号、列号（从 1 起）。 */
    OCR_CELL_NO_GRID,

    /** 某格没识别出周次，已按整学期处理。参数：行号、列号（从 1 起）、课名。 */
    OCR_CELL_NO_WEEKS,

    /** 整张表没有识别出任何课程。 */
    OCR_NO_COURSES,
}

/**
 * 一条提示 + 它的占位符参数（顺序与文案里的 `%1$s` / `%2$d` 一致）。
 *
 * 参数只放数据：数字、名称，或一串数据（`List`）—— 列表由界面层用当前语言的分隔符连起来，
 * 这里不能先拼成「1、2、3」。
 */
data class ImportNoticeEntry(val notice: ImportNotice, val args: List<Any> = emptyList())

/**
 * 导入文件本身不合法的原因。
 *
 * 与 [ImportNotice] 同样只产出标识；[ScheduleFileException] 把它抛出去，
 * 界面层按当前语言取文案。
 */
enum class ScheduleFileError {

    /** 不是有效的空课文件（.nullclass / WebDAV 快照）。 */
    NULLCLASS_INVALID_FILE,

    /** 二维码缺少协议头。 */
    QR_NOT_NULLCLASS,

    /** 找不到可分享的学期。 */
    QR_NO_SHAREABLE_TERM,

    /** 二维码内容已损坏。 */
    QR_CORRUPT,

    /** 二维码数据无法解压。 */
    QR_GUNZIP_FAILED,

    /** 不是合法的 JSON。 */
    SHIGUANG_BAD_JSON,

    /** 顶层不是对象。 */
    SHIGUANG_NOT_OBJECT,

    /** 缺少 courses 字段。 */
    SHIGUANG_NO_COURSES,

    /** 课程列表为空。 */
    SHIGUANG_EMPTY_COURSES,

    /** 没有可用的课程数据。 */
    SHIGUANG_NO_ROWS,

    /** 没有可以放进课表的课程。 */
    SHIGUANG_NO_PLACEABLE,

    /** 课程周次都不在学期范围内。 */
    SHIGUANG_WEEKS_OUT_OF_RANGE,

    /** 未找到课程时间安排数据。 */
    WAKEUP_NO_SCHEDULE,

    /** 缺少课程信息（courseName 列表）。 */
    WAKEUP_NO_COURSES,

    /** 课程信息为空。 */
    WAKEUP_EMPTY_COURSES,
}

/** 导入文件不合法，带原因码。文案由界面层按 [ScheduleFileError] 取。 */
class ScheduleFileException(val error: ScheduleFileError) : IllegalArgumentException(error.name)
