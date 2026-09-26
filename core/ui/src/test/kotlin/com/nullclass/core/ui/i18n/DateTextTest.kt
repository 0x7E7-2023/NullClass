package com.nullclass.core.ui.i18n

import android.content.Context
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "zh-rCN")
class DateTextTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val day = LocalDate.of(2026, 12, 20).toEpochDay()

    @Test
    fun `日期文案`() {
        assertEquals("2026年12月20日", DateText.date(context, day))
        assertEquals("12月20日", DateText.monthDay(context, day))
    }

    @Test
    fun `考试相对日期`() {
        assertEquals("今天", DateText.examRelative(context, day, day))
        assertEquals("明天", DateText.examRelative(context, day + 1, day))
        assertEquals("昨天", DateText.examRelative(context, day - 1, day))
        assertEquals("已结束", DateText.examRelative(context, day - 2, day))
        assertEquals("3 天后", DateText.examRelative(context, day + 3, day))
    }
}
