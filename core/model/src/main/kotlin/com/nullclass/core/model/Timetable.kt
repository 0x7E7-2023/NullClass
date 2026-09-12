package com.nullclass.core.model

/**
 * 课表：学期/课程/节次时间的顶层容器。
 *
 * 学期（[Term]）归属某张课表，课程与节次时间又挂在学期下——所以「不同课表的
 * 课程、时间安排相互独立」是结构性成立的，不靠查询过滤维持。
 * 「当前课表」是**本地**选择（DataStore），不进同步：两台设备各看各的课表。
 */
data class Timetable(
    val id: String,
    val name: String,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
)
