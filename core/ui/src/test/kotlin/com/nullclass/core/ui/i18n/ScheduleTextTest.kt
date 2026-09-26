package com.nullclass.core.ui.i18n

import android.content.Context
import com.nullclass.core.model.ScheduleBlock
import com.nullclass.core.model.WeekType
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals

/** 课表共用文案：断言走真实 strings.xml，资源写坏（比如分隔符的空格被 aapt 吃掉）会在这里暴露。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "zh-rCN")
class ScheduleTextTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun block(
        startWeek: Int,
        endWeek: Int,
        weekType: WeekType = WeekType.ALL,
        dayOfWeek: Int = 2,
        startPeriod: Int = 3,
        endPeriod: Int = 4,
        location: String? = null,
    ) = ScheduleBlock(
        startWeek = startWeek,
        endWeek = endWeek,
        weekType = weekType,
        dayOfWeek = dayOfWeek,
        startPeriod = startPeriod,
        endPeriod = endPeriod,
        location = location,
    )

    @Test
    fun `完整一行用两侧带空格的分隔符`() {
        assertEquals(
            "第1-16周 · 单周 · 周二 · 3-4节 · A101",
            ScheduleText.blockSummary(context, block(1, 16, WeekType.ODD, location = "A101")),
        )
        assertEquals("第3周 · 周二 · 3-4节", ScheduleText.blockSummary(context, block(3, 3)))
    }

    @Test
    fun `紧凑周次标出范围与单双周`() {
        // 周视图灰块那一格只有几十 dp 宽，不能带「第」（会被省略号吃掉尾巴）
        assertEquals("1-16周", ScheduleText.weekSpan(context, block(1, 16)))
        assertEquals("3周", ScheduleText.weekSpan(context, block(3, 3)))
        assertEquals("1-16周·单", ScheduleText.weekSpan(context, block(1, 16, WeekType.ODD)))
        assertEquals("2-6周·双", ScheduleText.weekSpan(context, block(2, 6, WeekType.EVEN)))
    }

    @Test
    fun `节次与星期`() {
        assertEquals("3-4节", ScheduleText.periodRange(context, block(1, 1)))
        assertEquals("5节", ScheduleText.periodRange(context, block(1, 1, startPeriod = 5, endPeriod = 5)))
        assertEquals("周日", ScheduleText.dayOfWeek(context, 7))
        assertEquals("日", ScheduleText.dayOfWeekShort(context, 7))
        assertEquals("?", ScheduleText.dayOfWeek(context, 8))
    }

    @Test
    fun `倒计时时长`() {
        assertEquals("45 分钟", ScheduleText.remaining(context, 45))
        assertEquals("1 小时", ScheduleText.remaining(context, 60))
        assertEquals("1 小时 20 分钟", ScheduleText.remaining(context, 80))
    }

    @Test
    fun `学期总周数`() {
        assertEquals("共 18 周", ScheduleText.totalWeeks(context, 18))
    }
}
