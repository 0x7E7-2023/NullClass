package com.nullclass.feature.settings

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.nullclass.core.model.WidgetFontSize
import com.nullclass.core.ui.layout.AdaptiveColumn
import com.nullclass.sync.AutoSyncInterval
import com.nullclass.widget.NextClassWidgetReceiver
import com.nullclass.widget.TodayWidgetReceiver
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 应用设置：WebDAV 同步、自动同步、桌面小组件、课表显示。提醒相关在「通知与提醒」页。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenTransfer: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val lastSyncAt by viewModel.lastSyncAt.collectAsState()
    val autoSyncInterval by viewModel.autoSyncInterval.collectAsState()
    val widgetFontSize by viewModel.widgetFontSize.collectAsState()
    val showOtherWeekCourses by viewModel.showOtherWeekCourses.collectAsState()

    val context = LocalContext.current

    val appBarScrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(appBarScrollBehavior.nestedScrollConnection),
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
                scrollBehavior = appBarScrollBehavior,
            )
        },
        // safeDrawing：WebDAV 的地址/账号/密码三个输入框在页面下半部，
        // 键盘弹出时靠它收缩滚动视口，否则会被盖住且滚不出来。
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        AdaptiveColumn(
            modifier = Modifier.padding(padding),
            scrollState = rememberScrollState(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            imePadding = false,
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
                }
                Switch(
                    checked = showOtherWeekCourses,
                    onCheckedChange = viewModel::setShowOtherWeekCourses,
                )
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
