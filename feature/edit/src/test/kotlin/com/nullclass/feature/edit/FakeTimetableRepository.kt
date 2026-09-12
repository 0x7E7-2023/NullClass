package com.nullclass.feature.edit

import com.nullclass.core.data.repository.TimetableOverview
import com.nullclass.core.data.repository.TimetableRepository
import com.nullclass.core.model.PeriodTime
import com.nullclass.core.model.Term
import com.nullclass.core.model.Timetable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** 课表仓库的可编程替身：列表/当前课表可推流，写操作全部记录。 */
internal class FakeTimetableRepository(
    initial: List<TimetableOverview> = emptyList(),
    initialActiveId: String? = null,
) : TimetableRepository {

    val overviewsFlow = MutableStateFlow(initial)
    val activeFlow = MutableStateFlow(
        initialActiveId?.let { id -> initial.firstOrNull { it.timetable.id == id }?.timetable },
    )
    val createCalls = mutableListOf<Triple<String, Term, List<PeriodTime>>>()
    val renameCalls = mutableListOf<Pair<String, String>>()
    val deleteCalls = mutableListOf<String>()
    var deleteResult = true

    /** 非空时 createWithFirstTerm 抛它（模拟事务失败）。 */
    var createWithFirstTermHook: (suspend () -> Unit)? = null

    override fun observeOverviews(): Flow<List<TimetableOverview>> = overviewsFlow

    override fun observeActive(): Flow<Timetable?> = activeFlow

    override suspend fun getActiveId(): String? = activeFlow.value?.id

    override suspend fun getById(id: String): Timetable? =
        overviewsFlow.value.firstOrNull { it.timetable.id == id }?.timetable

    override suspend fun setActive(id: String) {
        activeFlow.value = getById(id)
    }

    override suspend fun createWithFirstTerm(
        timetableName: String,
        term: Term,
        periodTimes: List<PeriodTime>,
    ): String {
        createCalls += Triple(timetableName, term, periodTimes)
        createWithFirstTermHook?.invoke()
        val created = Timetable(id = "tt-${createCalls.size}", name = timetableName)
        overviewsFlow.value = overviewsFlow.value + TimetableOverview(created, termCount = 1)
        activeFlow.value = created
        return created.id
    }

    override suspend fun rename(id: String, name: String) {
        renameCalls += id to name
    }

    override suspend fun delete(id: String): Boolean {
        deleteCalls += id
        return deleteResult
    }
}
