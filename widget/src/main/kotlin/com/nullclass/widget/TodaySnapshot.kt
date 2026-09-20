package com.nullclass.widget

import com.nullclass.core.data.repository.CourseRepository
import com.nullclass.core.data.repository.DayOverrideRepository
import com.nullclass.core.data.repository.TermRepository
import com.nullclass.core.model.TodaySnapshot
import com.nullclass.core.model.assembleTodaySnapshot
import kotlinx.coroutines.flow.first
import java.time.LocalDate

/** 从仓库取数并组装今日快照（provideGlance 调用；一次性读，不 collect）。判定逻辑在 core:model。 */
suspend fun buildTodaySnapshot(
    termRepository: TermRepository,
    courseRepository: CourseRepository,
    dayOverrideRepository: DayOverrideRepository,
): TodaySnapshot {
    val term = termRepository.getCurrent() ?: return TodaySnapshot.EMPTY
    val schedule = courseRepository.observeSchedule(term.id).first()
    val times = termRepository.getPeriodTimes(term.id)
    return assembleTodaySnapshot(
        term = term,
        schedule = schedule,
        periodTimes = times,
        today = LocalDate.now(),
        // 串课（调休）：小组件与今日页必须取同一天的课，口径全在 core:model
        dayOverrides = dayOverrideRepository.indexNow(),
    )
}
