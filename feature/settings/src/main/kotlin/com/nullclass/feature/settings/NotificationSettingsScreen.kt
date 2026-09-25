package com.nullclass.feature.settings

import androidx.compose.ui.res.pluralStringResource
import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.nullclass.core.model.SkipDate
import com.nullclass.core.model.SkipDateType
import com.nullclass.core.ui.R as CoreR
import com.nullclass.core.ui.i18n.dayOfWeekLabel
import com.nullclass.core.ui.i18n.resolve
import com.nullclass.core.ui.layout.AdaptiveColumn
import com.nullclass.core.ui.layout.LocalWindowSize
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 通知与提醒：权限引导（通知/自启动/电池/精确闹钟/勿扰）、提前提醒时间、
 * 节假日在线同步与跳过日期。
 *
 * 调课（串课）原先也挂在这页尾部，已挪到「我的 → 快捷操作 → 调课」（见 [DaySwapScreen]）：
 * 它是调休当天要用的操作，不该埋在一页提醒设置的最底下。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationSettingsScreen(
    onBack: () -> Unit,
    viewModel: NotificationSettingsViewModel = hiltViewModel(),
) {
    val reminderLeadMinutes by viewModel.reminderLeadMinutes.collectAsState()
    val examReminderLeadMinutes by viewModel.examReminderLeadMinutes.collectAsState()
    val exactReminder by viewModel.exactReminder.collectAsState()
    val reminderBypassDnd by viewModel.reminderBypassDnd.collectAsState()
    val holidaySyncEnabled by viewModel.holidaySyncEnabled.collectAsState()
    val holidayLastSyncMs by viewModel.holidayLastSyncMs.collectAsState()
    val skipDates by viewModel.skipDates.collectAsState()
    val holidayBusy by viewModel.holidayBusy.collectAsState()
    val holidayMessage by viewModel.holidayMessage.collectAsState()

    val appBarScrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(appBarScrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_notification_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(CoreR.string.common_back),
                        )
                    }
                },
                scrollBehavior = appBarScrollBehavior,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        AdaptiveColumn(
            modifier = Modifier.padding(padding),
            scrollState = rememberScrollState(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            imePadding = false,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ---- 权限引导 ----
            Text(
                stringResource(R.string.settings_notification_permissions),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                stringResource(R.string.settings_notification_permissions_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PermissionPanel(
                exactReminder = exactReminder,
                onSetExactReminder = viewModel::setExactReminder,
                reminderBypassDnd = reminderBypassDnd,
                onSetReminderBypassDnd = viewModel::setReminderBypassDnd,
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // ---- 提前提醒时间 ----
            Text(
                stringResource(R.string.settings_notification_class_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                stringResource(R.string.settings_notification_class_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ReminderLeadSelector(
                selected = reminderLeadMinutes,
                onSelect = viewModel::setReminderLeadMinutes,
            )
            Text(
                stringResource(R.string.settings_notification_exam_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                stringResource(R.string.settings_notification_exam_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ExamReminderLeadSelector(
                selected = examReminderLeadMinutes,
                onSelect = viewModel::setExamReminderLeadMinutes,
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // ---- 节假日与跳过日期 ----
            Text(
                stringResource(R.string.settings_holiday_section),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                stringResource(R.string.settings_holiday_section_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ToggleRow(
                title = stringResource(R.string.settings_holiday_sync_title),
                subtitle = stringResource(R.string.settings_holiday_sync_desc),
                checked = holidaySyncEnabled,
                onCheckedChange = viewModel::setHolidaySyncEnabled,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (holidayLastSyncMs > 0) {
                    val time = Instant.ofEpochMilli(holidayLastSyncMs).atZone(ZoneId.systemDefault())
                        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                    Text(
                        stringResource(R.string.settings_holiday_last_sync, time),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Text(
                        stringResource(R.string.settings_holiday_never_synced),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                }
                OutlinedButton(onClick = viewModel::refreshHolidays, enabled = !holidayBusy) {
                    Text(
                        stringResource(
                            if (holidayBusy) {
                                R.string.settings_holiday_syncing
                            } else {
                                R.string.settings_holiday_sync_now
                            },
                        ),
                    )
                }
            }
            holidayMessage?.let { message ->
                Text(
                    message.text.resolve(),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (message.isError) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }

            var showDatePicker by remember { mutableStateOf(false) }
            Button(onClick = { showDatePicker = true }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_holiday_add_skip))
            }
            if (showDatePicker) {
                val pickerState = androidx.compose.material3.rememberDatePickerState(
                    initialSelectedDateMillis = LocalDate.now().toEpochDay() * 86_400_000L,
                    // 矮屏（手机横屏）放不下 568dp 的日历，直接开输入模式
                    initialDisplayMode = if (LocalWindowSize.current.isCompactHeight) {
                        androidx.compose.material3.DisplayMode.Input
                    } else {
                        androidx.compose.material3.DisplayMode.Picker
                    },
                )
                DatePickerDialog(
                    onDismissRequest = { showDatePicker = false },
                    confirmButton = {
                        TextButton(onClick = {
                            pickerState.selectedDateMillis?.let { millis ->
                                viewModel.addManualDate(millis / 86_400_000L)
                            }
                            showDatePicker = false
                        }) { Text(stringResource(CoreR.string.common_confirm)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { showDatePicker = false }) {
                            Text(stringResource(CoreR.string.common_cancel))
                        }
                    },
                ) {
                    DatePicker(state = pickerState)
                }
            }

            val today = remember { LocalDate.now().toEpochDay() }
            val upcoming = skipDates.filter { it.epochDay >= today }
            if (upcoming.isEmpty()) {
                Text(
                    stringResource(R.string.settings_holiday_no_skips),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    pluralStringResource(R.plurals.settings_holiday_skip_count, upcoming.size, upcoming.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                upcoming.forEach { skip ->
                    SkipDateRow(skip = skip, onDelete = { viewModel.removeDate(skip.epochDay) })
                }
            }
        }
    }
}

/** 权限状态面板：所有系统侧状态从 Context 读，ON_RESUME（从系统设置返回）时刷新。 */
@Composable
private fun PermissionPanel(
    exactReminder: Boolean,
    onSetExactReminder: (Boolean) -> Unit,
    reminderBypassDnd: Boolean,
    onSetReminderBypassDnd: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val powerManager = remember { context.getSystemService(PowerManager::class.java) }
    val alarmManager = remember { context.getSystemService(AlarmManager::class.java) }
    val notificationManager =
        remember { context.getSystemService(android.app.NotificationManager::class.java) }

    var notificationGranted by remember { mutableStateOf(isNotificationGranted(context)) }
    var batteryIgnored by remember {
        mutableStateOf(powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true)
    }
    var exactAllowed by remember { mutableStateOf(canScheduleExactAlarms(alarmManager)) }
    var dndGranted by remember {
        mutableStateOf(notificationManager?.isNotificationPolicyAccessGranted == true)
    }

    fun refreshStates() {
        notificationGranted = isNotificationGranted(context)
        batteryIgnored = powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
        exactAllowed = canScheduleExactAlarms(alarmManager)
        dndGranted = notificationManager?.isNotificationPolicyAccessGranted == true
    }

    DisposableEffect(context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshStates()
        }
        val lifecycle = (context as? LifecycleOwner)?.lifecycle
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> notificationGranted = granted }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        PermissionRow(
            title = stringResource(R.string.settings_permission_notification),
            status = stringResource(
                if (notificationGranted) {
                    R.string.settings_permission_granted
                } else {
                    R.string.settings_permission_notification_denied
                },
            ),
            granted = notificationGranted,
            actionLabel = stringResource(R.string.settings_permission_grant_action),
            onAction = { requestNotificationPermission(context, permissionLauncher) },
        )

        PermissionRow(
            title = stringResource(R.string.settings_permission_battery),
            status = stringResource(
                if (batteryIgnored) {
                    R.string.settings_permission_battery_exempt
                } else {
                    R.string.settings_permission_battery_denied
                },
            ),
            granted = batteryIgnored,
            actionLabel = stringResource(R.string.settings_permission_settings_action),
            onAction = { BackgroundReliability.startBatteryOptimizationSettings(context) },
        )

        val autostartPage = remember { BackgroundReliability.resolvedAutostartActivity(context) }
        if (autostartPage != null) {
            PermissionRow(
                title = stringResource(R.string.settings_permission_autostart),
                // 自启动状态无法查询，只能引导用户去系统页自查
                status = stringResource(R.string.settings_permission_autostart_hint),
                granted = null,
                actionLabel = stringResource(R.string.settings_permission_settings_action),
                onAction = { BackgroundReliability.startAutostartSettings(context) },
            )
        }

        PermissionRow(
            title = stringResource(R.string.settings_permission_exact_alarm),
            status = stringResource(
                when {
                    !exactReminder -> R.string.settings_permission_exact_off
                    exactAllowed -> R.string.settings_permission_exact_granted
                    else -> R.string.settings_permission_exact_denied
                },
            ),
            granted = if (!exactReminder) null else exactAllowed,
            actionLabel = stringResource(R.string.settings_permission_grant_action)
                .takeIf { !exactAllowed },
            onAction = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            },
                        )
                    }
                }
            },
        )
        ToggleRow(
            title = stringResource(R.string.settings_notification_exact_title),
            subtitle = stringResource(R.string.settings_notification_exact_desc),
            checked = exactReminder,
            onCheckedChange = onSetExactReminder,
        )

        PermissionRow(
            title = stringResource(R.string.settings_permission_dnd),
            status = stringResource(
                if (dndGranted) {
                    R.string.settings_permission_granted
                } else {
                    R.string.settings_permission_denied
                },
            ),
            granted = dndGranted,
            actionLabel = stringResource(R.string.settings_permission_grant_action)
                .takeIf { !dndGranted },
            onAction = {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        },
                    )
                }
            },
        )
        if (dndGranted) {
            ToggleRow(
                title = stringResource(R.string.settings_notification_bypass_dnd),
                subtitle = stringResource(R.string.settings_notification_bypass_dnd_desc),
                checked = reminderBypassDnd,
                onCheckedChange = onSetReminderBypassDnd,
            )
        }
    }
}

/** 权限状态行：标题 + 状态文字 + 右侧动作按钮（已授权时按钮消失）。 */
@Composable
private fun PermissionRow(
    title: String,
    status: String,
    granted: Boolean?,
    actionLabel: String?,
    onAction: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = actionLabel != null) { if (actionLabel != null) onAction() },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                color = when (granted) {
                    true -> MaterialTheme.colorScheme.primary
                    false -> MaterialTheme.colorScheme.error
                    null -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        if (actionLabel != null) {
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

/** 开关行：标题 + 副标题 + Switch。 */
@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** 跳过日期行：日期 + 名称/类型 + 删除。 */
@Composable
private fun SkipDateRow(skip: SkipDate, onDelete: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                val date = LocalDate.ofEpochDay(skip.epochDay)
                // 跨年时列表里会出现两个年份都有的「10月1日 · 国庆节」，
                // 看起来像重复条目，所以年份只在与今年不同时才写出来
                val monthDay = stringResource(R.string.settings_month_day, date.monthValue, date.dayOfMonth)
                val dayLabel = if (date.year != LocalDate.now().year) {
                    stringResource(R.string.settings_day_label_with_year, date.year, monthDay, dayOfWeekLabel(date.dayOfWeek.value))
                } else {
                    stringResource(R.string.settings_day_label, monthDay, dayOfWeekLabel(date.dayOfWeek.value))
                }
                Text(
                    dayLabel,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    when (skip.type) {
                        SkipDateType.HOLIDAY ->
                            skip.label ?: stringResource(R.string.settings_holiday_type_holiday)
                        SkipDateType.WORKDAY -> stringResource(R.string.settings_holiday_type_workday)
                        SkipDateType.MANUAL -> stringResource(R.string.settings_holiday_type_manual)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = stringResource(CoreR.string.common_delete),
                )
            }
        }
    }
}

private val ReminderOptions = listOf(0, 5, 15, 30)

private val ExamReminderOptions = listOf(0, 30, 2 * 60, 24 * 60)

@Composable
private fun ReminderLeadSelector(selected: Int, onSelect: (Int) -> Unit) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        ReminderOptions.forEachIndexed { index, minutes ->
            SegmentedButton(
                selected = selected == minutes,
                onClick = { onSelect(minutes) },
                shape = SegmentedButtonDefaults.itemShape(index, ReminderOptions.size),
            ) {
                Text(
                    if (minutes == 0) {
                        stringResource(CoreR.string.common_off)
                    } else {
                        pluralStringResource(R.plurals.settings_reminder_minutes, minutes, minutes)
                    },
                    fontSize = 13.sp,
                )
            }
        }
    }
}

@Composable
private fun ExamReminderLeadSelector(selected: Int, onSelect: (Int) -> Unit) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        ExamReminderOptions.forEachIndexed { index, minutes ->
            SegmentedButton(
                selected = selected == minutes,
                onClick = { onSelect(minutes) },
                shape = SegmentedButtonDefaults.itemShape(index, ExamReminderOptions.size),
            ) {
                Text(examReminderLabel(minutes), fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun examReminderLabel(minutes: Int): String = when {
    minutes == 0 -> stringResource(CoreR.string.common_off)
    minutes % (24 * 60) == 0 -> (minutes / (24 * 60)).let { pluralStringResource(R.plurals.settings_reminder_days, it, it) }
    minutes % 60 == 0 -> (minutes / 60).let { pluralStringResource(R.plurals.settings_reminder_hours, it, it) }
    else -> pluralStringResource(R.plurals.settings_reminder_minutes, minutes, minutes)
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

/** Android 12 以下无精确闹钟授权一说，恒为 true。 */
private fun canScheduleExactAlarms(alarmManager: AlarmManager?): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager?.canScheduleExactAlarms() == true
