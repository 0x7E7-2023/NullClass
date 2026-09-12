package com.nullclass.importer.jw

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.mozilla.javascript.Context
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JwScriptContractTest {

    private fun runner(ocrEnabled: Boolean = true, askEnabled: Boolean = false): String = JwScriptContract.buildRunner(
        script = "(function(){ return '{}'; })()",
        inputJson = """{"a":1}""",
        allowedHosts = listOf("jw.example.edu.cn"),
        ocrEnabled = ocrEnabled,
        askEnabled = askEnabled,
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
        // 规范 §5.1 里适配器拿到的是对象（r.boxes / g.cells），
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
    fun `沙箱 preamble 含后缀通配且语法合法`() {
        val js = JwScriptContract.buildRunner(
            script = "(function(){ return '{}'; })()",
            inputJson = null,
            allowedHosts = listOf("*.ustc.edu.cn", "jw.ustc.edu.cn"),
            ocrEnabled = false,
        )
        assertTrue(js.contains("pattern.charAt(0) === '*'"), "JS 沙箱必须实现 *.host 通配")
        assertTrue(js.contains("*.ustc.edu.cn"), js)
        val context = Context.enter()
        try {
            context.languageVersion = Context.VERSION_ES6
            context.compileString(js, "wildcard-preamble", 1, null)
        } finally {
            Context.exit()
        }
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

    // ---- 提问桥（规范 §5.2）----

    @Test
    fun `提问桥只在启用时注入 且语法合法`() {
        val enabled = runner(askEnabled = true)
        assertTrue(enabled.contains("window.__ncSelect = function"), "启用了就该定义三个提问全局")
        assertTrue(enabled.contains("window.__ncConfirm = function"))
        assertTrue(enabled.contains("window.__ncPrompt = function"))
        val disabled = runner(askEnabled = false)
        assertFalse(disabled.contains("window.__ncSelect = function"), "没启用就不该注入")

        listOf(enabled, disabled).forEachIndexed { index, source ->
            val context = Context.enter()
            try {
                context.languageVersion = Context.VERSION_ES6
                context.compileString(source, "ask-$index", 1, null)
            } finally {
                Context.exit()
            }
        }
    }

    @Test
    fun `能力位 ask 要求桥对象与三个全局都在`() {
        // 和 OCR 那次踩过的坑同源：只报「宿主开关开了」会撒谎，
        // 适配器拿着 undefined 去调用，然后静默失败。
        val js = runner(askEnabled = true)
        assertTrue(js.contains("""typeof window["ncBridge"] !== 'undefined'"""))
        assertTrue(js.contains("""typeof window.__ncSelect === 'function'"""))
    }

    /**
     * 能力位要**真跑一遍取值**，不能只看源码里有没有那行字。
     *
     * 曾经的写法把 `__ncCapabilities` 的字面量放在提问桥模板**之前**求值：
     * 那一刻 `window.__ncSelect` 还没定义，`ask` 永远算出 false ——
     * 真机上就是「桥明明注入了、脚本却被告知不支持提问」。源码断言查不出这种顺序错误。
     */
    @Test
    fun `能力位在运行期按桥的真实状态取值`() {
        val context = Context.enter()
        try {
            context.languageVersion = Context.VERSION_ES6

            fun capsOf(ocr: Boolean, ask: Boolean, tag: String): Pair<String, String> {
                val scope = context.initStandardObjects()
                context.evaluateString(scope, BRIDGE_STUBS, "stubs-$tag", 1, null)
                context.evaluateString(scope, runner(ocrEnabled = ocr, askEnabled = ask), "runner-$tag", 1, null)
                val askValue = context.evaluateString(scope, "String(window.__ncCapabilities.ask)", "a", 1, null)
                val ocrValue = context.evaluateString(scope, "String(window.__ncCapabilities.ocr)", "o", 1, null)
                return ocrValue as String to askValue as String
            }

            assertEquals("true" to "true", capsOf(ocr = true, ask = true, tag = "on"))
            assertEquals("false" to "false", capsOf(ocr = false, ask = false, tag = "off"))
        } finally {
            Context.exit()
        }
    }

    /**
     * 真跑一遍提问桥（Rhino + 极简 Promise 垫片）：验证
     * 「选项只回传索引」「取消是 null 而不是错误」「confirm 的否是 false」
     * 这些**语义**，而不只是源码里有没有那几行字。
     */
    @Test
    fun `提问桥取值语义：索引 文本 取消 与失败`() {
        val context = Context.enter()
        try {
            context.languageVersion = Context.VERSION_ES6
            val scope = context.initStandardObjects()
            context.evaluateString(scope, BRIDGE_STUBS, "stubs", 1, null)
            context.evaluateString(scope, runner(askEnabled = true), "runner", 1, null)
            val raw = context.evaluateString(scope, PROBE, "probe", 1, null) as String
            val log = Json.parseToJsonElement(raw).jsonObject
            fun text(name: String) = log[name]!!.jsonPrimitive.content
            assertEquals("askSelect", text("type"))
            assertEquals("askPrompt", text("type2"))
            val options = Json.parseToJsonElement(text("options")).jsonObject
            assertEquals("选学期", options["title"]!!.jsonPrimitive.content)
            assertEquals(2, options["items"]!!.jsonArray.size)
            val values = log["values"]!!.jsonArray.map { it.jsonPrimitive.content }
            assertEquals("3", values[0], "select 回传的是索引")
            assertEquals("null", values[1], "prompt 取消应当是 null（正常结果，不是错误）")
            assertEquals("false", values[2], "confirm 的「否」应当是 false")
            val errors = log["errors"]!!.jsonArray.map { it.jsonPrimitive.content }
            assertEquals(1, errors.size, "只有宿主明确报错的那次才算失败")
            assertEquals("参数不合法", errors[0])
        } finally {
            Context.exit()
        }
    }

    private companion object {
        /** 让整段 runner 能在 Rhino 里跑起来的最小垫片（沙箱 preamble 要用到这几个全局）。 */
        val BRIDGE_STUBS = """
            var window = this;
            var messages = [];
            window.__ncMessages = messages;
            window["ncBridge"] = { postMessage: function (m) { messages.push(m); } };
            function XMLHttpRequest() {}
            XMLHttpRequest.prototype.open = function () {};
            var navigator = { sendBeacon: function () { return true; } };
            function Promise(executor) {
              this.state = 'pending';
              this.value = undefined;
              this.handlers = [];
              var self = this;
              executor(
                function (v) {
                  self.state = 'fulfilled'; self.value = v;
                  for (var i = 0; i < self.handlers.length; i++) { if (self.handlers[i].ok) self.handlers[i].ok(v); }
                },
                function (e) {
                  self.state = 'rejected'; self.value = e;
                  for (var i = 0; i < self.handlers.length; i++) { if (self.handlers[i].err) self.handlers[i].err(e); }
                }
              );
            }
            Promise.prototype.then = function (ok, err) {
              if (this.state === 'fulfilled') { if (ok) ok(this.value); }
              else if (this.state === 'rejected') { if (err) err(this.value); }
              else this.handlers.push({ ok: ok, err: err });
              return this;
            };
        """.trimIndent()

        val PROBE = """
            (function () {
              var log = { values: [], errors: [] };
              function take(index) { return JSON.parse(window.__ncMessages[index]); }
              function idOf(index) { return take(index).id; }

              window.__ncSelect({ title: '选学期', items: ['A', 'B'] }).then(
                function (v) { log.values.push(v === null ? 'null' : String(v)); },
                function (e) { log.errors.push(String(e && e.message ? e.message : e)); }
              );
              log.type = take(0).type;
              log.options = take(0).options;
              window.__ncAskReply(idOf(0), true, '3');

              window.__ncPrompt({ title: '学年' }).then(
                function (v) { log.values.push(v === null ? 'null' : String(v)); },
                function (e) { log.errors.push(String(e && e.message ? e.message : e)); }
              );
              log.type2 = take(1).type;
              window.__ncAskReply(idOf(1), true, 'null');

              window.__ncConfirm({ title: '确定？' }).then(
                function (v) { log.values.push(String(v)); },
                function (e) { log.errors.push(String(e && e.message ? e.message : e)); }
              );
              window.__ncAskReply(idOf(2), true, 'false');

              window.__ncConfirm({ title: 'x' }).then(
                function (v) { log.values.push(String(v)); },
                function (e) { log.errors.push(String(e && e.message ? e.message : e)); }
              );
              window.__ncAskReply(idOf(3), false, '参数不合法');

              return JSON.stringify(log);
            })()
        """.trimIndent()
    }
}
