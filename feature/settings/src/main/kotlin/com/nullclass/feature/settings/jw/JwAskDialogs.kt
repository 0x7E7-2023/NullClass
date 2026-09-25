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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nullclass.core.ui.R as CoreR
import com.nullclass.feature.settings.R
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
    // rememberSaveable：旋转会重建 Activity，已选中的项不该被打回默认值。
    // request 作组合键，换一个请求时照常重置。
    var selected by rememberSaveable(request) { mutableStateOf(request.defaultIndex) }
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
            TextButton(onClick = { onResult(JwAskResult.Index(selected)) }) {
                Text(stringResource(CoreR.string.common_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = { onResult(JwAskResult.Cancelled) }) {
                Text(stringResource(CoreR.string.common_cancel))
            }
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
                // 适配器可以自定义按钮文案；没给就用应用自己的「确定」
                Text(request.confirmText ?: stringResource(CoreR.string.common_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = { onResult(JwAskResult.Flag(false)) }) {
                Text(request.cancelText ?: stringResource(CoreR.string.common_cancel))
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
    // rememberSaveable：脚本常靠这个框要学号/密码之类，旋转一次就清空太伤
    var text by rememberSaveable(request) { mutableStateOf(request.defaultText) }
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
                    text = stringResource(R.string.settings_jw_ask_safety),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onResult(JwAskResult.Input(text)) }) {
                Text(stringResource(CoreR.string.common_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = { onResult(JwAskResult.Cancelled) }) {
                Text(stringResource(CoreR.string.common_cancel))
            }
        },
    )
}

/** 固定抬头：谁在问。文本由宿主给，脚本无法改写或隐藏。 */
@Composable
private fun AskTitle(adapterLabel: String, title: String) {
    Column {
        Text(
            text = stringResource(R.string.settings_jw_ask_title, adapterLabel),
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
