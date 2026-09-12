package com.nullclass.importer.jw

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * 脚本提问参数的解码与校验。
 *
 * 这些参数**来自第三方脚本**，所以每条限制都既是对适配器作者的提示，
 * 也是宿主侧的防线（标题 16M 字符、5 万个选项这种输入不能进到弹窗里）。
 */
class JwAskCodecTest {

    @Test
    fun `select 正常解析并去掉标题空白`() {
        val ask = JwAskCodec.decode(
            JwAskCodec.TYPE_SELECT,
            """{"title":" 请选择学期 ","message":"选一个","items":["A","B"],"defaultIndex":1}""",
        ) as JwAskRequest.Select
        assertEquals("请选择学期", ask.title)
        assertEquals("选一个", ask.message)
        assertEquals(listOf("A", "B"), ask.items)
        assertEquals(1, ask.defaultIndex)
    }

    @Test
    fun `select 的 defaultIndex 越界只夹取不报错`() {
        // 越界的默认项只是「选不中」，不该把整次提问判死 —— 弹窗照样能弹，用户自己选。
        val ask = JwAskCodec.decode(
            JwAskCodec.TYPE_SELECT,
            """{"title":"t","items":["A","B"],"defaultIndex":99}""",
        ) as JwAskRequest.Select
        assertEquals(1, ask.defaultIndex)

        val negative = JwAskCodec.decode(
            JwAskCodec.TYPE_SELECT,
            """{"title":"t","items":["A","B"],"defaultIndex":-5}""",
        ) as JwAskRequest.Select
        assertEquals(0, negative.defaultIndex)
    }

    @Test
    fun `select 没有选项时报错`() {
        val error = assertFailsWith<JwAskException> {
            JwAskCodec.decode(JwAskCodec.TYPE_SELECT, """{"title":"t","items":[]}""")
        }
        assertEquals(true, error.message?.contains("items 不能为空"))
    }

    @Test
    fun `select 选项过多或过长都拦下`() {
        val many = (1..JwAskLimits.MAX_ITEMS + 1).joinToString(",") { "\"i$it\"" }
        assertFailsWith<JwAskException> {
            JwAskCodec.decode(JwAskCodec.TYPE_SELECT, """{"title":"t","items":[$many]}""")
        }
        val long = "x".repeat(JwAskLimits.MAX_ITEM_TEXT + 1)
        assertFailsWith<JwAskException> {
            JwAskCodec.decode(JwAskCodec.TYPE_SELECT, """{"title":"t","items":["$long"]}""")
        }
    }

    @Test
    fun `标题必填且有长度上限`() {
        assertFailsWith<JwAskException> { JwAskCodec.decode(JwAskCodec.TYPE_SELECT, """{"title":"","items":["A"]}""") }
        assertFailsWith<JwAskException> { JwAskCodec.decode(JwAskCodec.TYPE_CONFIRM, """{"title":"   "}""") }
        val long = "题".repeat(JwAskLimits.MAX_TITLE + 1)
        assertFailsWith<JwAskException> { JwAskCodec.decode(JwAskCodec.TYPE_CONFIRM, """{"title":"$long"}""") }
    }

    @Test
    fun `说明超长拦下`() {
        val long = "说".repeat(JwAskLimits.MAX_MESSAGE + 1)
        assertFailsWith<JwAskException> {
            JwAskCodec.decode(JwAskCodec.TYPE_CONFIRM, """{"title":"t","message":"$long"}""")
        }
    }

    @Test
    fun `confirm 的按钮文案可以省略`() {
        val ask = JwAskCodec.decode(JwAskCodec.TYPE_CONFIRM, """{"title":"开始导入？"}""") as JwAskRequest.Confirm
        assertNull(ask.confirmText)
        assertNull(ask.cancelText)
        assertNull(ask.message)
    }

    @Test
    fun `confirm 的空按钮文案当作没给`() {
        val ask = JwAskCodec.decode(
            JwAskCodec.TYPE_CONFIRM,
            """{"title":"t","confirmText":"  ","cancelText":"不要"}""",
        ) as JwAskRequest.Confirm
        assertNull(ask.confirmText)
        assertEquals("不要", ask.cancelText)
    }

    @Test
    fun `prompt 有默认长度上限且 maxLength 被限制在合法区间`() {
        val ask = JwAskCodec.decode(JwAskCodec.TYPE_PROMPT, """{"title":"学年"}""") as JwAskRequest.Prompt
        assertEquals(JwAskLimits.DEFAULT_PROMPT_LENGTH, ask.maxLength)
        assertEquals("", ask.defaultText)

        for (bad in listOf(0, -1, JwAskLimits.MAX_PROMPT_LENGTH + 1)) {
            assertFailsWith<JwAskException>("maxLength=$bad 应当被拒绝") {
                JwAskCodec.decode(JwAskCodec.TYPE_PROMPT, """{"title":"t","maxLength":$bad}""")
            }
        }
    }

    @Test
    fun `prompt 的默认文本超长拦下`() {
        val long = "x".repeat(JwAskLimits.MAX_DEFAULT_TEXT + 1)
        assertFailsWith<JwAskException> {
            JwAskCodec.decode(JwAskCodec.TYPE_PROMPT, """{"title":"t","defaultText":"$long"}""")
        }
    }

    @Test
    fun `不认识的类型与坏 JSON 都给出可读错误`() {
        assertFailsWith<JwAskException> { JwAskCodec.decode("askSomething", """{}""") }
        assertFailsWith<JwAskException> { JwAskCodec.decode(JwAskCodec.TYPE_CONFIRM, "not json") }
        assertFailsWith<JwAskException> { JwAskCodec.decode(JwAskCodec.TYPE_CONFIRM, null) }
        assertFailsWith<JwAskException> { JwAskCodec.decode(JwAskCodec.TYPE_CONFIRM, "   ") }
    }

    @Test
    fun `未知字段被忽略（向前兼容）`() {
        val ask = JwAskCodec.decode(
            JwAskCodec.TYPE_CONFIRM,
            """{"title":"t","futureField":123,"nested":{"a":1}}""",
        ) as JwAskRequest.Confirm
        assertEquals("t", ask.title)
    }
}
