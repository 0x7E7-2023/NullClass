package com.nullclass.feature.edit

import com.nullclass.core.model.DefaultPeriodTimes
import com.nullclass.core.model.Term
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TimetableCreateViewModelTest {

    private lateinit var timetables: FakeTimetableRepository

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        timetables = FakeTimetableRepository()
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun blankTimetableNameIsRejected() = runTest {
        val vm = TimetableCreateViewModel(timetables)
        vm.state.first { it.firstDayEpochDay > 0 } // 等默认值算好

        vm.setTimetableName("  ")
        vm.save {}

        assertNull(timetables.createCalls.singleOrNull())
        assertEquals("课表名不能为空", vm.state.value.error)
    }

    @Test
    fun blankTermNameIsRejected() = runTest {
        val vm = TimetableCreateViewModel(timetables)
        vm.state.first { it.firstDayEpochDay > 0 }

        vm.setTermName("")
        vm.save {}

        assertNull(timetables.createCalls.singleOrNull())
        assertEquals("学期名不能为空", vm.state.value.error)
    }

    @Test
    fun saveCreatesTimetableAndFirstTermTogether() = runTest {
        val vm = TimetableCreateViewModel(timetables)
        vm.state.first { it.firstDayEpochDay > 0 }
        vm.setTermName("2026 秋")
        vm.setTotalWeeks(18)

        var saved = false
        vm.save { saved = true }

        assertTrue(saved)
        val (name, term, periods) = timetables.createCalls.single()
        assertEquals("我的课表", name)
        assertEquals("2026 秋", term.name)
        assertEquals(18, term.totalWeeks)
        assertEquals(vm.state.value.firstDayEpochDay, term.firstDayEpochDay)
        // 默认节次模板整套写入
        assertEquals(DefaultPeriodTimes.create("x").size, periods.size)
        // 成功后保持 saving（等待页面离开），连点不会重复建两份
        assertTrue(vm.state.value.saving)
    }

    @Test
    fun failedCreationAllowsRetryWithoutHalfState() = runTest {
        // 事务失败 = 课表和学期都没落库，重试干净（不会多出一张同名课表）
        timetables.createWithFirstTermHook = { throw IllegalStateException("disk") }
        val vm = TimetableCreateViewModel(timetables)
        vm.state.first { it.firstDayEpochDay > 0 }

        vm.save {}
        assertEquals(false, vm.state.value.saving, "失败要允许重试")
        assertEquals(1, timetables.createCalls.size)

        timetables.createWithFirstTermHook = null
        vm.save {}
        assertEquals(2, timetables.createCalls.size, "重试会重新走一次完整事务")
        assertTrue(timetables.overviewsFlow.value.isNotEmpty())
    }

    @Test
    fun totalWeeksIsClampedToValidRange() = runTest {
        val vm = TimetableCreateViewModel(timetables)
        vm.state.first { it.firstDayEpochDay > 0 }

        vm.setTotalWeeks(99)
        assertEquals(25, vm.state.value.totalWeeks)
        vm.setTotalWeeks(0)
        assertEquals(1, vm.state.value.totalWeeks)
    }
}
