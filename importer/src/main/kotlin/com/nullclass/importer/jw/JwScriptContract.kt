package com.nullclass.importer.jw

/**
 * 适配器脚本与宿主之间的**契约**：注入哪些全局、怎么包装脚本、怎么把结果分块取回。
 *
 * 运行器（Android 侧）与单元测试共用这里的字符串，避免两边各写一份 JS 而慢慢漂移。
 *
 * 约定：
 * - 脚本内容是**一个表达式**，求值结果可以是字符串 / 可 JSON 序列化的对象 / Promise；
 *   也可以自己调用 `__ncDone(json)`（多步异步请求用这种）。
 * - 报错用 `__ncError(msg)` 或抛异常；宿主另有超时兜底。
 * - 脚本用 `new Function` 编译执行：语法错误可被捕获并回报，而不是静默失败。
 */
object JwScriptContract {

    const val SPEC_VERSION = JwManifest.SPEC_VERSION

    const val GLOBAL_INPUT = "__ncInput"
    const val GLOBAL_RESULT = "__ncResult"
    /** 错误槽（字符串）。适配器用 [GLOBAL_ERROR] 这个函数写入。 */
    const val GLOBAL_ERROR_TEXT = "__ncErrorText"
    /** `__ncError(message)`：脚本报错入口。 */
    const val GLOBAL_ERROR = "__ncError"
    const val GLOBAL_DONE = "__ncDone"
    const val GLOBAL_CAPABILITIES = "__ncCapabilities"
    const val GLOBAL_OCR = "__ncOcr"
    const val GLOBAL_OCR_GRID = "__ncOcrGrid"
    const val GLOBAL_OCR_REPLY = "__ncOcrReply"

    /** 向用户提问：`__ncSelect` / `__ncConfirm` / `__ncPrompt`。 */
    const val GLOBAL_ASK_SELECT = "__ncSelect"
    const val GLOBAL_ASK_CONFIRM = "__ncConfirm"
    const val GLOBAL_ASK_PROMPT = "__ncPrompt"
    /** 宿主回填提问结果的入口。 */
    const val GLOBAL_ASK_REPLY = "__ncAskReply"

    /** JS 桥对象名（`WebViewCompat.addWebMessageListener` 注入）。 */
    const val BRIDGE_NAME = "ncBridge"

    /** 单次 `substring` 取回的字符数（evaluateJavascript 回调对超长字符串可能截断）。 */
    const val CHUNK_CHARS = 128 * 1024

    /** 单次提取允许的结果上限（字符）。 */
    const val MAX_RESULT_CHARS = 16 * 1024 * 1024

    /** 脚本超时。**等用户回答弹窗的时间不计入**（见 [JwRunBudget]）。 */
    const val DEFAULT_TIMEOUT_MS = 30_000L
    const val OCR_TIMEOUT_MS = 15_000L

    /** OCR 限额（单次提取内）。 */
    const val OCR_MAX_CALLS = 8
    const val OCR_MAX_PIXELS = 16 * 1024 * 1024

    private const val BACKSLASH = 0x5C.toChar()
    private const val QUOTE = 0x22.toChar()

    /** 字符串 → JS 字符串字面量（含 U+2028/2029 转义，老 WebView 也能解析）。 */
    fun jsStringLiteral(value: String): String {
        val sb = StringBuilder(value.length + 16)
        sb.append(QUOTE)
        for (ch in value) {
            when (ch.code) {
                0x22 -> sb.append(BACKSLASH).append(QUOTE)
                0x5C -> sb.append(BACKSLASH).append(BACKSLASH)
                0x0A -> sb.append(BACKSLASH).append('n')
                0x0D -> sb.append(BACKSLASH).append('r')
                0x09 -> sb.append(BACKSLASH).append('t')
                0x08 -> sb.append(BACKSLASH).append('b')
                0x0C -> sb.append(BACKSLASH).append('f')
                0x2028 -> sb.append(BACKSLASH).append("u2028")
                0x2029 -> sb.append(BACKSLASH).append("u2029")
                else -> if (ch.code < 0x20) {
                    sb.append(BACKSLASH).append("u%04x".format(ch.code))
                } else {
                    sb.append(ch)
                }
            }
        }
        sb.append(QUOTE)
        return sb.toString()
    }

