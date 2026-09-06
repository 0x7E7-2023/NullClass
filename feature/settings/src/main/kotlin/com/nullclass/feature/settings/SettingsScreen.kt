package com.nullclass.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 应用设置：WebDAV 同步。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    val lastSyncAt by viewModel.lastSyncAt.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("WebDAV 同步", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "课表通过你自己的 WebDAV 服务器（坚果云、NextCloud 等）在设备间同步。" +
                    "凭证以明文存储在本机，请务必使用 HTTPS。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = state.url,
                onValueChange = viewModel::setUrl,
                label = { Text("服务器地址") },
                placeholder = { Text("https://dav.jianguoyun.com/dav/") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.username,
                onValueChange = viewModel::setUsername,
                label = { Text("用户名") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.password,
                onValueChange = viewModel::setPassword,
                label = { Text("密码 / 应用密码") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = viewModel::saveConfig, modifier = Modifier.weight(1f)) {
                    Text("保存配置")
                }
                OutlinedButton(
                    onClick = viewModel::testConnection,
                    enabled = state.configured && !state.busy,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("测试连接")
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Button(
                onClick = viewModel::syncNow,
                enabled = state.configured && !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state.busy) "同步中…" else "立即同步")
            }

            lastSyncAt?.let { at ->
                val time = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                Text(
                    "上次同步：$time",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            state.message?.let { message ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.busy) CircularProgressIndicator(modifier = Modifier.padding(4.dp))
                    Text(
                        message,
                        color = if (state.messageIsError) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            Text(
                "同步说明：整库快照按记录合并，同一条修改时间新者胜，删除会传播到所有设备。" +
                    "M2 仅支持手动同步，自动定时同步将在后续版本提供。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 24.dp),
            )
        }
    }
}
