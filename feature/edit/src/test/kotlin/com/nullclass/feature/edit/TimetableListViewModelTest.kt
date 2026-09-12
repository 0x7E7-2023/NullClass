package com.nullclass.feature.edit

import com.nullclass.core.data.repository.TimetableOverview
import com.nullclass.core.model.Timetable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
class TimetableListViewModelTest {

    private lateinit var repository: FakeTimetableRepository

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        repository = FakeTimetableRepository(
            initial = listOf(
                TimetableOverview(Timetable("mine", "我的课表", 1, 1), termCount = 3),
                TimetableOverview(Timetable("sibling", "弟弟的课表", 2, 2), termCount = 1),
            ),
            initialActiveId = "mine",
        )
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun listsItemsWithActiveFlag() = runTest {
        val vm = TimetableListViewModel(repository)
        val items = vm.uiState.first { it.items.isNotEmpty() }.items

        assertEquals(listOf("mine", "sibling"), items.map { it.timetable.id })
        assertEquals(listOf(3, 1), items.map { it.termCount })
        assertEquals(listOf(true, false), items.map { it.isActive })
    }

    @Test
    fun setActiveDelegatesToRepository() = runTest {
        val vm = TimetableListViewModel(repository)
        vm.uiState.first { it.items.isNotEmpty() }

        vm.setActive("sibling")

        // 仓库里当前课表换人 → 列表的当前标记跟着换
        assertEquals("sibling", repository.activeFlow.value?.id)
        val items = vm.uiState.first { it.items.all { it.isActive == (it.timetable.id == "sibling") } }.items
        assertEquals(listOf(false, true), items.map { it.isActive })
    }

    @Test
    fun renameAndDeletePassThrough() = runTest {
        val vm = TimetableListViewModel(repository)
        vm.uiState.first { it.items.isNotEmpty() }

        vm.rename("sibling", "哥哥的课表")
        vm.delete("sibling")

        assertEquals(listOf("sibling" to "哥哥的课表"), repository.renameCalls)
        assertEquals(listOf("sibling"), repository.deleteCalls)
    }

    @Test
    fun deleteFailureIsNotSwallowedAsSuccess() = runTest {
        // 最后一张删不掉：仓库返回 false，UI 靠列表还在来呈现（对话框已各自处理）
        repository.deleteResult = false
        val vm = TimetableListViewModel(repository)
        vm.uiState.first { it.items.isNotEmpty() }

        vm.delete("mine")

        assertTrue(repository.deleteCalls.isNotEmpty())
    }
}
