package com.nullclass.feature.settings.jw

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.nullclass.core.ui.R as CoreR
import com.nullclass.feature.settings.R
import com.nullclass.feature.settings.toUiText
import com.nullclass.core.ui.i18n.UiText
import com.nullclass.core.ui.i18n.resolve
import com.nullclass.core.ui.layout.LocalWindowSize
import com.nullclass.importer.NullClassCodec
import com.nullclass.importer.jw.JwAdapter
import com.nullclass.importer.jw.JwAskRequest
import com.nullclass.importer.jw.JwHostAllowlist
import com.nullclass.importer.jw.JwOriginRules
import com.nullclass.importer.jw.JwPackageException
import com.nullclass.importer.jw.JwPayloadCodec
import com.nullclass.importer.jw.JwScheduleNormalizer
import com.nullclass.importer.jw.JwSchedulePayload
import com.nullclass.importer.ImportNoticeEntry
import com.nullclass.importer.jw.ocr.JwBoxes
import com.nullclass.importer.jw.ocr.JwOcrBuildResult
import com.nullclass.importer.jw.ocr.JwOcrScheduleBuilder
import com.nullclass.importer.jw.ocr.JwTableAligner
import com.nullclass.ocr.OcrEngines
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.text.SimpleDateFormat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

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

    /**
     * 可变：通用适配器（[JwManifest.startUrlPrompt]）没有已知域名，用户实际打开的那个页面
     * 就是「同源」，导航到哪里就把哪个域加进来——否则提取期间连课表页自己的子资源都会被拦掉。
     * 第三方域照旧拦截。
     *
     * 必须线程安全：写入在 UI 线程（onPageFinished），读取在 WebView 的请求拦截线程
     * （`shouldInterceptRequest`）与 IO 线程（图片下载按域名放行）。普通 ArrayList 在
     * 提取进行中被追加会抛 ConcurrentModificationException，而拦截回调里没人接得住。
     */
    val allowedHosts: MutableList<String> = CopyOnWriteArrayList()

    private val blockedHosts = Collections.synchronizedSet(mutableSetOf<String>())

    fun allows(url: String?): Boolean {
        val host = hostOf(url) ?: return false
        return JwHostAllowlist.matches(host, allowedHosts)
    }

    /** 把当前页面的域补进白名单；已在白名单内则不动。 */
    fun allowCurrentHost(url: String?) {
        val host = hostOf(url) ?: return
        if (host.isBlank() || JwHostAllowlist.matches(host, allowedHosts)) return
        allowedHosts += host
    }

    /** 提取开始时清掉上一轮拦截记录，避免重复刷屏。 */
    fun resetBlockLog() {
        blockedHosts.clear()
    }

    /**
     * 冻结期内拦截非白名单请求。每个 host 只打一条日志（页面常有大量重复子资源）。
     */
    fun intercept(url: String?): WebResourceResponse? {
        if (!frozen || allows(url)) return null
        val host = hostOf(url) ?: "?"
        if (blockedHosts.add(host)) {
            JwExtractLog.w("闸门拦截 $host  $url") // i18n-exempt: 开发者日志
        }
        return blocked()
    }

    fun blocked(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

    private fun hostOf(url: String?): String? = runCatching { Uri.parse(url).host?.lowercase() }.getOrNull()
}

private const val DESKTOP_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

