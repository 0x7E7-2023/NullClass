package com.nullclass.feature.edit

import com.nullclass.core.data.repository.TermRepository
import com.nullclass.core.model.PeriodTime
import com.nullclass.core.model.Term
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TermListViewModelTest {

    private val older = Term(id = "old", name = "2025-2026-2", firstDayEpochDay = 0, totalWeeks = 18)
    private val newer = Term(id = "new", name = "2026-2027-1", firstDayEpochDay = 140, totalWeeks = 20)

    private lateinit var repository: FakeListTermRepository
    private lateinit var timetableRepository: FakeTimetableRepository

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        repository = FakeListTermRepository(listOf(newer, older), currentId = newer.id)
        timetableRepository = FakeTimetableRepository()
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun marksCurrentTerm() = runTest {
        val vm = TermListViewModel(repository, timetableRepository)
        val items = vm.uiState.first { it.items.isNotEmpty() }.items
        assertEquals(listOf("new", "old"), items.map { it.term.id })
        assertEquals(listOf(true, false), items.map { it.isCurrent })
    }

    @Test
    fun setCurrentSkipsAlreadyCurrent() = runTest {
        val vm = TermListViewModel(repository, timetableRepository)
        vm.uiState.first { it.items.isNotEmpty() }
        vm.setCurrent(newer.id)
        assertTrue(repository.setCurrentCalls.isEmpty())
    }

    @Test
    fun setCurrentSwitches() = runTest {
        val vm = TermListViewModel(repository, timetableRepository)
        vm.uiState.first { it.items.isNotEmpty() }
        vm.setCurrent(older.id)
        val items = vm.uiState.first { state -> state.items.any { it.isCurrent && it.term.id == older.id } }.items
        assertEquals(listOf("old"), repository.setCurrentCalls)
        assertEquals(listOf(false, true), items.map { it.isCurrent })
    }
}

private class FakeListTermRepository(
    terms: List<Term>,
    currentId: String?,
) : TermRepository {

    private val termsFlow = MutableStateFlow(terms)
    private val currentFlow = MutableStateFlow(terms.firstOrNull { it.id == currentId })
    val setCurrentCalls = mutableListOf<String>()

    override fun observeAll(): Flow<List<Term>> = termsFlow

    override fun observeCurrent(): Flow<Term?> = currentFlow

    override suspend fun getCurrent(): Term? = currentFlow.value

    override suspend fun getById(termId: String): Term? = termsFlow.value.firstOrNull { it.id == termId }

    override suspend fun getPreviousTerm(): Term? = null

    override suspend fun upsert(term: Term, periodTimes: List<PeriodTime>): String = term.id

    override suspend fun setCurrent(termId: String) {
        setCurrentCalls += termId
        currentFlow.value = termsFlow.value.firstOrNull { it.id == termId }
    }

    override fun observePeriodTimes(termId: String): Flow<List<PeriodTime>> = MutableStateFlow(emptyList())

    override suspend fun getPeriodTimes(termId: String): List<PeriodTime> = emptyList()

    override suspend fun deleteTerm(termId: String) = Unit
}
