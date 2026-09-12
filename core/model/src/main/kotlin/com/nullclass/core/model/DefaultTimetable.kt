package com.nullclass.core.model

/**
 * 迁移与同步兜底共用的「默认课表」。
 *
 * - v2→v3 迁移：存量学期全部归到这张固定 UUID 的课表——两台设备各自升级后
 *   收敛到同一条记录，而不是同步出两张空的同名课表（`UUID.randomUUID` 的版本位是 4，
 *   全零 id 永远不会撞上它）
 * - 同步合并：极边角（旧版本快照直接落到一个还没有任何课表的库）时造一张兜住，
 *   否则里面的学期在界面上无家可归
 */
object DefaultTimetable {
    const val ID = "00000000-0000-0000-0000-000000000001"
    const val NAME = "我的课表"
}
