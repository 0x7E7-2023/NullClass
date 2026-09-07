package com.nullclass.widget

import com.nullclass.core.data.repository.CourseRepository
import com.nullclass.core.data.repository.TermRepository
import com.nullclass.core.model.TodaySnapshot
import com.nullclass.core.model.assembleTodaySnapshot
import kotlinx.coroutines.flow.first
import java.time.LocalDate

/** 从仓库取数并组装今日快照（provideGlance 调用；一次性读，不 collect）。判定逻辑在 core:model。 */
suspend fun buildTodaySnapshot(
    termRepository: TermRepository,
    courseRepository: CourseRepository,
): TodaySnapshot {
    val term = termRepository.getCurrent() ?: return TodaySnapshot.EMPTY
    val schedule = courseRepository.observeSchedule(term.id).first()
    val times = termRepository.getPeriodTimes(term.id)
    return assembleTodaySnapshot(term, schedule, times, LocalDate.now())
}
