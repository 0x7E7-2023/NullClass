package com.nullclass.feature.settings.jw

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nullclass.importer.jw.JwAskRequest

/**
 * 适配器脚本的提问弹窗（规范 §5.2）。
 *
 * **抬头与警示由应用渲染，适配器改不掉**：标题、说明、选项文本都来自第三方脚本，
 * 如果不把「这是脚本在问你」摆在最显眼的位置，一个恶意脚本就能在我们自己的界面里
 * 伪装成空课去索要教务密码。所以：
 * - 抬头固定写着哪个适配器在问（[adapterLabel] 由宿主给，不是脚本给的）；
 * - 自由文本输入（`__ncPrompt`）底部固定附一句「空课不会向你索要账号信息」；
 * - 取消永远可用 —— 取消是正常结果（`null` / `false`），不是错误。
 */
@Composable
fun JwAskDialog(
    request: JwAskRequest,
    adapterLabel: String,
    onResult: (JwAskResult) -> Unit,
) {
    when (request) {
        is JwAskRequest.Select -> SelectDialog(request, adapterLabel, onResult)
        is JwAskRequest.Confirm -> ConfirmDialog(request, adapterLabel, onResult)
        is JwAskRequest.Prompt -> PromptDialog(request, adapterLabel, onResult)
    }
}

@Composable
private fun SelectDialog(
    request: JwAskRequest.Select,
    adapterLabel: String,
    onResult: (JwAskResult) -> Unit,
) {
    var selected by remember(request) { mutableStateOf(request.defaultIndex) }
    AlertDialog(
        onDismissRequest = { onResult(JwAskResult.Cancelled) },
        title = { AskTitle(adapterLabel, request.title) },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                AskMessage(request.message)
                request.items.forEachIndexed { index, item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = index == selected, onClick = { selected = index })
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = index == selected, onClick = { selected = index })
                        Text(item, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onResult(JwAskResult.Index(selected)) }) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = { onResult(JwAskResult.Cancelled) }) { Text("取消") }
        },
    )
}

@Composable
private fun ConfirmDialog(
    request: JwAskRequest.Confirm,
    adapterLabel: String,
    onResult: (JwAskResult) -> Unit,
) {
    AlertDialog(
        onDismissRequest = { onResult(JwAskResult.Flag(false)) },
        title = { AskTitle(adapterLabel, request.title) },
        text = { AskMessage(request.message) },
        confirmButton = {
            TextButton(onClick = { onResult(JwAskResult.Flag(true)) }) {
                Text(request.confirmText ?: "确定")
            }
        },
        dismissButton = {
            TextButton(onClick = { onResult(JwAskResult.Flag(false)) }) {
                Text(request.cancelText ?: "取消")
            }
        },
    )
}

@Composable
private fun PromptDialog(
    request: JwAskRequest.Prompt,
    adapterLabel: String,
    onResult: (JwAskResult) -> Unit,
) {
    var text by remember(request) { mutableStateOf(request.defaultText) }
    AlertDialog(
        onDismissRequest = { onResult(JwAskResult.Cancelled) },
        title = { AskTitle(adapterLabel, request.title) },
        text = {
            Column {
                AskMessage(request.message)
                OutlinedTextField(
                    value = text,
                    // 上限在这里截断，不靠脚本自觉：脚本给的 maxLength 只是它想要的长度。
                    onValueChange = { if (it.length <= request.maxLength) text = it },
                    placeholder = request.placeholder?.let { { Text(it) } },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "空课不会向你索要教务密码等账号信息。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onResult(JwAskResult.Input(text)) }) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = { onResult(JwAskResult.Cancelled) }) { Text("取消") }
        },
    )
}

/** 固定抬头：谁在问。文本由宿主给，脚本无法改写或隐藏。 */
@Composable
private fun AskTitle(adapterLabel: String, title: String) {
    Column {
        Text(
            text = "适配器「$adapterLabel」在向你提问",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.secondaryContainer)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun AskMessage(message: String?) {
    if (message.isNullOrBlank()) return
    Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}
