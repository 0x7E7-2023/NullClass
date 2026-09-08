package com.nullclass.core.model

/**
 * 默认节次模板：12 小节 = 6 大节，上午/下午/晚上各 4 节。
 * 每节 45 分钟；同会话内课间 10 分钟，第三节课前 20 分钟大课间。
 * 用户可在学期编辑页整体替换。
 *
 * 放在 `:core:model`（纯 JVM）而非 `:core:data`，因为 `:importer` 的教务适配器
 * 与 WakeUp 迁移都需要同一张表，而 `:importer` 不能依赖 Android 模块。
 */
object DefaultPeriodTimes {

    private data class Slot(val start: Int, val end: Int, val session: Int)

    private val template = listOf(
        // 上午 8:00-11:40
        Slot(8 * 60, 8 * 60 + 45, Session.MORNING),        // 1
        Slot(8 * 60 + 55, 8 * 60 + 100, Session.MORNING),  // 2
        Slot(10 * 60, 10 * 60 + 45, Session.MORNING),      // 3
        Slot(10 * 60 + 55, 10 * 60 + 100, Session.MORNING), // 4
        // 下午 14:00-17:40
        Slot(14 * 60, 14 * 60 + 45, Session.AFTERNOON),    // 5
        Slot(14 * 60 + 55, 14 * 60 + 100, Session.AFTERNOON), // 6
        Slot(16 * 60, 16 * 60 + 45, Session.AFTERNOON),    // 7
        Slot(16 * 60 + 55, 16 * 60 + 100, Session.AFTERNOON), // 8
        // 晚上 18:30-22:00
        Slot(18 * 60 + 30, 18 * 60 + 75, Session.EVENING), // 9
        Slot(18 * 60 + 85, 18 * 60 + 130, Session.EVENING), // 10
        Slot(20 * 60 + 20, 20 * 60 + 65, Session.EVENING), // 11
        Slot(20 * 60 + 75, 20 * 60 + 120, Session.EVENING), // 12
    )

    /** 节次总数（默认模板）。 */
    val periodCount: Int get() = template.size

    /** 为指定学期生成默认节次时间。 */
    fun create(termId: String): List<PeriodTime> =
        template.mapIndexed { index, slot ->
            PeriodTime(
                termId = termId,
                periodIndex = index + 1,
                startMinuteOfDay = slot.start,
                endMinuteOfDay = slot.end,
                session = slot.session,
            )
        }
}