    /**
     * 网络白名单沙箱：包装 fetch / XHR / WebSocket / EventSource / sendBeacon，
     * 只放行白名单主机。**这是纵深防御，不是安全边界**——真正的边界是宿主的请求拦截。
     */
    fun buildPreamble(allowedHosts: List<String>): String {
        val hosts = allowedHosts.joinToString(", ") { jsStringLiteral(it.lowercase()) }
        return PREAMBLE_TEMPLATE.replace("__NC_HOSTS__", hosts)
    }

    /** 完整的一次脚本执行包装（含沙箱、能力声明、OCR 桥、提问桥、结果收集）。 */
    fun buildRunner(
        script: String,
        inputJson: String?,
        allowedHosts: List<String>,
        ocrEnabled: Boolean,
        askEnabled: Boolean = false,
    ): String {
        val ocrBridge = if (ocrEnabled) {
            OCR_BRIDGE_TEMPLATE.replace("__NC_BRIDGE__", jsStringLiteral(BRIDGE_NAME))
        } else {
            ""
        }
        val askBridge = if (askEnabled) {
            ASK_BRIDGE_TEMPLATE.replace("__NC_BRIDGE__", jsStringLiteral(BRIDGE_NAME))
        } else {
            ""
        }
        return RUNNER_TEMPLATE
            .replace("__NC_INPUT__", inputJson?.let { jsStringLiteral(it) } ?: "null")
            .replace("__NC_SPEC__", SPEC_VERSION.toString())
            .replace("__NC_OCR_ENABLED__", ocrEnabled.toString())
            .replace("__NC_ASK_ENABLED__", askEnabled.toString())
            .replace("__NC_BRIDGE_NAME__", jsStringLiteral(BRIDGE_NAME))
            .replace("__NC_OCR_MAX_PIXELS__", OCR_MAX_PIXELS.toString())
            .replace("__NC_OCR_MAX_CALLS__", OCR_MAX_CALLS.toString())
            .replace("__NC_ASK_MAX_CALLS__", JwAskLimits.MAX_CALLS.toString())
            .replace("__NC_PREAMBLE__", buildPreamble(allowedHosts))
            .replace("__NC_OCR_BRIDGE__", ocrBridge)
            .replace("__NC_ASK_BRIDGE__", askBridge)
            .replace("__NC_SCRIPT__", jsStringLiteral(script))
    }

    /** 轮询脚本状态：返回 `{done, error, length}` 对象（evaluateJavascript 会 JSON 编码）。 */
    fun buildPollScript(): String = POLL_TEMPLATE

    /** 分块取回脚本：返回结果字符串的 [start, end) 片段。 */
    fun buildChunkScript(start: Int, end: Int): String =
        "(window.$GLOBAL_RESULT === undefined ? null : window.$GLOBAL_RESULT.substring($start, $end))"

    /** 宿主回填 OCR 结果（桥的应答）。 */
    fun buildOcrReplyScript(id: String, ok: Boolean, payloadJson: String): String =
        "window.$GLOBAL_OCR_REPLY(${jsStringLiteral(id)}, $ok, ${jsStringLiteral(payloadJson)})"

    /**
     * 宿主回填提问结果（桥的应答）。
     *
     * `ok = true` 时 [payloadJson] 是**答案的 JSON 字面量**（`3` / `"文本"` / `true` / `null`）；
     * `ok = false` 时是错误消息。取消不是错误：那是 `ok = true` + `null`（或 confirm 的 `false`）。
     */
    fun buildAskReplyScript(id: String, ok: Boolean, payloadJson: String): String =
        "window.$GLOBAL_ASK_REPLY(${jsStringLiteral(id)}, $ok, ${jsStringLiteral(payloadJson)})"

