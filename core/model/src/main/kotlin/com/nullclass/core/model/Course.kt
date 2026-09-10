package com.nullclass.core.model

/**
 * 一门课。同一门课可以有多个时间安排（见 [ScheduleBlock]），
 * 例如每周二 1-2 节在 A101、每周四 3-4 节在实验楼。
 */
data class Course(
    val id: String = "",
    val termId: String = "",
    val name: String,
    val teacher: String? = null,
    /** 备注（教材、考核方式等），UI 展示用 */
    val note: String? = null,
    /** 预设色板下标，由 UI 层统一映射为颜色；教务导入按课名关键词写入，可手改 */
    val colorIndex: Int = 0,
)
