package com.nullclass.feature.settings.jw

import android.content.Context
import android.os.SystemClock
import android.webkit.WebView
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.nullclass.importer.jw.JwAskCodec
import com.nullclass.importer.jw.JwAskException
import com.nullclass.importer.jw.JwAskLimits
import com.nullclass.importer.jw.JwAskRequest
import com.nullclass.importer.jw.JwScriptContract
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

/** 用户对脚本提问的回答。 */
sealed interface JwAskResult {
    /**
     * 用户取消。
     *
     * 注意 `__ncConfirm` 的「否」**不是**这个 —— 那是 [Flag]`(false)`：
     * 对确认框来说「否」是一个明确的答案，不是「没答」。
     */
    data object Cancelled : JwAskResult

    /** `__ncSelect`：选中项的下标（索引，不是文本 —— 脚本拿不到用户没选的东西）。 */
    data class Index(val value: Int) : JwAskResult

    /** `__ncConfirm`。 */
    data class Flag(val value: Boolean) : JwAskResult

    /** `__ncPrompt`：用户敲进去的文本。 */
    data class Input(val value: String) : JwAskResult
}

/**
 * 适配器脚本与宿主之间的**桥**：注册 `ncBridge` 对象、把消息按类型路由给处理器、把结果回填给脚本。
 *
 * 处理器：
 * - OCR（[JwOcrBridge]）—— 脚本要图片识别；
 * - 提问（本类）—— 脚本要问用户话，见 [JwAskRequest]。
 *
 * 桥只对**已知 origin** 注入（[com.nullclass.importer.jw.JwOriginRules]），
 * 所以拿不到桥的适配器（例如地址由用户现场输入的通用适配器）必须按
 * `__ncCapabilities` 降级 —— 这一点在规范里写死了。
 */
class JwScriptBridge(
    context: Context,
    private val webView: WebView,
    allowedHosts: List<String>,
    private val scope: CoroutineScope,
    /**
     * 当前正在等用户回答的问题。由界面持有：桥写、界面读。
     * 界面据此弹窗，答完调 [answerAsk]。
     */
    private val askState: MutableStateFlow<JwAskRequest?>,
) {

    private val ocr = JwOcrBridge(context, allowedHosts)
    private val askCalls = AtomicInteger(0)

    /** 正在等待回答的那次桥调用 id（回填时用）。 */
    private var pendingAskId: String? = null

    /**
     * 桥是否真的挂上了。
     *
     * `addWebMessageListener` 的对象是**异步**注入到渲染进程的：`onPageFinished`
     * 立刻自动提取时，`window.ncBridge` 可能还没到位 —— 适配器会看到
     * `__ncCapabilities` 报 false 然后直接放弃（真机上复现过）。
     */
    @Volatile
    private var attached = false

    /** 每次提取开始时重置：计数清零，上一轮没答完的问题取消掉（否则脚本会挂在那里等）。 */
    fun reset() {
        askCalls.set(0)
        ocr.reset()
        cancelPendingAsk("上一次提取已结束")
    }

    /** 等桥对象出现在页面里再跑脚本（纯 DOM 适配器不必白等）。 */
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

    /** 用户回答了当前问题。 */
    fun answerAsk(result: JwAskResult) {
        val id = takePendingId() ?: return
        askState.value = null
        replyAsk(id, ok = true, payloadJson = encodeAsk(result))
    }

    /** 取消当前问题（用户关掉弹窗 / 离开本页 / 开始新一轮提取）。 */
    fun cancelPendingAsk(reason: String) {
        val id = takePendingId() ?: return
        askState.value = null
        replyAsk(id, ok = false, payloadJson = reason)
    }

    /**
     * 是否有一次提问还没被回答。
     *
     * 运行器据此暂停脚本超时 —— **这个真值只能由宿主答**：页面全局是脚本可写的，
     * 拿它当刹车等于把刹车交给被审计的人。
     */
    fun hasPendingAsk(): Boolean = synchronized(this) { pendingAskId != null }

    /** 取走当前待答 id（并保证只被取走一次：答完/取消完不会再回填第二次）。 */
    private fun takePendingId(): String? = synchronized(this) {
        val current = pendingAskId ?: return null
        pendingAskId = null
        current
    }

    private fun handleMessage(message: WebMessageCompat) {
        val raw = message.data ?: return
        val request = runCatching { JSONObject(raw) }.getOrNull() ?: return
        val type = request.optString("type")
        val id = request.optString("id")
        if (id.isEmpty()) return
        when {
            type in JwOcrBridge.TYPES -> handleOcr(id, type, request)
            type in JwAskCodec.TYPES -> handleAsk(id, type, request)
            else -> reply(id, ok = false, payload = "不支持的桥调用：$type")
        }
    }

    private fun handleOcr(id: String, type: String, request: JSONObject) {
        scope.launch {
            try {
                reply(id, ok = true, payload = ocr.handle(type, request.optString("input")))
            } catch (e: Exception) {
                reply(id, ok = false, payload = e.message ?: "识别失败")
            }
        }
    }

    private fun handleAsk(id: String, type: String, request: JSONObject) {
        val ask = try {
            JwAskCodec.decode(type, request.optString("options"))
        } catch (e: JwAskException) {
            replyAsk(id, ok = false, payloadJson = e.message ?: "弹窗参数不合法")
            return
        }
        if (askCalls.incrementAndGet() > JwAskLimits.MAX_CALLS) {
            replyAsk(id, ok = false, payloadJson = "提问次数超过上限（${JwAskLimits.MAX_CALLS} 次）")
            return
        }
        val accepted = synchronized(this) {
            if (pendingAskId != null) {
                false
            } else {
                pendingAskId = id
                true
            }
        }
        if (!accepted) {
            replyAsk(id, ok = false, payloadJson = "上一个问题还没有回答")
            return
        }
        // 弹窗抬头会展示这是哪个适配器在问 —— 记一条，日志里也对得上。
        JwExtractLog.i("适配器提问 type=$type title=${ask.title}")
        askState.value = ask
    }

    /** 回填 OCR 结果。**提问不能用这个** —— 两个通道各有各的 pending 表，串了就是永远不 settle。 */
    private fun reply(id: String, ok: Boolean, payload: String) {
        webView.post {
            webView.evaluateJavascript(JwScriptContract.buildOcrReplyScript(id, ok, payload), null)
        }
    }

    /** 回填提问结果（含失败：参数不合法、超次数、上一个还没答）。 */
    private fun replyAsk(id: String, ok: Boolean, payloadJson: String) {
        webView.post {
            webView.evaluateJavascript(JwScriptContract.buildAskReplyScript(id, ok, payloadJson), null)
        }
    }

    /** 答案 → 脚本那边的值：取消是 `null`，其余按类型给字面量。 */
    private fun encodeAsk(result: JwAskResult): String = when (result) {
        is JwAskResult.Cancelled -> "null"
        is JwAskResult.Index -> result.value.toString()
        is JwAskResult.Flag -> result.value.toString()
        is JwAskResult.Input -> JSONObject.quote(result.value)
    }
}
