package com.nullclass.feature.settings.jw

import android.os.SystemClock
import android.webkit.WebView
import com.nullclass.importer.jw.JwRunBudget
import com.nullclass.importer.jw.JwRunVerdict
import com.nullclass.importer.jw.JwScriptContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.coroutines.resume

/** 适配器脚本执行失败（消息面向用户）。 */
class JwScriptException(message: String) : Exception(message)

/**
 * 在已登录的 WebView 里执行适配器脚本，并把结果**分块**取回。
 *
 * 为什么分块：`evaluateJavascript` 的回调对超长字符串可能截断，
 * 所以脚本先把结果写进 `window.__ncResult`，这里再按 [JwScriptContract.CHUNK_CHARS] 切片拼接。
 *
 * 异步是常态：真实适配器要「先取学期 → 再查课表」，所以脚本可以返回 Promise 或自己调 `__ncDone`。
 */
class JwScriptRunner(
    private val webView: WebView,
    private val allowedHosts: List<String>,
    private val ocrEnabled: Boolean = false,
    private val askEnabled: Boolean = false,
    /**
     * 此刻是否正等用户回答弹窗。
     *
     * **由宿主回答，不读页面全局** —— 页面全局脚本能改写，拿它当刹车
     * 等于把刹车交给被审计的人。
     */
    private val isWaitingForUser: () -> Boolean = { false },
) {

    suspend fun run(
        script: String,
        inputJson: String? = null,
        timeoutMs: Long = JwScriptContract.DEFAULT_TIMEOUT_MS,
    ): String {
        evaluate(JwScriptContract.buildRunner(script, inputJson, allowedHosts, ocrEnabled, askEnabled))
        val budget = JwRunBudget(timeoutMs)
        while (true) {
            val status = JSONObject(evaluate(JwScriptContract.buildPollScript()))
            if (status.optBoolean("done")) {
                if (!status.isNull("error")) {
                    val error = status.optString("error")
                    if (error.isNotEmpty()) throw JwScriptException(error)
                }
                val length = status.optInt("length", 0)
                if (length > JwScriptContract.MAX_RESULT_CHARS) {
                    throw JwScriptException("提取结果过大（${length / 1024}K 字符），请向适配器作者反馈")
                }
                return readResult(length)
            }

            when (budget.tick(SystemClock.elapsedRealtime(), isWaitingForUser())) {
                JwRunVerdict.SCRIPT_TIMEOUT ->
                    throw JwScriptException("脚本执行超时（${timeoutMs / 1000} 秒）")
                JwRunVerdict.WAIT_TIMEOUT ->
                    throw JwScriptException("等待回答超时（${budget.maxWaitMs / 1000} 秒）")
                JwRunVerdict.RUNNING -> Unit
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    private suspend fun readResult(length: Int): String {
        if (length <= 0) return ""
        val builder = StringBuilder(length)
        var start = 0
        while (start < length) {
            val end = minOf(start + JwScriptContract.CHUNK_CHARS, length)
            builder.append(decodeJsonString(evaluate(JwScriptContract.buildChunkScript(start, end))))
            start = end
        }
        return builder.toString()
    }

    private fun decodeJsonString(raw: String): String {
        if (raw.isEmpty() || raw == "null") return ""
        return try {
            JSONTokener(raw).nextValue() as? String ?: ""
        } catch (e: Exception) {
            throw JwScriptException("读取脚本结果失败：${e.message}")
        }
    }

    private suspend fun evaluate(js: String): String = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            webView.evaluateJavascript(js) { value -> continuation.resume(value ?: "null") }
        }
    }

    private companion object {
        const val POLL_INTERVAL_MS = 40L
    }
}
