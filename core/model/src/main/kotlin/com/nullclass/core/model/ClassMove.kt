package com.nullclass.core.model

import java.time.LocalDate

/**
 * 单次调课：课块 [blockId] 在 [fromEpochDay] 那一次挪到 [toEpochDay] 的第 [toStartPeriod]..[toEndPeriod] 节。
 *
 * 和整天串课（[DayOverride]）是两件事：串课换的是「这天上哪天的课」，这里只动某一次课，
 * 原位置这一次空出来，目标位置多出这一次，其余周照旧。
 *
 * [fromEpochDay] 是这次课**实际出现**的日期（串课解析之后），也就是用户在今日页 / 周视图上看到它的那天。
 */
data class ClassMove(
    val id: Long = 0,
    val blockId: String,
    val fromEpochDay: Long,
    val toEpochDay: Long,
    val toStartPeriod: Int,
    val toEndPeriod: Int,
) {
    init {
        require(toStartPeriod >= 1 && toStartPeriod <= toEndPeriod) {
            "invalid period range: $toStartPeriod..$toEndPeriod"
        }
    }
}

/** 单次调课的解析规则；今日页、周视图、小组件、提醒共用，四处算出同一份课。 */
object ClassMoves {

    /** 去掉 [epochDay] 这天被挪走的那几次课。 */
    fun withoutMovedAway(blocks: List<PlacedBlock>, moves: List<ClassMove>, epochDay: Long): List<PlacedBlock> {
        val away = moves.filter { it.fromEpochDay == epochDay }.mapTo(HashSet()) { it.blockId }
        return if (away.isEmpty()) blocks else blocks.filterNot { it.block.id in away }
    }

    /**
     * 挪进 [epochDay] 这天的课：原课块复制一份，换成目标日的星期与节次、只在目标周上。
     * 目标日不在学期内的不算（同串课口径：学期外不凭空造课）；原课块已被删的记录静默忽略。
     */
    fun movedIn(term: Term, schedule: List<CourseWithBlocks>, moves: List<ClassMove>, epochDay: Long): List<PlacedBlock> {
        val incoming = moves.filter { it.toEpochDay == epochDay }
        if (incoming.isEmpty()) return emptyList()
        val week = term.weekOf(epochDay) ?: return emptyList()
        val dayOfWeek = LocalDate.ofEpochDay(epochDay).dayOfWeek.value
        return incoming.mapNotNull { move ->
            schedule.firstNotNullOfOrNull { cwb ->
                cwb.blocks.firstOrNull { it.id == move.blockId }?.let { block ->
                    PlacedBlock(
                        cwb.course,
                        block.copy(
                            startWeek = week,
                            endWeek = week,
                            weekType = WeekType.ALL,
                            dayOfWeek = dayOfWeek,
                            startPeriod = move.toStartPeriod,
                            endPeriod = move.toEndPeriod,
                        ),
                    )
                }
            }
        }
    }

    /** [epochDay] 这天的课块套上单次调课：先去掉挪走的，再加上挪进来的。 */
    fun apply(
        term: Term,
        schedule: List<CourseWithBlocks>,
        moves: List<ClassMove>,
        epochDay: Long,
        blocks: List<PlacedBlock>,
    ): List<PlacedBlock> =
        if (moves.isEmpty()) blocks else withoutMovedAway(blocks, moves, epochDay) + movedIn(term, schedule, moves, epochDay)
}
