package com.nullclass.core.ui.i18n

import android.content.Context
import com.nullclass.core.ui.R
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "zh-rCN")
class UiTextTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun `数量词条不传参数时以数量代入`() {
        assertEquals("3 天后", UiText.Plural(R.plurals.fmt_exam_relative_in_days, 3).resolve(context))
    }

    @Test
    fun `数量词条可以另传参数`() {
        assertEquals("共 1 周", UiText.Plural(R.plurals.fmt_total_weeks, 1, 1).resolve(context))
    }

    @Test
    fun `连接时分隔符随词条`() {
        val joined = UiText.Joined(listOf("甲", UiText.Res(R.string.fmt_week_type_odd), "丙"), R.string.common_list_separator)
        assertEquals("甲、单周、丙", joined.resolve(context))
    }

    @Test
    fun `中文句间不加分隔符`() {
        val joined = UiText.Joined(listOf("第一句。", "第二句。"), R.string.common_sentence_separator)
        assertEquals("第一句。第二句。", joined.resolve(context))
    }

    @Test
    fun `词条参数可以嵌套`() {
        val nested = UiText.Res(R.string.fmt_remaining_hours_minutes, UiText.Plural(R.plurals.fmt_remaining_hours, 1), "20 分钟")
        assertEquals("1 小时 20 分钟", nested.resolve(context))
    }
}
