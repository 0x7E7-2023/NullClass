package com.nullclass.feature.settings.jw

import android.content.Context
import android.os.SystemClock
import android.webkit.WebView
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.nullclass.importer.jw.JwManifest
import com.nullclass.importer.jw.JwScriptContract
import com.nullclass.importer.jw.ocr.JwTableAligner
import com.nullclass.importer.jw.ocr.OcrPage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

/**
 * `__ncOcr` / `__ncOcrGrid` 的宿主侧实现（适配器脚本 → 应用内 OCR）。
 *
 * 桥只对**教务页面 origin** 注入，只接受图片，只回传文本与坐标：
 * 适配器无法借它读任意页面内容，也不能当通用 fetch 用。
 * 每次提取的调用次数有上限，单次识别有超时。
 */
class JwOcrBridge(
    private val context: Context,
    private val webView: WebView,
    private val allowedHosts: List<String>,
    private val scope: CoroutineScope,
) {

    private val calls = AtomicInteger(0)

    /** 桥是否真的挂上了（特性不支持或注入失败时为 false，此时不必等它出现）。 */
    @Volatile
    private var attached: Boolean = false

    /** 每次提取开始时重置计数。 */
    fun reset() {
        calls.set(0)
    }

    /**
     * 等桥对象出现在页面里再跑脚本。
     *
     * `addWebMessageListener` 的对象是**异步**注入到渲染进程的：`onPageFinished`
     * 立刻自动提取时，`window.ncBridge` 可能还没到位 —— 适配器会看到
     * `__ncCapabilities.ocr === false` 然后直接放弃（真机上复现过）。
     */
    suspend fun awaitReady(timeoutMs: Long = 3_000L) {
        if (!attached) return
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        val probe = "typeof window[${JwScriptContract.jsStringLiteral(JwScriptContract.BRIDGE_NAME)}] !== 'undefined'"
        while (SystemClock.uptimeMillis() < deadline) {
            val present = suspendCancellableCoroutine { cont ->
                webView.evaluateJavascript(probe) { value -> cont.resume(value == "true", null) }
            }
            if (present) return
            delay(50L)
        }
    }

    /** 注入桥（只在支持 WebMessageListener 的 WebView 上；否则能力位保持 false）。 */
    fun attach(originRules: Set<String>) {
        if (originRules.isEmpty()) return
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return
        val listener = WebViewCompat.WebMessageListener { _, message, _, _, _ ->
            handleMessage(message)
        }
        runCatching {
            WebViewCompat.addWebMessageListener(
                webView,
                JwScriptContract.BRIDGE_NAME,
                originRules,
                listener,
            )
            attached = true
        }
    }

    private fun handleMessage(message: WebMessageCompat) {
        val raw = message.data ?: return
        val request = runCatching { JSONObject(raw) }.getOrNull() ?: return
        val type = request.optString("type")
        val id = request.optString("id")
        if (id.isEmpty()) return
        if (type != TYPE_OCR && type != TYPE_OCR_GRID) {
            reply(id, ok = false, payload = "不支持的桥调用：$type")
            return
        }
        if (calls.incrementAndGet() > JwScriptContract.OCR_MAX_CALLS) {
            reply(id, ok = false, payload = "OCR 调用次数超过上限（${JwScriptContract.OCR_MAX_CALLS} 次）")
            return
        }
        val input = request.optString("input")
        if (input.isEmpty()) {
            reply(id, ok = false, payload = "缺少图片输入")
            return
        }
        scope.launch {
            try {
                val page = withTimeout(JwScriptContract.OCR_TIMEOUT_MS) {
                    JwImageOcr.recognizeInput(context, input, allowedHosts)
                }
                reply(id, ok = true, payload = if (type == TYPE_OCR) ocrPayload(page) else gridPayload(page))
            } catch (e: TimeoutCancellationException) {
                reply(id, ok = false, payload = "识别超时")
            } catch (e: Exception) {
                reply(id, ok = false, payload = e.message ?: "识别失败")
            }
        }
    }

    private fun reply(id: String, ok: Boolean, payload: String) {
        val script = JwScriptContract.buildOcrReplyScript(id, ok, payload)
        webView.post { webView.evaluateJavascript(script, null) }
    }

    private fun ocrPayload(page: OcrPage): String {
        val boxes = JSONArray()
        page.boxes.forEach { box ->
            boxes.put(
                JSONObject()
                    .put("text", box.text)
                    .put("x", box.left)
                    .put("y", box.top)
                    .put("w", box.width)
                    .put("h", box.height)
                    .put("confidence", box.confidence.toDouble()),
            )
        }
        return JSONObject()
            .put("width", page.width)
            .put("height", page.height)
            .put("boxes", boxes)
            .toString()
    }

    private fun gridPayload(page: OcrPage): String {
        val table = JwTableAligner.align(page)
        val cells = JSONArray()
        table.cells.forEach { row -> cells.put(JSONArray(row)) }
        return JSONObject()
            .put("width", page.width)
            .put("height", page.height)
            .put("rowAnchors", JSONArray(table.rowAnchors))
            .put("colAnchors", JSONArray(table.colAnchors))
            .put("cells", cells)
            .put("reliable", table.reliable)
            .put("warnings", JSONArray(table.warnings))
            .toString()
    }

    companion object {
        private const val TYPE_OCR = "ocr"
        private const val TYPE_OCR_GRID = "ocrGrid"

        /**
         * 白名单里的每个域名都生成一条 origin 规则 —— 登录页与课表页可能不同源
         * （CAS 单点登录尤其常见），只绑一个 origin 会让课表页上拿不到 `__ncOcr`。
         *
         * **端口必须带上**：`addWebMessageListener` 的规则是 origin（`scheme://host[:port]`），
         * 省略端口只表示默认端口。教务系统跑在 8080/8081 这类非默认端口上很常见，
         * 规则少写端口会导致桥根本不注入（页面里 `window.ncBridge` 是 undefined）。
         * `allowHosts` 按规范只写主机名、拿不到端口，所以 http/https 的默认端口都发一条。
         */
        fun originRules(manifest: JwManifest, allowedHosts: List<String>): Set<String> {
            val rules = linkedSetOf<String>()
            fun addUrl(url: String?) {
                val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return
                val scheme = uri.scheme?.lowercase() ?: return
                if (scheme != "http" && scheme != "https") return
                val host = uri.host?.lowercase() ?: return
                rules += if (uri.port > 0) "$scheme://$host:${uri.port}" else "$scheme://$host"
            }
            addUrl(manifest.loginUrl)
            addUrl(manifest.scheduleUrlHint)
            allowedHosts.forEach { host ->
                val lower = host.lowercase()
                rules += "https://$lower"
                rules += "http://$lower"
            }
            return rules
        }
    }
}