    private const val PREAMBLE_TEMPLATE = """
(function () {
  if (window.__ncSandboxInstalled) return;
  window.__ncSandboxInstalled = true;
  var __ncHosts = [__NC_HOSTS__];
  function __ncHostOf(url) {
    try {
      var parsed = new URL(String(url), window.location.href);
      return parsed.hostname.toLowerCase();
    } catch (e) {
      return null;
    }
  }
  function __ncAllowed(url) {
    var host = __ncHostOf(url);
    if (host === null) return false;
    for (var i = 0; i < __ncHosts.length; i++) {
      var pattern = __ncHosts[i];
      if (pattern.length >= 2 && pattern.charAt(0) === '*' && pattern.charAt(1) === '.') {
        var suffix = pattern.slice(2);
        if (host === suffix) return true;
        if (suffix && host.length > suffix.length + 1 &&
            host.slice(host.length - suffix.length - 1) === '.' + suffix) return true;
      } else if (pattern === host) {
        return true;
      }
    }
    return false;
  }
  function __ncBlocked(url) {
    return new Error('空课沙箱：禁止访问白名单之外的地址 ' + String(url));
  }
  var __ncRawFetch = window.fetch;
  if (__ncRawFetch) {
    window.fetch = function (input, init) {
      var url = (typeof input === 'string') ? input : (input && input.url ? input.url : '');
      if (!__ncAllowed(url)) return Promise.reject(__ncBlocked(url));
      return __ncRawFetch.call(this, input, init);
    };
  }
  var __ncRawOpen = XMLHttpRequest.prototype.open;
  XMLHttpRequest.prototype.open = function (method, url) {
    if (!__ncAllowed(url)) throw __ncBlocked(url);
    return __ncRawOpen.apply(this, arguments);
  };
  var __ncRawWs = window.WebSocket;
  if (__ncRawWs) {
    window.WebSocket = function (url, protocols) {
      if (!__ncAllowed(url)) throw __ncBlocked(url);
      return new __ncRawWs(url, protocols);
    };
  }
  var __ncRawEs = window.EventSource;
  if (__ncRawEs) {
    window.EventSource = function (url, config) {
      if (!__ncAllowed(url)) throw __ncBlocked(url);
      return new __ncRawEs(url, config);
    };
  }
  if (navigator.sendBeacon) {
    var __ncRawBeacon = navigator.sendBeacon.bind(navigator);
    navigator.sendBeacon = function (url, data) {
      if (!__ncAllowed(url)) return false;
      return __ncRawBeacon(url, data);
    };
  }
})();
"""

    private const val OCR_BRIDGE_TEMPLATE = """
(function () {
  var __ncSeq = 0;
  var __ncPending = {};
  window.$GLOBAL_OCR_REPLY = function (id, ok, payload) {
    var entry = __ncPending[id];
    if (!entry) return;
    delete __ncPending[id];
    if (ok) {
      // 规范 §5.1 承诺的是对象（r.boxes / g.cells），宿主传过来的是 JSON 字符串 —— 必须解析
      try { entry.resolve(JSON.parse(payload)); }
      catch (e) { entry.reject(new Error('OCR 返回的数据无法解析：' + String(e && e.message ? e.message : e))); }
    } else { entry.reject(new Error(String(payload || '识别失败'))); }
  };
  function __ncCall(type, input, options) {
    return new Promise(function (resolve, reject) {
      var id = type + '-' + (++__ncSeq);
      __ncPending[id] = { resolve: resolve, reject: reject };
      try {
        window[__NC_BRIDGE__].postMessage(JSON.stringify({ type: type, id: id, input: input, options: options || null }));
      } catch (e) {
        delete __ncPending[id];
        reject(new Error('OCR 桥不可用：' + String(e && e.message ? e.message : e)));
      }
    });
  }
  window.$GLOBAL_OCR = function (input, options) { return __ncCall('ocr', input, options); };
  window.$GLOBAL_OCR_GRID = function (input, options) { return __ncCall('ocrGrid', input, options); };
})();
"""

