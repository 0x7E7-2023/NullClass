package com.nullclass.feature.settings

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.nullclass.sync.AutoSyncInterval
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 应用设置：WebDAV 同步、课前提醒、自动同步。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenTransfer: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val lastSyncAt by viewModel.lastSyncAt.collectAsState()
    val reminderLeadMinutes by viewModel.reminderLeadMinutes.collectAsState()
    val autoSyncInterval by viewModel.autoSyncInterval.collectAsState()

    val context = LocalContext.current
    var notificationGranted by remember {
        mutableStateOf(isNotificationGranted(context))
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> notificationGranted = granted }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(onClick = onOpenTransfer) { Text("导入 / 导出") }
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
                "同步说明：整库快照按记录合并，同一条修改时间新者胜，删除会传播到所有设备。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // ---- 课前提醒 ----
            Text("课前提醒", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "每节课开始前发系统通知。提醒非精确（省电策略下可能有几分钟误差）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ReminderLeadSelector(
                selected = reminderLeadMinutes,
                onSelect = viewModel::setReminderLeadMinutes,
            )
            if (!notificationGranted) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "通知权限未授权，收不到提醒",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { requestNotificationPermission(context, permissionLauncher) }) {
                        Text("去授权")
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // ---- 自动同步 ----
            Text("自动同步", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "在后台按周期自动执行上面的手动同步（需已配置 WebDAV）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AutoSyncSelector(
                selected = autoSyncInterval,
                onSelect = viewModel::setAutoSyncInterval,
            )

            Text(
                "空课 · 本地优先，无账号、无埋点。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp, bottom = 24.dp),
            )
        }
    }
}

private val ReminderOptions = listOf(0, 5, 15, 30)

@Composable
private fun ReminderLeadSelector(selected: Int, onSelect: (Int) -> Unit) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        ReminderOptions.forEachIndexed { index, minutes ->
            SegmentedButton(
                selected = selected == minutes,
                onClick = { onSelect(minutes) },
                shape = SegmentedButtonDefaults.itemShape(index, ReminderOptions.size),
            ) {
                Text(if (minutes == 0) "关闭" else "${minutes}分钟", fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun AutoSyncSelector(selected: AutoSyncInterval, onSelect: (AutoSyncInterval) -> Unit) {
    val options = AutoSyncInterval.entries
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, interval ->
            SegmentedButton(
                selected = selected == interval,
                onClick = { onSelect(interval) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
            ) {
                Text(interval.label, fontSize = 13.sp)
            }
        }
    }
}

private fun isNotificationGranted(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    } else {
        true
    }

private fun requestNotificationPermission(
    context: Context,
    launcher: ActivityResultLauncher<String>,
) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
