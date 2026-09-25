package com.nullclass.feature.settings

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.nullclass.core.model.AppLanguage
import com.nullclass.core.model.ThemeMode
import com.nullclass.core.model.WidgetFontSize
import com.nullclass.core.ui.i18n.labelRes
import com.nullclass.core.ui.i18n.resolve
import com.nullclass.core.ui.layout.AdaptiveColumn
import com.nullclass.core.ui.R as CoreR
import com.nullclass.sync.AutoSyncInterval
import com.nullclass.widget.NextClassWidgetReceiver
import com.nullclass.widget.TodayWidgetReceiver
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 应用设置：外观、WebDAV 同步、自动同步、桌面小组件、课表显示。提醒相关在「通知与提醒」页。 */
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
    val themeMode by viewModel.themeMode.collectAsState()
    val showExamTab by viewModel.showExamTab.collectAsState()
    val appLanguage by viewModel.appLanguage.collectAsState()

    val context = LocalContext.current

    val appBarScrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(appBarScrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(CoreR.string.common_back),
                        )
                    }
                },
                actions = {
                    TextButton(onClick = onOpenTransfer) {
                        Text(stringResource(R.string.settings_transfer_entry))
                    }
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
            // ---- 外观 ----
            Text(
                stringResource(R.string.settings_appearance),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                stringResource(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        R.string.settings_appearance_desc
                    } else {
                        R.string.settings_appearance_desc_unsupported
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SegmentedSelector(
                options = ThemeMode.entries,
                selected = themeMode,
                label = { stringResource(it.labelRes) },
                onSelect = viewModel::setThemeMode,
            )

            // 语言入口只有在第二种译文就绪后才出现 —— 只有一种译文时，
            // 「跟随系统」与该语言效果完全相同，展示选择项没有意义。见 docs/i18n.md。
            if (AppLanguage.isSelectionMeaningful) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text(
                    stringResource(R.string.settings_language),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    stringResource(R.string.settings_language_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SegmentedSelector(
                    options = AppLanguage.selectable,
                    selected = appLanguage,
                    label = { stringResource(it.labelRes) },
                    onSelect = viewModel::setAppLanguage,
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Text(
                stringResource(R.string.settings_webdav),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                stringResource(R.string.settings_webdav_desc) + " " +
                    stringResource(R.string.settings_webdav_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = state.url,
                onValueChange = viewModel::setUrl,
                label = { Text(stringResource(R.string.settings_webdav_server)) },
                placeholder = { Text("https://dav.jianguoyun.com/dav/") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.username,
                onValueChange = viewModel::setUsername,
                label = { Text(stringResource(R.string.settings_webdav_username)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.password,
                onValueChange = viewModel::setPassword,
                label = { Text(stringResource(R.string.settings_webdav_password)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = viewModel::saveConfig, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_webdav_save))
                }
                OutlinedButton(
                    onClick = viewModel::testConnection,
                    enabled = state.configured && !state.busy,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.settings_webdav_test))
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Button(
                onClick = viewModel::syncNow,
                enabled = state.configured && !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(
                        if (state.busy) {
                            R.string.settings_webdav_syncing
                        } else {
                            R.string.settings_webdav_sync_now
                        },
                    ),
                )
            }

            lastSyncAt?.let { at ->
                val time = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                Text(
                    stringResource(R.string.settings_webdav_last_sync, time),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            state.message?.let { message ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.busy) CircularProgressIndicator(modifier = Modifier.padding(4.dp))
                    Text(
                        message.resolve(),
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
                stringResource(R.string.settings_webdav_merge_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // ---- 自动同步 ----
            Text(
                stringResource(R.string.settings_auto_sync),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                stringResource(R.string.settings_auto_sync_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SegmentedSelector(
                options = AutoSyncInterval.entries,
                selected = autoSyncInterval,
                label = { stringResource(it.labelRes) },
                onSelect = viewModel::setAutoSyncInterval,
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // ---- 桌面小组件 ----
            Text(
                stringResource(R.string.settings_widget),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            val widgetManager = remember { context.getSystemService(AppWidgetManager::class.java) }
            val pinSupported = remember { widgetManager?.isRequestPinAppWidgetSupported == true }
            Text(
                stringResource(
                    if (pinSupported) R.string.settings_widget_desc else R.string.settings_widget_unsupported,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SegmentedSelector(
                options = WidgetFontSize.entries,
                selected = widgetFontSize,
                label = { stringResource(it.labelRes) },
                onSelect = viewModel::setWidgetFontSize,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { requestPinWidget(context, TodayWidgetReceiver::class.java) },
                    enabled = pinSupported,
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.settings_widget_today)) }
                OutlinedButton(
                    onClick = { requestPinWidget(context, NextClassWidgetReceiver::class.java) },
                    enabled = pinSupported,
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.settings_widget_next)) }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // ---- 课表显示 ----
            Text(
                stringResource(R.string.settings_schedule_display),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.setShowOtherWeekCourses(!showOtherWeekCourses) },
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_show_other_week))
                }
                Switch(
                    checked = showOtherWeekCourses,
                    onCheckedChange = viewModel::setShowOtherWeekCourses,
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // ---- 底部导航 ----
            Text(
                stringResource(R.string.settings_bottom_nav),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.setShowExamTab(!showExamTab) },
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_show_exam_tab))
                }
                Switch(
                    checked = showExamTab,
                    onCheckedChange = viewModel::setShowExamTab,
                )
            }
        }
    }
}

@Composable
private fun <T> SegmentedSelector(
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = selected == option,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
            ) {
                Text(label(option), fontSize = 13.sp)
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