    private const val ASK_BRIDGE_TEMPLATE = """
(function () {
  var __ncAskSeq = 0;
  var __ncAskPending = {};
  window.$GLOBAL_ASK_REPLY = function (id, ok, payload) {
    var entry = __ncAskPending[id];
    if (!entry) return;
    delete __ncAskPending[id];
    if (ok) {
      try { entry.resolve(JSON.parse(payload)); }
      catch (e) { entry.reject(new Error('弹窗返回的数据无法解析：' + String(e && e.message ? e.message : e))); }
    } else {
      entry.reject(new Error(String(payload || '提问失败')));
    }
  };
  function __ncAsk(type, options) {
    return new Promise(function (resolve, reject) {
      var id = type + '-' + (++__ncAskSeq);
      __ncAskPending[id] = { resolve: resolve, reject: reject };
      try {
        window[__NC_BRIDGE__].postMessage(JSON.stringify({
          type: type,
          id: id,
          options: JSON.stringify(options || null)
        }));
      } catch (e) {
        delete __ncAskPending[id];
        reject(new Error('提问桥不可用：' + String(e && e.message ? e.message : e)));
      }
    });
  }
  window.$GLOBAL_ASK_SELECT = function (options) { return __ncAsk('askSelect', options); };
  window.$GLOBAL_ASK_CONFIRM = function (options) { return __ncAsk('askConfirm', options); };
  window.$GLOBAL_ASK_PROMPT = function (options) { return __ncAsk('askPrompt', options); };
})();
"""

    private const val RUNNER_TEMPLATE = """
(function () {
  try {
    window.$GLOBAL_RESULT = undefined;
    window.$GLOBAL_ERROR_TEXT = undefined;
    window.$GLOBAL_INPUT = __NC_INPUT__;
    window.$GLOBAL_DONE = function (value) {
      try {
        window.$GLOBAL_RESULT = (typeof value === 'string') ? value : JSON.stringify(value);
      } catch (e) {
        window.$GLOBAL_ERROR_TEXT = '适配器返回的结果无法序列化为 JSON：' + String(e && e.message ? e.message : e);
      }
    };
    window.$GLOBAL_ERROR = function (message) {
      window.$GLOBAL_ERROR_TEXT = String(message && message.message ? message.message : message);
    };
    window.$GLOBAL_CAPABILITIES = {
      specVersion: __NC_SPEC__,
      ocr: __NC_OCR_ENABLED__ && (typeof window[__NC_BRIDGE_NAME__] !== 'undefined'),
      // 提问能力要同时满足「宿主开了」「桥对象在」「三个全局真的定义了」——
      // 只报引擎/开关会撒谎，适配器会拿着 undefined 去调用然后静默失败。
      ask: __NC_ASK_ENABLED__ &&
        (typeof window[__NC_BRIDGE_NAME__] !== 'undefined') &&
        (typeof window.$GLOBAL_ASK_SELECT === 'function'),
      ocrMaxPixels: __NC_OCR_MAX_PIXELS__,
      ocrMaxCalls: __NC_OCR_MAX_CALLS__,
      askMaxCalls: __NC_ASK_MAX_CALLS__
    };
__NC_PREAMBLE__
__NC_OCR_BRIDGE__
__NC_ASK_BRIDGE__
    var __ncSource = __NC_SCRIPT__;
    var __ncReturn;
    try {
      __ncReturn = new Function('return (' + __ncSource + String.fromCharCode(10) + ');').call(window);
    } catch (e) {
      window.$GLOBAL_ERROR('适配器脚本无法执行：' + String(e && e.message ? e.message : e));
      return;
    }
    if (window.$GLOBAL_RESULT === undefined && window.$GLOBAL_ERROR_TEXT === undefined) {
      if (__ncReturn && typeof __ncReturn.then === 'function') {
        __ncReturn.then(
          function (value) { window.$GLOBAL_DONE(value); },
          function (e) { window.$GLOBAL_ERROR(e); }
        );
      } else if (typeof __ncReturn !== 'undefined') {
        window.$GLOBAL_DONE(__ncReturn);
      }
    }
  } catch (e) {
    window.$GLOBAL_ERROR(e);
  }
})();
"""

    private const val POLL_TEMPLATE = """
({
  done: (typeof window.$GLOBAL_RESULT !== 'undefined') || (typeof window.$GLOBAL_ERROR_TEXT !== 'undefined'),
  error: (typeof window.$GLOBAL_ERROR_TEXT === 'undefined') ? null : String(window.$GLOBAL_ERROR_TEXT),
  length: (typeof window.$GLOBAL_RESULT === 'string') ? window.$GLOBAL_RESULT.length : 0
})
"""
}
