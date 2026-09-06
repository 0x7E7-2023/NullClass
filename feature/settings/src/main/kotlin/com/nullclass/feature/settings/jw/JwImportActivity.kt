package com.nullclass.feature.settings.jw

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.nullclass.core.ui.theme.NullClassTheme
import com.nullclass.importer.NullClassCodec
import com.nullclass.importer.jw.JwAdapter
import com.nullclass.importer.jw.JwAdapterRegistry
import org.json.JSONTokener

/**
 * 教务导入宿主：选学校 → WebView 手动登录 → 提取课表 → 回传课表文档 JSON。
 *
 * **凭证红线**：登录全程用户手工完成，本 Activity 不解析、不存储任何账号密码；
 * 提取是纯前端 DOM 读取。结果以 ScheduleDocument JSON 回传给 TransferScreen，
 * 走与文件/二维码导入同一条 ImportPreview 管线。
 */
class JwImportActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NullClassTheme {
                JwImportContent(
                    onFinishWithDocument = { json ->
                        setResult(RESULT_OK, Intent().putExtra(EXTRA_DOCUMENT_JSON, json))
                        finish()
                    },
                    onCancel = { finish() },
                )
            }
        }
    }

    companion object {
        const val EXTRA_DOCUMENT_JSON = "documentJson"

        /** 启动入口（Activity Result 用）。 */
        fun intent(context: Context): Intent = Intent(context, JwImportActivity::class.java)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun JwImportContent(
    onFinishWithDocument: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var adapterKey by remember { mutableStateOf<String?>(null) }
    val adapter = adapterKey?.let { JwAdapterRegistry.byKey(it) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (adapter == null) "教务导入" else adapter.schoolName) },
                navigationIcon = {
                    IconButton(onClick = if (adapter == null) onCancel else ({ adapterKey = null })) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        if (adapter == null) {
            SchoolPicker(onPick = { adapterKey = it }, modifier = Modifier.padding(padding))
        } else {
            WebViewStep(
                adapter = adapter,
                onFinishWithDocument = onFinishWithDocument,
                modifier = Modifier.padding(padding),
            )
        }
    }
}

/** 学校选择：内建适配器 + 「提交我的学校」入口。 */
@Composable
private fun SchoolPicker(
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("从教务系统导入课表", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            "登录由你在下方网页里手工完成（验证码/扫码/短信都自己操作），" +
                "空课不会碰你的教务账号密码；登录后进入课表页面，点「提取课表」即可。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(JwAdapterRegistry.adapters) { adapter ->
                OutlinedButton(
                    onClick = { onPick(adapter.schoolKey) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(adapter.schoolName) }
            }
        }
        TextButton(onClick = {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(JwAdapterRegistry.ADAPTER_REQUEST_URL))
            ContextCompat.startActivity(context, intent, null)
        }) { Text("没有我的学校？提交适配请求") }
        Text(
            "适配器由社区贡献，覆盖学校逐步增加。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** WebView 步骤：加载登录页（用户手动登录到课表页）+ 提取按钮。 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun WebViewStep(
    adapter: JwAdapter,
    onFinishWithDocument: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var status by remember { mutableStateOf("登录并打开课表页面后，点下方「提取课表」") }
    var webView by remember { mutableStateOf<WebView?>(null) }
    val currentWebView = remember { mutableStateOf<WebView?>(null) }

    // 离开组合（返回学校选择/finish）时销毁 WebView，防渲染进程与 Activity Context 泄漏
    DisposableEffect(Unit) {
        onDispose {
            currentWebView.value?.apply {
                loadUrl("about:blank")
                (parent as? android.view.ViewGroup)?.removeView(this)
                destroy()
            }
            currentWebView.value = null
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    webViewClient = WebViewClient()
                    loadUrl(adapter.loginUrl)
                    webView = this
                    currentWebView.value = this
                }
            },
            onRelease = { w ->
                w.destroy()
                if (currentWebView.value === w) currentWebView.value = null
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
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = {
                        status = "提取中…"
                        webView?.evaluateJavascript(adapter.extractScript) { value ->
                            // evaluateJavascript 把返回值 JSON 编码（字符串会带引号转义）
                            val extracted = try {
                                (JSONTokener(value).nextValue() as? String)
                            } catch (e: Exception) {
                                null
                            }
                            if (extracted == null) {
                                status = "提取失败：页面结构未识别"
                                return@evaluateJavascript
                            }
                            try {
                                val document = adapter.parseExtracted(extracted)
                                status = "提取成功"
                                onFinishWithDocument(NullClassCodec.encode(document))
                            } catch (e: Exception) {
                                status = "提取失败：${e.message}"
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("提取课表") }
                OutlinedButton(
                    onClick = { (context as? JwImportActivity)?.finish() },
                    modifier = Modifier.weight(1f),
                ) { Text("取消") }
            }
        }
    }
}
