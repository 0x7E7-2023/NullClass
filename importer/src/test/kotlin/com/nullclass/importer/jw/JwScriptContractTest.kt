package com.nullclass.importer.jw

import org.mozilla.javascript.Context
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JwScriptContractTest {

    private fun runner(ocrEnabled: Boolean = true): String = JwScriptContract.buildRunner(
        script = "(function(){ return '{}'; })()",
        inputJson = """{"a":1}""",
        allowedHosts = listOf("jw.example.edu.cn"),
        ocrEnabled = ocrEnabled,
    )

    /** 生成的 JS 必须是**语法合法**的（曾经把字符串字面量拼进属性位置，整段脚本挂掉）。 */
    @Test
    fun `runner 与 poll 脚本语法合法`() {
        listOf(runner(ocrEnabled = true), runner(ocrEnabled = false), JwScriptContract.buildPollScript())
            .forEachIndexed { index, source ->
                val context = Context.enter()
                try {
                    context.languageVersion = Context.VERSION_ES6
                    context.compileString(source, "contract-$index", 1, null)
                } finally {
                    Context.exit()
                }
            }
    }

    @Test
    fun `OCR 桥用下标访问而不是属性位置的字面量`() {
        val js = runner(ocrEnabled = true)
        assertTrue(js.contains("""window["ncBridge"]"""), "桥对象必须用 window[...] 访问")
        assertFalse(js.contains("""window.""""), "出现了 window.\"…\" 这种非法属性访问")
    }

    @Test
    fun `能力位只在桥真的注入时才为 true`() {
        // origin 规则写错（例如漏端口）时桥不会注入，此时 __ncCapabilities.ocr 必须是 false，
        // 否则适配器会拿到「能力位说可用、实际 window.ncBridge 是 undefined」的迷惑错误。
        val withBridge = runner(ocrEnabled = true)
        assertTrue(
            withBridge.contains("""typeof window["ncBridge"] !== 'undefined'"""),
            "能力位必须回查桥对象是否真的存在",
        )
        val withoutBridge = runner(ocrEnabled = false)
        assertFalse(
            withoutBridge.contains("window.__ncOcr = function"),
            "未启用 OCR 时不应注入桥实现",
        )
    }

    @Test
    fun `OCR 桥回传的 JSON 字符串必须解析成对象再 resolve`() {
        // 规范 §5 里适配器拿到的是对象（r.boxes / g.cells），
        // 宿主 buildOcrReplyScript 传的是 JSON 字符串，忘了 JSON.parse 就会让
        // 所有按规范写的适配器拿到 undefined。
        val js = runner(ocrEnabled = true)
        assertTrue(js.contains("entry.resolve(JSON.parse(payload))"), "回传值必须解析成对象")
    }

    @Test
    fun `__ncError 是函数 错误写在独立槽位`() {
        val js = runner()
        assertTrue(js.contains("window.__ncError = function"), "__ncError 必须是可调用的函数")
        assertTrue(js.contains("window.__ncErrorText"), "错误内容要写在 __ncErrorText")
        assertTrue(JwScriptContract.buildPollScript().contains("__ncErrorText"))
    }

    @Test
    fun `字符串字面量转义覆盖引号 反斜杠 换行与行分隔符`() {
        assertEquals("\"a\\\"b\"", JwScriptContract.jsStringLiteral("a\"b"))
        assertEquals("\"a\\\\b\"", JwScriptContract.jsStringLiteral("a\\b"))
        assertEquals("\"a\\nb\"", JwScriptContract.jsStringLiteral("a\nb"))
        assertTrue(JwScriptContract.jsStringLiteral("a\u2028b").contains("\\u2028"))
        assertTrue(JwScriptContract.jsStringLiteral("a\u2029b").contains("\\u2029"))
        assertEquals("\"\\u0001\"", JwScriptContract.jsStringLiteral("\u0001"))
    }

    @Test
    fun `脚本内容被当作字面量而不是拼接进源码`() {
        // 脚本里带引号与换行也不能破坏外层包装
        val js = JwScriptContract.buildRunner(
            script = "(function(){ return \"a\\\"b\"; })()",
            inputJson = null,
            allowedHosts = emptyList(),
            ocrEnabled = false,
        )
        val context = Context.enter()
        try {
            context.languageVersion = Context.VERSION_ES6
            context.compileString(js, "contract", 1, null)
        } finally {
            Context.exit()
        }
    }
}