/** WebView 步骤：加载登录页（用户手动登录到课表页）+ 提取按钮。 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun JwWebViewStep(
    adapter: JwAdapter,
    autoExtract: Boolean,
    preferredUrl: String?,
    onExtracted: (documentJson: String, loadedUrl: String, notes: List<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 状态行由 WebView / 相机回调写入（非 Compose 上下文），所以存 UiText：
    // 存成已解析的字符串会把文案钉死在写入那一刻的语言上。
    var status by remember {
        mutableStateOf(
            UiText.Res(
                if (adapter.promptsForStartUrl) {
                    R.string.settings_jw_hint_generic
                } else {
                    R.string.settings_jw_hint
                },
            ),
        )
    }
    // rememberSaveable：这是用户显式点「切换手机版/电脑版」的选择（见下方按钮），
    // 旋转重建后 WebView 的 factory 会照它重设 UA，不保存的话用户的选择会被打回电脑版。
    // 注意本文件其余状态**有意不保存**，理由见下：
    //   frozen / extracting —— 提取期的运行时态。旋转后协程已死，恢复成 true 会让
    //     网络一直冻着、按钮一直转圈，比丢失更糟。
    //   ocrEnabled —— 是 OCR 能力探测的结果，LaunchedEffect 每次都会重新写。
    //   autoTriggered —— 防重复自动提取的哨兵。页面重新加载后本就该允许再触发一次。
    //   webView / scriptBridge —— 实例，无法序列化。
    var isDesktopMode by rememberSaveable { mutableStateOf(true) }
    var defaultUserAgent by remember { mutableStateOf<String?>(null) }
    var lastErrorLog by remember { mutableStateOf<String?>(null) }
    val consoleLogs = remember { Collections.synchronizedList(mutableListOf<String>()) }
    var frozen by remember { mutableStateOf(false) }
    var extracting by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var autoTriggered by remember { mutableStateOf(false) }
    var ocrEnabled by remember { mutableStateOf(false) }
    var ocrReview by remember { mutableStateOf<JwOcrBuildResult?>(null) }
    var scriptBridge by remember { mutableStateOf<JwScriptBridge?>(null) }
    // 提问弹窗的状态由界面持有（桥写、界面读），答完再回填给脚本
    val askState = remember { MutableStateFlow<JwAskRequest?>(null) }
    val askRequest by askState.collectAsState()

    val gate = remember(adapter.key) {
        JwNetworkGate().apply {
            allowedHosts += adapter.allowedHosts(hostOf(adapter.manifest.loginUrl))
        }
    }
    val startUrl = preferredUrl ?: adapter.manifest.scheduleUrlHint ?: adapter.manifest.loginUrl

    /** 一键刷新要回到「刚才成功提取的那一页」，而不是重新登录一遍。 */
    fun rememberableUrl(): String {
        val live = webView?.url
        return if (live != null && live.startsWith("http")) live else startUrl
    }

    // 能力探测放到后台：加载 OCR 引擎不该卡住首屏。
    // 提取前必须等它出结果 —— 首次加载模型要几秒，自动提取很容易跑在它前面，
    // 那样运行器会按「没有 OCR」构建脚本，适配器看到的 __ncCapabilities.ocr 就是 false。
    val ocrProbe = remember(adapter.key) { kotlinx.coroutines.CompletableDeferred<Boolean>() }
    LaunchedEffect(adapter.key) {
        val available = withContext(Dispatchers.IO) {
            runCatching { OcrEngines.default(context).available }.getOrDefault(false)
        }
        ocrEnabled = available
        ocrProbe.complete(available)
    }

    fun persistError(log: String) {
        lastErrorLog = log
        JwExtractLog.writeLastError(context, log)
    }

    fun extract() {
        val view = webView ?: return
        if (extracting) return
        extracting = true
        frozen = true
        gate.frozen = true
        gate.resetBlockLog()
        lastErrorLog = null
        JwExtractLog.clearLastError(context)
        scriptBridge?.reset()
        status = UiText.Res(R.string.settings_jw_extracting)
        JwExtractLog.i(
            // 开发者日志：adb / logcat 用，不翻译
            "开始提取 key=${adapter.key} url=${view.url} desktop=$isDesktopMode hosts=${gate.allowedHosts}", // i18n-exempt: 开发者日志
        )
        scope.launch {
            try {
                // 用到 OCR 的脚本才等：① 能力探测（首次要加载 ONNX 模型，自动提取很容易跑在它
                // 前面，那样脚本会被按「没有 OCR」构建）② 桥对象注入。纯 DOM 适配器不必白等。
                val usesOcr = adapter.usesOcrBridge()
                val ocrAvailable = if (usesOcr) ocrProbe.await() else false
                // 桥是异步注入的，用到它的脚本必须先等它到位 —— 否则能力位会报 false，
                // 脚本按「不支持」降级，明明能用却不用。
                if ((usesOcr && ocrAvailable) || adapter.usesAskBridge()) scriptBridge?.awaitReady()
                val bridge = scriptBridge
                val runner = JwScriptRunner(
                    view,
                    gate.allowedHosts,
                    ocrEnabled = ocrAvailable,
                    askEnabled = bridge != null,
                    // 等用户回答的时间不算脚本时间 —— 由宿主的账本说了算，不读页面全局
                    isWaitingForUser = { bridge?.hasPendingAsk() == true },
                )
                val extracted = runner.run(adapter.extractScript)
                val payloadJson = adapter.parseScript?.let { runner.run(it, extracted) } ?: extracted
                val payload = JwPayloadCodec.decode(payloadJson)
                when (payload.kind) {
                    JwSchedulePayload.KIND_IMAGE -> {
                        status = UiText.Res(R.string.settings_jw_ocr_running)
                        val page = JwImageOcr.recognize(context, payload.images.first(), gate.allowedHosts)
                        val table = JwTableAligner.align(page)
                        if (!table.reliable) {
                            val warnings = table.warnings.joined()
                            status = UiText.Res(R.string.settings_jw_ocr_unreliable, warnings)
                            persistError(
                                buildErrorLog(
                                    error = IllegalStateException("识别不可靠：${warnings.resolve(context)}"), // i18n-exempt: 开发者日志
                                    url = view.url,
                                    adapter = adapter,
                                    isDesktopMode = isDesktopMode,
                                    consoleLogs = synchronized(consoleLogs) { consoleLogs.toList() },
                                ),
                            )
                        } else {
                            val built = JwOcrScheduleBuilder.build(
                                table = table,
                                // 学期名会进数据库，属数据不属文案：用界面语言的当前取值即可
                                termName = context.getString(
                                    R.string.settings_jw_adapter_ocr,
                                    adapter.displayName,
                                ),
                                firstDayEpochDay = LocalDate.now()
                                    .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                                    .toEpochDay(),
                                totalWeeks = 20,
                                warnings = payload.warnings,
                            )
                            if (built.payload.terms.single().courses.isEmpty()) {
                                status = UiText.Res(R.string.settings_jw_ocr_empty)
                                persistError(
                                    buildErrorLog(
                                        error = IllegalStateException("识别结果里没有课程"), // i18n-exempt: 开发者日志
                                        url = view.url,
                                        adapter = adapter,
                                        isDesktopMode = isDesktopMode,
                                        consoleLogs = synchronized(consoleLogs) { consoleLogs.toList() },
                                    ),
                                )
                            } else {
                                ocrReview = built
                                status = UiText.Res(R.string.settings_jw_ocr_done)
                                lastErrorLog = null
                                JwExtractLog.i("OCR 识别完成 courses=${built.payload.terms.single().courses.size}") // i18n-exempt: 开发者日志
                            }
                        }
                    }
                    JwSchedulePayload.KIND_BOXES -> {
                        // 通用适配器：适配器已把页面量成「文字 + 坐标」，这里走与 OCR 完全相同的
                        // 表格结构层。文字是精确的，没有识别误差。
                        status = UiText.Res(R.string.settings_jw_ocr_building)
                        ocrReview = buildBoxesReview(context, adapter, view.title, payload)
                        status = UiText.Res(R.string.settings_jw_structure_done)
                        lastErrorLog = null
                    }
                    else -> {
                        val document = JwScheduleNormalizer.normalize(
                            payload = payload,
                            schoolKey = adapter.key,
                            now = System.currentTimeMillis(),
                        )
                        status = UiText.Res(R.string.settings_jw_extract_done)
                        lastErrorLog = null
                        JwExtractLog.i("提取成功 key=${adapter.key} terms=${document.terms.size}") // i18n-exempt: 开发者日志
                        onExtracted(NullClassCodec.encode(document), rememberableUrl(), payload.reviewNotes)
                    }
                }
            } catch (e: JwStructureNotFoundException) {
                status = UiText.Res(R.string.settings_jw_structure_missing, e.warnings.joined())
                persistError(
                    buildErrorLog(
                        error = e,
                        url = view.url,
                        adapter = adapter,
                        isDesktopMode = isDesktopMode,
                        consoleLogs = synchronized(consoleLogs) { consoleLogs.toList() },
                    ),
                )
            } catch (e: JwNoCoursesFoundException) {
                status = UiText.Res(R.string.settings_jw_no_courses)
                persistError(
                    buildErrorLog(
                        error = e,
                        url = view.url,
                        adapter = adapter,
                        isDesktopMode = isDesktopMode,
                        consoleLogs = synchronized(consoleLogs) { consoleLogs.toList() },
                    ),
                )
            } catch (e: JwImageOcr.JwImageUnreadableException) {
                status = UiText.Res(R.string.settings_jw_extract_failed, UiText.Res(R.string.settings_jw_image_unreadable))
                persistError(
                    buildErrorLog(
                        error = e,
                        url = view.url,
                        adapter = adapter,
                        isDesktopMode = isDesktopMode,
                        consoleLogs = synchronized(consoleLogs) { consoleLogs.toList() },
                    ),
                )
            } catch (e: Exception) {
                // 异常详情不翻（系统/服务器给的原始信息），只翻结论
                status = UiText.Res(
                    R.string.settings_jw_extract_failed,
                    e.message ?: e.javaClass.simpleName,
                )
                persistError(
                    buildErrorLog(
                        error = e,
                        url = view.url,
                        adapter = adapter,
                        isDesktopMode = isDesktopMode,
                        consoleLogs = synchronized(consoleLogs) { consoleLogs.toList() },
                    ),
                )
            } finally {
                extracting = false
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            // 弹窗还挂着就走人：不叫醒脚本的话它会一直卡在「等用户回答」上
            // 回填给适配器脚本的协议文本：脚本侧只认字符串，属给适配器作者的诊断
            scriptBridge?.cancelPendingAsk("页面已关闭") // i18n-exempt: 适配器协议文本
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
                    if (ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
                        WebView.setWebContentsDebuggingEnabled(true)
                    }
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

                    // 电脑分辨率 / 桌面视图支持与缩放
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    settings.setSupportZoom(true)
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                    defaultUserAgent = settings.userAgentString
                    if (isDesktopMode) {
                        settings.userAgentString = DESKTOP_USER_AGENT
                    }

                    webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                            if (consoleMessage != null) {
                                val level = consoleMessage.messageLevel()?.name ?: "LOG"
                                val msg = consoleMessage.message()
                                val src = consoleMessage.sourceId()?.substringAfterLast('/') ?: ""
                                val line = consoleMessage.lineNumber()
                                val formatted = "[$level] $msg ($src:$line)"
                                synchronized(consoleLogs) {
                                    if (consoleLogs.size >= 100) {
                                        consoleLogs.removeAt(0)
                                    }
                                    consoleLogs.add(formatted)
                                }
                                // 「空课沙箱」是注入脚本自己写的标记，用于筛出沙箱告警：解析判据
                                if (level == "ERROR" || level == "WARNING" || msg.contains("空课沙箱")) { // i18n-exempt: 解析关键词
                                    JwExtractLog.w("console $formatted")
                                }
                            }
                            return super.onConsoleMessage(consoleMessage)
                        }
                    }

                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?,
                        ): WebResourceResponse? {
                            return gate.intercept(request?.url?.toString())
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
                            // 通用适配器没有已知域名：用户自己打开的页面就是同源，导航到哪就放行到哪。
                            // 只在「地址由用户输入」的适配器上这么做，其余仍严格按 manifest 白名单。
                            if (adapter.promptsForStartUrl) gate.allowCurrentHost(url)
                            if (autoExtract && !autoTriggered) {
                                autoTriggered = true
                                status = UiText.Res(R.string.settings_jw_page_loaded)
                                extract()
                            }
                        }
                    }
                    webView = this
                    // 桥在 loadUrl 之前注册（注册后才创建的文档才会注入对象）；
                    // 注入本身是异步的，自动提取还得再等它到位 —— 见 extract() 里的 awaitReady
                    val rules = JwOriginRules.forAdapter(adapter.manifest, gate.allowedHosts)
                    if (rules.isNotEmpty()) {
                        val bridge = JwScriptBridge(context, this, gate.allowedHosts, scope, askState)
                        bridge.attach(rules)
                        scriptBridge = bridge
                    }
                    if (startUrl.isBlank()) {
                        // startUrlPrompt 适配器理论上不会走到这（地址在进入本页前就填好了）
                        status = UiText.Res(R.string.settings_jw_no_start_url)
                    } else {
                        loadUrl(startUrl)
                    }
                }
            },
            update = { view ->
                val targetUa = if (isDesktopMode) DESKTOP_USER_AGENT else defaultUserAgent
                if (targetUa != null && view.settings.userAgentString != targetUa) {
                    view.settings.userAgentString = targetUa
                    view.settings.useWideViewPort = isDesktopMode
                    view.settings.loadWithOverviewMode = isDesktopMode
                    view.reload()
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )
        // 手机横屏（约 400dp 高）下这条操作栏原本要吃掉约 130dp，减去 TopAppBar 后
        // WebView 只剩约 200dp，键盘一弹更是只剩约 100dp——而用户正要在里面输
        // 账号、密码、验证码。矮屏收紧间距、状态文字压成一行、冻结提示缩短文案。
        val compactBar = LocalWindowSize.current.isCompactHeight
        Column(
            Modifier
                .fillMaxWidth()
                .padding(if (compactBar) 8.dp else 12.dp),
            verticalArrangement = Arrangement.spacedBy(if (compactBar) 4.dp else 8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    status.resolve(),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (lastErrorLog != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (compactBar) 1 else Int.MAX_VALUE,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = { isDesktopMode = !isDesktopMode },
                ) {
                    Text(
                        stringResource(
                            if (isDesktopMode) {
                                R.string.settings_jw_switch_mobile
                            } else {
                                R.string.settings_jw_switch_desktop
                            },
                        ),
                    )
                }
            }
            if (frozen) {
                // 安全提示不能因为屏幕矮就不显示，只缩短文案
                Text(
                    stringResource(
                        if (compactBar) {
                            R.string.settings_jw_network_frozen_short
                        } else {
                            R.string.settings_jw_network_frozen
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    maxLines = if (compactBar) 1 else Int.MAX_VALUE,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { extract() },
                    enabled = !extracting,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        stringResource(
                            if (extracting) R.string.settings_jw_extracting else R.string.settings_jw_extract,
                        ),
                    )
                }
                OutlinedButton(
                    onClick = {
                        if (frozen) {
                            frozen = false
                            gate.frozen = false
                            status = UiText.Res(R.string.settings_jw_network_restored)
                        } else {
                            (context as? JwImportActivity)?.finish()
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        stringResource(
                            if (frozen) R.string.settings_jw_resume_browsing else CoreR.string.common_cancel,
                        ),
                    )
                }
            }
            if (lastErrorLog != null) {
                OutlinedButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        val clip = ClipData.newPlainText(
                            context.getString(R.string.settings_jw_error_log_title),
                            lastErrorLog,
                        )
                        clipboard?.setPrimaryClip(clip)
                        Toast.makeText(
                            context,
                            context.getString(R.string.settings_jw_error_log_copied),
                            Toast.LENGTH_SHORT,
                        ).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.settings_jw_error_log_copy))
                }
            }
        }
    }

    askRequest?.let { request ->
        JwAskDialog(
            request = request,
            adapterLabel = adapter.displayName,
            onResult = { scriptBridge?.answerAsk(it) },
        )
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
                onExtracted(NullClassCodec.encode(document), rememberableUrl(), review.payload.reviewNotes)
            },
            onDismiss = {
                ocrReview = null
                status = UiText.Res(R.string.settings_jw_image_cancelled)
            },
        )
    }
}

