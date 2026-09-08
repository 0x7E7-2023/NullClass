package com.nullclass.feature.settings.jw

import android.content.Context
import android.webkit.WebView
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.nullclass.importer.jw.JwScriptContract
import com.nullclass.importer.jw.ocr.JwTableAligner
import com.nullclass.importer.jw.ocr.OcrPage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
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

    /** 每次提取开始时重置计数。 */
    fun reset() {
        calls.set(0)
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
         */
        fun originRules(loginUrl: String, allowedHosts: List<String>): Set<String> {
            val scheme = runCatching { java.net.URI(loginUrl).scheme?.lowercase() }.getOrNull() ?: "https"
            return allowedHosts.map { "$scheme://${it.lowercase()}" }.toSet()
        }
    }
}
