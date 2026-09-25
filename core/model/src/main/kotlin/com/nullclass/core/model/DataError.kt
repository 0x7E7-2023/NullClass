package com.nullclass.core.model

/**
 * 数据层拒绝一次写入的原因。
 *
 * 仓库层不产出文案 —— 它拿不到当前界面语言，把中文写死在那里会让提示无法翻译。
 * 原因码到文案的映射见 `:core:ui` 的 `DataError.messageRes`，
 * 新增枚举项时那里的 `when` 会缺分支，编译器会提醒补上。
 */
enum class DataError {

    /** 考试指向的课程已不存在。 */
    COURSE_NOT_FOUND,

    /** 没有可容纳学期的课表（新装应用应先创建课表）。 */
    NO_TIMETABLE,

    /** 课表名称为空。 */
    TIMETABLE_NAME_EMPTY,
}

/** 带原因码的数据层异常，便于界面层还原成可翻译的提示。 */
class DataException(val error: DataError) : IllegalStateException(error.name)
