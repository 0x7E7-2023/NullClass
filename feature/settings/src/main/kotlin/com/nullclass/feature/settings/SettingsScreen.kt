package com.nullclass.feature.settings

import android.Manifest
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.nullclass.core.model.WidgetFontSize
import com.nullclass.sync.AutoSyncInterval
import com.nullclass.widget.NextClassWidgetReceiver
import com.nullclass.widget.TodayWidgetReceiver
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
    val widgetFontSize by viewModel.widgetFontSize.collectAsState()
    val showOtherWeekCourses by viewModel.showOtherWeekCourses.collectAsState()

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
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
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

            // ---- 后台可靠性 ----
            Text("后台可靠性", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "系统的省电策略（Doze / 厂商后台管控）可能推迟提醒与小组件刷新。" +
                    "建议把空课排除在电池优化之外；小米/华为/vivo 等厂商还需单独允许自启动。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            BatteryReliabilityStatus(context)
            val autostartPage = remember { BackgroundReliability.resolvedAutostartActivity(context) }
            if (autostartPage != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "厂商自启动：请在系统里允许空课自启动",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { BackgroundReliability.startAutostartSettings(context) }) {
                        Text("去设置")
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

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // ---- 桌面小组件 ----
            Text("桌面小组件", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            val widgetManager = remember { context.getSystemService(AppWidgetManager::class.java) }
            val pinSupported = remember { widgetManager?.isRequestPinAppWidgetSupported == true }
            Text(
                if (pinSupported) {
                    "点按后系统弹出添加确认，一键把课表钉到桌面，不用去小部件列表里翻找。"
                } else {
                    "当前桌面不支持一键添加，请长按桌面空白处，从「添加小工具」中手动添加。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            WidgetFontSizeSelector(
                selected = widgetFontSize,
                onSelect = viewModel::setWidgetFontSize,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { requestPinWidget(context, TodayWidgetReceiver::class.java) },
                    enabled = pinSupported,
                    modifier = Modifier.weight(1f),
                ) { Text("今日课程 3×2") }
                OutlinedButton(
                    onClick = { requestPinWidget(context, NextClassWidgetReceiver::class.java) },
                    enabled = pinSupported,
                    modifier = Modifier.weight(1f),
                ) { Text("下节课 2×1") }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // ---- 课表显示 ----
            Text("课表显示", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.setShowOtherWeekCourses(!showOtherWeekCourses) },
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("显示非本周课程")
                    Text(
                        "本周空着的时段，别的周要上的课用灰色标出来。" +
                            "与课表右上角「显示设置」里的是同一个开关，两边同步。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = showOtherWeekCourses,
                    onCheckedChange = viewModel::setShowOtherWeekCourses,
                )
            }
        }
    }
}

/** 电池优化豁免状态行；从系统设置页返回时（ON_RESUME）重新读取。 */
@Composable
private fun BatteryReliabilityStatus(context: Context) {
    val powerManager = remember { context.getSystemService(PowerManager::class.java) }
    var batteryIgnored by remember {
        mutableStateOf(powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true)
    }
    DisposableEffect(context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                batteryIgnored = powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
            }
        }
        val lifecycle = (context as? LifecycleOwner)?.lifecycle
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            if (batteryIgnored) "电池优化：已豁免 ✓" else "电池优化：未豁免，后台刷新可能被推迟",
            style = MaterialTheme.typography.bodySmall,
            color = if (batteryIgnored) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.error
            },
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { BackgroundReliability.startBatteryOptimizationSettings(context) }) {
            Text("去设置")
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
private fun WidgetFontSizeSelector(
    selected: WidgetFontSize,
    onSelect: (WidgetFontSize) -> Unit,
) {
    val options = WidgetFontSize.entries
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, size ->
            SegmentedButton(
                selected = selected == size,
                onClick = { onSelect(size) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
            ) {
                Text(size.label, fontSize = 13.sp)
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

/** 请求系统把小组件钉到桌面：launcher 弹「添加到桌面」确认层。 */
private fun requestPinWidget(context: Context, provider: Class<*>) {
    val manager = context.getSystemService(AppWidgetManager::class.java) ?: return
    // 成功回调 PendingIntent 在部分桌面（MIUI 等）不会被调用，不依赖它做任何事
    manager.requestPinAppWidget(ComponentName(context, provider), null, null)
}

private fun isNotificationGranted(context: Context): Boolean =    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
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