/**
 * 页面文本块 → 课表载荷 + 待核对项。
 *
 * 与 OCR 课表走的是**同一套**表格结构还原（星期表头 + 节次列锚定），差别只在输入：
 * 这里的文本框是适配器从 DOM 量出来的，文字精确；OCR 那条是识别出来的。
 */
private fun buildBoxesReview(
    context: Context,
    adapter: JwAdapter,
    title: String?,
    payload: JwSchedulePayload,
): JwOcrBuildResult {
    val table = JwTableAligner.align(JwBoxes.toOcrPage(payload))
    if (!table.reliable) {
        throw JwStructureNotFoundException(table.warnings)
    }
    val built = JwOcrScheduleBuilder.build(
        table = table,
        termName = pageTermName(context, adapter, title),
        firstDayEpochDay = LocalDate.now()
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            .toEpochDay(),
        totalWeeks = JwOcrScheduleBuilder.inferTotalWeeks(table),
        ocrAssisted = false,
        warnings = payload.warnings,
    )
    if (built.payload.terms.single().courses.isEmpty()) {
        throw JwNoCoursesFoundException()
    }
    return built
}

/** 找不出课表结构（表头/节次锚点对不上）。携带匹配器给的警告，文案由界面层取。 */
private class JwStructureNotFoundException(val warnings: List<ImportNoticeEntry>) :
    Exception("structure not found")

