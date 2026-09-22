package com.nullclass.feature.settings.jw

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import java.text.DateFormat
import java.util.Date

/** 官方适配器库版本 + 上次检查时间 + 检查/更新按钮。 */
@Composable
fun JwLibraryUpdateSection(viewModel: JwLibraryUpdateViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.toasts.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "适配器库版本：${state.version?.let { "v$it" } ?: "内置"}（${state.adapterCount} 个）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "上次检查更新：" + (state.lastCheck?.let {
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it))
            } ?: "从未"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    val available = state.available?.latest
    OutlinedButton(
        onClick = { if (available != null) viewModel.update() else viewModel.check() },
        enabled = !state.busy,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            when {
                state.busy && available != null -> "正在更新…"
                state.busy -> "正在检查…"
                available != null -> "点击更新到 v$available"
                else -> "立即检查更新"
            },
        )
    }
}
