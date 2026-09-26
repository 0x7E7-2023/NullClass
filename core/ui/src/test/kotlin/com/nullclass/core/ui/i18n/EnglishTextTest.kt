package com.nullclass.core.ui.i18n

import android.content.Context
import com.nullclass.core.model.ScheduleBlock
import com.nullclass.core.model.WeekType
import com.nullclass.core.ui.R
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

/** 英文译文里中文没有的东西：one/other 两档、带空格的分隔符。断言走真实 values-en。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS")
class EnglishTextTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun `数量词条区分单复数`() {
        assertEquals("In 1 day", UiText.Plural(R.plurals.fmt_exam_relative_in_days, 1).resolve(context))
        assertEquals("In 3 days", UiText.Plural(R.plurals.fmt_exam_relative_in_days, 3).resolve(context))
        assertEquals("1 week total", ScheduleText.totalWeeks(context, 1))
        assertEquals("18 weeks total", ScheduleText.totalWeeks(context, 18))
    }

    @Test
    fun `分隔符两侧的空格没被 aapt 吃掉`() {
        val list = UiText.Joined(listOf("A", "B", "C"), R.string.common_list_separator)
        assertEquals("A, B, C", list.resolve(context))
        val sentences = UiText.Joined(listOf("First.", "Second."), R.string.common_sentence_separator)
        assertEquals("First. Second.", sentences.resolve(context))
        assertEquals(
            "Weeks 1–16 · Odd weeks · Tue · P3–4 · A101",
            ScheduleText.blockSummary(
                context,
                ScheduleBlock(
                    startWeek = 1,
                    endWeek = 16,
                    weekType = WeekType.ODD,
                    dayOfWeek = 2,
                    startPeriod = 3,
                    endPeriod = 4,
                    location = "A101",
                ),
            ),
        )
    }

    @Test
    fun `倒计时与日期`() {
        assertEquals("1 hr 20 min", ScheduleText.remaining(context, 80))
        assertEquals("12/20/2026", DateText.date(context, LocalDate.of(2026, 12, 20).toEpochDay()))
    }
}
