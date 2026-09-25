package com.nullclass.feature.settings.jw

import android.content.Context
import com.nullclass.feature.settings.toUiText
import com.nullclass.importer.jw.JwScriptContract
import com.nullclass.importer.jw.ocr.JwTableAligner
import com.nullclass.importer.jw.ocr.OcrPage
import kotlinx.coroutines.TimeoutCancellationException
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
 *
 * 传输（注册 listener、收消息、回填结果）在 [JwScriptBridge]，这里只管识别本身。
 */
class JwOcrBridge(
    private val context: Context,
    private val allowedHosts: List<String>,
) {

    private val calls = AtomicInteger(0)

    /** 每次提取开始时重置计数。 */
    fun reset() {
        calls.set(0)
    }

    /**
     * 处理一次识别，返回回填给脚本的 JSON 载荷。
     * 失败抛异常，由 [JwScriptBridge] 转成脚本那边的 reject。
     */
    suspend fun handle(type: String, input: String): String {
        if (calls.incrementAndGet() > JwScriptContract.OCR_MAX_CALLS) {
            throw JwScriptException("OCR 调用次数超过上限（${JwScriptContract.OCR_MAX_CALLS} 次）")
        }
        if (input.isEmpty()) throw JwScriptException("缺少图片输入")
        val page = try {
            withTimeout(JwScriptContract.OCR_TIMEOUT_MS) {
                JwImageOcr.recognizeInput(context, input, allowedHosts)
            }
        } catch (e: TimeoutCancellationException) {
            throw JwScriptException("识别超时")
        } catch (e: JwImageOcr.JwImageUnreadableException) {
            throw JwScriptException(
                "图片无法读取或不在白名单内（只允许 https/http 且域名在适配器声明范围内），" +
                    "也可能只是图片损坏——建议用原始截图，不要用微信转发后的压缩图",
            )
        }
        return if (type == TYPE_OCR) ocrPayload(page) else gridPayload(page)
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
            // 脚本可能把这些原样转给用户看（放进载荷的 warnings），按当前界面语言取
            .put("warnings", JSONArray(table.warnings.map { it.toUiText().resolve(context) }))
            .toString()
    }

    companion object {
        const val TYPE_OCR = "ocr"
        const val TYPE_OCR_GRID = "ocrGrid"
        val TYPES = setOf(TYPE_OCR, TYPE_OCR_GRID)
    }
}
