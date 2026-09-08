package com.nullclass.feature.settings.jw

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.nullclass.importer.NullClassCodec
import com.nullclass.importer.jw.JwAdapter
import com.nullclass.importer.jw.JwPayloadCodec
import com.nullclass.importer.jw.JwScheduleNormalizer
import com.nullclass.importer.jw.JwSchedulePayload
import com.nullclass.importer.jw.ocr.JwOcrBuildResult
import com.nullclass.importer.jw.ocr.JwOcrScheduleBuilder
import com.nullclass.importer.jw.ocr.JwTableAligner
import com.nullclass.ocr.OcrEngines
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * 提取期的网络闸门。
 *
 * **这是白名单的真正执行点**（JS 层的包装只是纵深防御）：
 * 提取期间非白名单的**子资源**与**导航**一律拦掉，防止适配器把页面内容外发。
 * 提取结束后只保留 JS 层白名单（否则会打断教务页面自身的后续请求）。
 */
class JwNetworkGate {
    @Volatile
    var frozen: Boolean = false

    @Volatile
    var allowedHosts: List<String> = emptyList()

    fun allows(url: String?): Boolean {
        val host = hostOf(url) ?: return false
        return allowedHosts.any { it.equals(host, ignoreCase = true) }
    }

    fun blocked(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

    private fun hostOf(url: String?): String? = runCatching { Uri.parse(url).host?.lowercase() }.getOrNull()
}

/** WebView 步骤：加载登录页（用户手动登录到课表页）+ 提取按钮。 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun JwWebViewStep(
    adapter: JwAdapter,
    autoExtract: Boolean,
    preferredUrl: String?,
    onExtracted: (documentJson: String, loadedUrl: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("登录并打开课表页面后，点下方「提取课表」") }
    var frozen by remember { mutableStateOf(false) }
    var extracting by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var autoTriggered by remember { mutableStateOf(false) }
    var ocrEnabled by remember { mutableStateOf(false) }
    var ocrReview by remember { mutableStateOf<JwOcrBuildResult?>(null) }
    var ocrBridge by remember { mutableStateOf<JwOcrBridge?>(null) }

    val gate = remember(adapter.key) {
        JwNetworkGate().apply {
            allowedHosts = adapter.allowedHosts(hostOf(adapter.manifest.loginUrl))
        }
    }
    val startUrl = preferredUrl ?: adapter.manifest.scheduleUrlHint ?: adapter.manifest.loginUrl

    // 能力探测放到后台：加载 OCR 引擎不该卡住首屏
    LaunchedEffect(adapter.key) {
        ocrEnabled = withContext(Dispatchers.IO) {
            runCatching { OcrEngines.default(context).available }.getOrDefault(false)
        }
    }

    fun extract() {
        val view = webView ?: return
        if (extracting) return
        extracting = true
        frozen = true
        gate.frozen = true
        ocrBridge?.reset()
        status = "提取中…"
        scope.launch {
            try {
                val runner = JwScriptRunner(view, gate.allowedHosts, ocrEnabled = ocrEnabled)
                val extracted = runner.run(adapter.extractScript)
                val payloadJson = adapter.parseScript?.let { runner.run(it, extracted) } ?: extracted
                val payload = JwPayloadCodec.decode(payloadJson)
                when (payload.kind) {
                    JwSchedulePayload.KIND_IMAGE -> {
                        status = "识别图片课表…"
                        val page = JwImageOcr.recognize(context, payload.images.first(), gate.allowedHosts)
                        val table = JwTableAligner.align(page)
                        if (!table.reliable) {
                            status = "识别不可靠：${table.warnings.joinToString("；")}。请改用手动录入。"
                        } else {
                            val built = JwOcrScheduleBuilder.build(
                                table = table,
                                termName = "${adapter.displayName}（图片识别）",
                                firstDayEpochDay = LocalDate.now()
                                    .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                                    .toEpochDay(),
                                totalWeeks = 20,
                            )
                            if (built.payload.terms.single().courses.isEmpty()) {
                                status = "识别结果里没有课程，请改用手动录入。"
                            } else {
                                ocrReview = built
                                status = "识别完成，请核对后导入"
                            }
                        }
                    }
                    else -> {
                        val document = JwScheduleNormalizer.normalize(
                            payload = payload,
                            schoolKey = adapter.key,
                            now = System.currentTimeMillis(),
                        )
                        status = "提取成功"
                        onExtracted(NullClassCodec.encode(document), startUrl)
                    }
                }
            } catch (e: Exception) {
                status = "提取失败：${e.message ?: e.javaClass.simpleName}"
            } finally {
                extracting = false
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webView?.apply {
                loadUrl("about:blank")
                (parent as? android.view.ViewGroup)?.removeView(this)
                destroy()
            }
            webView = null
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    @Suppress("DEPRECATION")
                    settings.allowFileAccessFromFileURLs = false
                    @Suppress("DEPRECATION")
                    settings.allowUniversalAccessFromFileURLs = false
                    settings.setSupportMultipleWindows(false)
                    settings.javaScriptCanOpenWindowsAutomatically = false
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    settings.saveFormData = false
                    settings.setGeolocationEnabled(false)
                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?,
                        ): WebResourceResponse? {
                            if (gate.frozen && !gate.allows(request?.url?.toString())) return gate.blocked()
                            return null
                        }

                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?,
                        ): Boolean {
                            val url = request?.url?.toString()
                            return gate.frozen && !gate.allows(url)
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            if (autoExtract && !autoTriggered) {
                                autoTriggered = true
                                status = "页面已加载，自动提取中…"
                                extract()
                            }
                        }
                    }
                    loadUrl(startUrl)
                    webView = this
                    val rules = JwOcrBridge.originRules(adapter.manifest.loginUrl, gate.allowedHosts)
                    if (rules.isNotEmpty()) {
                        val bridge = JwOcrBridge(context, this, gate.allowedHosts, scope)
                        bridge.attach(rules)
                        ocrBridge = bridge
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )
        Column(
            Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (frozen) {
                Text(
                    "提取期间已冻结网页网络（防止适配器把页面内容发到站外）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { extract() },
                    enabled = !extracting,
                    modifier = Modifier.weight(1f),
                ) { Text(if (extracting) "提取中…" else "提取课表") }
                OutlinedButton(
                    onClick = {
                        if (frozen) {
                            frozen = false
                            gate.frozen = false
                            status = "已恢复网页网络"
                        } else {
                            (context as? JwImportActivity)?.finish()
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) { Text(if (frozen) "继续浏览网页" else "取消") }
            }
        }
    }

    ocrReview?.let { review ->
        OcrReviewDialog(
            review = review,
            onConfirm = {
                val document = JwScheduleNormalizer.normalize(
                    payload = review.payload,
                    schoolKey = adapter.key,
                    now = System.currentTimeMillis(),
                )
                ocrReview = null
                onExtracted(NullClassCodec.encode(document), startUrl)
            },
            onDismiss = {
                ocrReview = null
                status = "已取消图片识别结果（可重试或手动录入）"
            },
        )
    }
}

/** 图片课表的**确认闸门**：识别结果先给用户看，确认后才归一化入库。 */
@Composable
private fun OcrReviewDialog(
    review: JwOcrBuildResult,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val term = review.payload.terms.firstOrNull()
    val courseCount = term?.courses?.size ?: 0
    val blockCount = term?.courses?.sumOf { it.blocks.size } ?: 0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("核对识别结果") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("识别到 $courseCount 门课程、$blockCount 条安排。", fontWeight = FontWeight.Bold)
                Text(
                    "学期名、开始日期与总周数用的是默认值（本周周一 / 20 周），导入后可在学期编辑里改。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                term?.courses?.take(20)?.forEach { course ->
                    Text(
                        "· ${course.name}" + course.blocks.joinToString("") { block ->
                            "（周${block.dayOfWeek} ${block.startPeriod}-${block.endPeriod}节）"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (review.issues.isNotEmpty()) {
                    Text(
                        "⚠ 以下内容需要你重点核对：",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold,
                    )
                    review.issues.take(10).forEach { issue ->
                        Text(
                            "· $issue",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                Text(
                    "图片识别可能整行错位。导入后请到课表里抽查几门课的位置。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("确认导入") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private fun hostOf(url: String): String? = runCatching { java.net.URI(url).host?.lowercase() }.getOrNull()
