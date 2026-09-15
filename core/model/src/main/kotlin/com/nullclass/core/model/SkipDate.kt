package com.nullclass.core.model

/**
 * 一个「这天不上课」的日期。来源两种：用户手动添加，或节假日同步。
 * [SkipDateType.WORKDAY] 是调休补班日（周末要上课），仅展示提示，
 * 不参与任何「跳过」逻辑。
 */
data class SkipDate(
    val epochDay: Long,
    val type: SkipDateType,
    /** 展示名（如「国庆节」）；手动添加为 null。 */
    val label: String?,
)

enum class SkipDateType {
    /** 用户手动添加的跳过日期。 */
    MANUAL,

    /** 节假日（放假，不上课）。 */
    HOLIDAY,

    /** 调休补班日（周末要上课）。仅展示，不生成课程也不跳过提醒。 */
    WORKDAY,
}
