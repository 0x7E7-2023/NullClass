package com.nullclass.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.nullclass.core.data.repository.CourseRepository
import com.nullclass.core.data.repository.DayOverrideRepository
import com.nullclass.core.data.repository.TermRepository
import com.nullclass.core.model.TodaySnapshot
import com.nullclass.core.model.assembleTodaySnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.LocalDateTime

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

/** 今日快照与当前时刻，小组件每一帧都按这一对渲染。 */
internal data class LiveToday(val snapshot: TodaySnapshot, val now: LocalDateTime)

/**
 * Glance 会话存活期间（每次更新后约 45 秒）让画面跟着数据库和时钟走。
 *
 * 会话还活着时，updateAll 只会给会话发一个「状态变了」的事件，provideGlance 不会重跑；
 * 而页码存档没变时 currentState 取到的值与上次相等，内容也不会重组。
 * 此前快照只在 provideGlance 读一次，于是连续两次改课（间隔不到一个会话）时，
 * 第二次改动要等到下一个课节切换才会出现在桌面上，分钟倒计时也停在会话开始那一刻。
 *
 * 首帧用 provideGlance 一次性读出的值，保证第一帧就是对的。
 */
@Composable
internal fun liveToday(
    entryPoint: ScheduleWidgetEntryPoint,
    initialSnapshot: TodaySnapshot,
    initialNow: LocalDateTime,
): LiveToday {
    val now by remember { minuteTicks() }.collectAsState(initialNow)
    val snapshot by remember { observeTodaySnapshot(entryPoint) }.collectAsState(initialSnapshot)
    return LiveToday(snapshot, now)
}

/** 立即发一次当前时刻，之后每逢整分钟再发。 */
private fun minuteTicks(): Flow<LocalDateTime> = flow {
    while (true) {
        val now = LocalDateTime.now()
        emit(now)
        delay(60_000L - now.second * 1_000L - now.nano / 1_000_000L)
    }
}

/** 与 [buildTodaySnapshot] 同一口径，只是持续观察；跨过零点时换成新一天的课。 */
@OptIn(ExperimentalCoroutinesApi::class)
private fun observeTodaySnapshot(entryPoint: ScheduleWidgetEntryPoint): Flow<TodaySnapshot> {
    val termRepository = entryPoint.termRepository()
    val dates = minuteTicks().map { it.toLocalDate() }.distinctUntilChanged()
    return termRepository.observeCurrent().flatMapLatest { term ->
        if (term == null) {
            flowOf(TodaySnapshot.EMPTY)
        } else {
            combine(
                entryPoint.courseRepository().observeSchedule(term.id),
                termRepository.observePeriodTimes(term.id),
                entryPoint.dayOverrideRepository().index,
                dates,
            ) { schedule, times, overrides, today ->
                assembleTodaySnapshot(
                    term = term,
                    schedule = schedule,
                    periodTimes = times,
                    today = today,
                    dayOverrides = overrides,
                )
            }
        }
    }.distinctUntilChanged()
}