/** 结构层的几条提示连成一句（分句符随语言：中文「；」）。 */
private fun List<ImportNoticeEntry>.joined(): UiText =
    UiText.Joined(map { it.toUiText() }, CoreR.string.common_clause_separator)

/** 页面里没解析出任何课程。 */
private class JwNoCoursesFoundException : Exception("no courses")

/** 学期名：页面标题本身就是学期名就用它（用户一眼能认出），否则退回适配器名。 */
private fun pageTermName(context: Context, adapter: JwAdapter, title: String?): String {
    val clean = title?.replace(Regex("\\s+"), " ")?.trim()?.take(30).orEmpty()
    // 「学期」「学年」是判断页面标题是否就是学期名的关键词：数据判据，不是文案
    return if (clean.length >= 3 && (clean.contains("学期") || clean.contains("学年"))) { // i18n-exempt: 解析关键词
        clean
    } else {
        // 学期名会进数据库，属数据不属文案：与图片识别那条路一样，用界面语言的当前取值即可
        context.getString(R.string.settings_jw_adapter_auto, adapter.displayName)
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
    // joinToString 的 lambda 不是 @Composable，取不了 stringResource，用 context 取
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_jw_review_title)) },
        text = {
            Column(
                Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(R.string.settings_jw_review_count, courseCount, blockCount),
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    stringResource(R.string.settings_jw_review_defaults),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                term?.courses?.take(20)?.forEach { course ->
                    Text(
                        "· ${course.name}" + course.blocks.joinToString("") { block ->
                            context.getString(
                                R.string.settings_jw_review_block,
                                block.dayOfWeek,
                                block.startPeriod,
                                block.endPeriod,
                            )
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (review.issues.isNotEmpty()) {
                    Text(
                        stringResource(R.string.settings_jw_review_warn),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold,
                    )
                    review.issues.take(10).forEach { issue ->
                        Text(
                            "· ${issue.toUiText().resolve()}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                Text(
                    stringResource(R.string.settings_jw_review_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.settings_jw_review_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CoreR.string.common_cancel)) }
        },
    )
}

private fun hostOf(url: String): String? = runCatching { java.net.URI(url).host?.lowercase() }.getOrNull()

/**
 * 脚本是否用到了 OCR 全局。
 *
 * 只对这类脚本等桥：桥是异步注入的（真机上要几秒），而 `__ncCapabilities.ocr`
 * 会因为桥还没到位而报 false，适配器就会误判成「设备不支持 OCR」。
 * 纯 DOM 抓取的适配器不该为此白等。
 */
private fun JwAdapter.usesAskBridge(): Boolean {
    val source = extractScript + (parseScript ?: "")
    return source.contains("__ncSelect") || source.contains("__ncConfirm") || source.contains("__ncPrompt")
}

private fun JwAdapter.usesOcrBridge(): Boolean {
    // 只认真正会调起来的两个全局。
    //
    // 这里**不能**顺带匹配 `__ncCapabilities`：那是个太宽的判据 —— 一个只想查
    // 「能不能弹窗」的适配器（读 caps.ask）会被当成要用 OCR 的，于是每次提取
    // 都先白等 OCR 引擎探测（首次要加载 ONNX 模型，好几秒）。
    // 移植上游适配器时真有人踩到，只能靠不写这个词绕开：判据在宿主这边，不该
    // 让每个适配器作者都知道这个坑。
    val source = extractScript + (parseScript ?: "")
    return source.contains("__ncOcr") || source.contains("__ncOcrGrid")
}

private fun buildErrorLog(
    error: Throwable,
    url: String?,
    adapter: JwAdapter,
    isDesktopMode: Boolean,
    consoleLogs: List<String>,
): String = buildString {
    // 整块是给开发者看的原始日志（adb / 剪贴板反馈用），不翻译，逐行标注豁免
    appendLine("=== 教务课表提取失败日志 ===") // i18n-exempt: 开发者日志
    appendLine("学校: ${adapter.displayName} (${adapter.key})") // i18n-exempt: 开发者日志
    appendLine("当前 URL: ${url ?: "未知"}") // i18n-exempt: 开发者日志
    appendLine("显示模式: ${if (isDesktopMode) "电脑版" else "手机版"}") // i18n-exempt: 开发者日志
    val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
    appendLine("发生时间: $time") // i18n-exempt: 开发者日志
    appendLine()
    appendLine("【错误信息】") // i18n-exempt: 开发者日志
    appendLine(error.message ?: error.javaClass.simpleName)
    appendLine()
    appendLine("【异常堆栈】") // i18n-exempt: 开发者日志
    appendLine(error.stackTraceToString().trim())
    if (consoleLogs.isNotEmpty()) {
        appendLine()
        appendLine("【网页控制台日志 (最新 ${consoleLogs.size} 条)】") // i18n-exempt: 开发者日志
        consoleLogs.forEach { log ->
            appendLine(log)
        }
    }
}

