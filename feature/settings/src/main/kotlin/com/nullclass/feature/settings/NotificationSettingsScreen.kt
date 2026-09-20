package com.nullclass.feature.settings

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
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.nullclass.core.model.DayOverride
import com.nullclass.core.model.ScheduleFormat
import com.nullclass.core.model.SkipDate
import com.nullclass.core.model.SkipDateType
import com.nullclass.core.model.Term
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 通知与提醒：权限引导（通知/自启动/电池/精确闹钟/勿扰）、提前提醒时间、
 * 节假日在线同步、跳过日期与调课（串课）管理。
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
    val dayOverrides by viewModel.dayOverrides.collectAsState()
    val currentTerm by viewModel.currentTerm.collectAsState()
    val holidayBusy by viewModel.holidayBusy.collectAsState()
    val holidayMessage by viewModel.holidayMessage.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("通知与提醒") },
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
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ---- 权限引导 ----
            Text("权限与后台", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "提醒依赖系统通知与后台权限。逐项检查，未授权的可以一键跳到对应系统页。",
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
            Text("课前提醒", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "每节课开始前发系统通知。默认方案非精确（省电策略下可能有几分钟误差）；" +
                    "需要准时可在上面开启「精确提醒」。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ReminderLeadSelector(
                selected = reminderLeadMinutes,
                onSelect = viewModel::setReminderLeadMinutes,
            )
            Text("考试提醒", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "有具体时间时按考试开始时间提醒；未填写时间时，以考试日 08:00 作为提醒基准。" +
                    "节假日不会跳过考试提醒（考试日期是学校定的）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ExamReminderLeadSelector(
                selected = examReminderLeadMinutes,
                onSelect = viewModel::setExamReminderLeadMinutes,
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // ---- 节假日与跳过日期 ----
            Text("节假日与跳过日期", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "在线同步法定节假日（逐年多数据源自动降级：timor.tech → holiday-cn → Nager.Date），" +
                    "落在这天的课前提醒自动跳过。也可以手动添加自己的跳过日期。" +
                    "调休补班日仅作提示，不会生成课程。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ToggleRow(
                title = "在线同步节假日",
                subtitle = "每周自动同步一次当前学期覆盖的年份",
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
                        "上次同步：$time",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Text(
                        "从未同步",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                }
                OutlinedButton(onClick = viewModel::refreshHolidays, enabled = !holidayBusy) {
                    Text(if (holidayBusy) "同步中…" else "立即同步")
                }
            }
            holidayMessage?.let { message ->
                Text(
                    message.text,
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
                Text("添加跳过日期")
            }
            if (showDatePicker) {
                val pickerState = androidx.compose.material3.rememberDatePickerState(
                    initialSelectedDateMillis = LocalDate.now().toEpochDay() * 86_400_000L,
                )
                DatePickerDialog(
                    onDismissRequest = { showDatePicker = false },
                    confirmButton = {
                        TextButton(onClick = {
                            pickerState.selectedDateMillis?.let { millis ->
                                viewModel.addManualDate(millis / 86_400_000L)
                            }
                            showDatePicker = false
                        }) { Text("确定") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showDatePicker = false }) { Text("取消") }
                    },
                ) {
                    DatePicker(state = pickerState)
                }
            }

            val today = remember { LocalDate.now().toEpochDay() }
            val upcoming = skipDates.filter { it.epochDay >= today }
            if (upcoming.isEmpty()) {
                Text(
                    "今天及以后暂无跳过日期",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    "今天及以后（${upcoming.size} 条，往前的已隐藏）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                upcoming.forEach { skip ->
                    SkipDateRow(skip = skip, onDelete = { viewModel.removeDate(skip.epochDay) })
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // ---- 调课（串课） ----
            DaySwapSection(
                term = currentTerm,
                overrides = dayOverrides,
                onSet = viewModel::setDayOverride,
                onClear = viewModel::clearDayOverride,
            )
        }
    }
}

/**
 * 调课（串课）：把某一天设成上另一天的课，调休专用。
 *
 * 周视图点表头那一列是更快的入口，这里是总览与兜底（能看见全部、能删）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DaySwapSection(
    term: Term?,
    overrides: List<DayOverride>,
    onSet: (epochDay: Long, sourceEpochDay: Long) -> Unit,
    onClear: (epochDay: Long) -> Unit,
) {
    Text("调课（串课）", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    Text(
        "调休时把某一天设成上另一天的课（如「周六上周五的课」）。今日页、周视图、" +
            "小组件和课前提醒会一起跟着改，上课时间仍按这一天的作息。" +
            "在周视图里点某一列的星期表头，也能直接调这一天。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    // null = 关着；pending 为 null = 正在选「哪一天」，非 null = 正在选「上哪天的课」
    var picking by remember { mutableStateOf(false) }
    var pendingTarget by remember { mutableStateOf<Long?>(null) }

    if (term == null) {
        Text(
            "还没有学期，先建一个学期再来调课",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        Button(
            onClick = {
                pendingTarget = null
                picking = true
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("添加调课")
        }
    }

    if (picking && term != null) {
        val target = pendingTarget
        // key(target)：rememberDatePickerState 内部是**无 key 的** rememberSaveable，
        // 不换组合键的话第 2 步会原样沿用第 1 步选中的那天——选完目标日顺手点确定
        // 就成了「这天上它自己的课」，仓库层当取消处理，静默什么都不发生。
        key(target) {
            val pickerState = androidx.compose.material3.rememberDatePickerState(
                // 两步都从「未选中」开始：选过才让点下一步/确定
                initialSelectedDateMillis = null,
                initialDisplayedMonthMillis = (target ?: defaultDisplayedDay(term)) * MILLIS_PER_DAY,
                // 学期外的日子没有课表可借、也排不出提醒，直接不让选
                selectableDates = remember(term) { termSelectableDates(term) },
            )
            DatePickerDialog(
                onDismissRequest = {
                    picking = false
                    pendingTarget = null
                },
                confirmButton = {
                    // 向下取整而不是整除：1970 年前的日期会被整除截断成后一天
                    val picked = pickerState.selectedDateMillis?.let { Math.floorDiv(it, MILLIS_PER_DAY) }
                    TextButton(
                        // 第 2 步选回目标日自己 = 不调课，不让确认
                        enabled = picked != null && (target == null || picked != target),
                        onClick = {
                            val day = picked ?: return@TextButton
                            if (target == null) {
                                // 第一步选完「哪一天」，接着选来源日
                                pendingTarget = day
                            } else {
                                onSet(target, day)
                                picking = false
                                pendingTarget = null
                            }
                        },
                    ) { Text(if (target == null) "下一步" else "确定") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        picking = false
                        pendingTarget = null
                    }) { Text("取消") }
                },
            ) {
                Text(
                    if (target == null) {
                        "第 1 步：选哪一天要调课"
                    } else {
                        val date = LocalDate.ofEpochDay(target)
                        "第 2 步：${date.monthValue}月${date.dayOfMonth}日" +
                            "（${ScheduleFormat.dayOfWeekLabel(date.dayOfWeek.value)}）上哪天的课"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
                DatePicker(state = pickerState)
            }
        }
    }

    val today = remember { LocalDate.now().toEpochDay() }
    val upcoming = overrides.filter { it.epochDay >= today }
    if (upcoming.isEmpty()) {
        Text(
            "今天及以后暂无调课",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        upcoming.forEach { override ->
            DayOverrideRow(override = override, onDelete = { onClear(override.epochDay) })
        }
    }
}

/** 调课行：「10月11日 周六 → 上 10月9日 周五 的课」+ 删除。 */
@Composable
private fun DayOverrideRow(override: DayOverride, onDelete: () -> Unit) {
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
                Text(
                    dayLabel(override.epochDay),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    "上 ${dayLabel(override.sourceEpochDay)} 的课",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "删除")
            }
        }
    }
}

/** 「10月11日 · 周六」；跨年时补年份（与跳过日期列表同一口径）。 */
private fun dayLabel(epochDay: Long): String {
    val date = LocalDate.ofEpochDay(epochDay)
    val yearPrefix = if (date.year != LocalDate.now().year) "${date.year}年" else ""
    return yearPrefix + date.format(DateTimeFormatter.ofPattern("M月d日")) +
        " · ${ScheduleFormat.dayOfWeekLabel(date.dayOfWeek.value)}"
}

/** DatePicker 的毫秒口径是 UTC 零点。 */
private const val MILLIS_PER_DAY = 86_400_000L

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
            title = "通知权限",
            status = if (notificationGranted) "已授权" else "未授权，收不到任何提醒",
            granted = notificationGranted,
            actionLabel = "去授权",
            onAction = { requestNotificationPermission(context, permissionLauncher) },
        )

        PermissionRow(
            title = "忽略电池优化",
            status = if (batteryIgnored) "已豁免" else "未豁免，后台提醒可能被推迟",
            granted = batteryIgnored,
            actionLabel = "去设置",
            onAction = { BackgroundReliability.startBatteryOptimizationSettings(context) },
        )

        val autostartPage = remember { BackgroundReliability.resolvedAutostartActivity(context) }
        if (autostartPage != null) {
            PermissionRow(
                title = "厂商自启动",
                // 自启动状态无法查询，只能引导用户去系统页自查
                status = "部分厂商需单独允许空课自启动",
                granted = null,
                actionLabel = "去设置",
                onAction = { BackgroundReliability.startAutostartSettings(context) },
            )
        }

        PermissionRow(
            title = "精确闹钟权限",
            status = when {
                !exactReminder -> "未开启精确提醒（当前为普通提醒，误差几分钟）"
                exactAllowed -> "已授权，提醒将到点准时"
                else -> "已开启但未授权，仍按普通提醒工作"
            },
            granted = if (!exactReminder) null else exactAllowed,
            actionLabel = if (exactAllowed) null else "去授权",
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
            title = "精确提醒",
            subtitle = "课程提醒改用系统精确闹钟，到点准时（需要上面的精确闹钟权限）",
            checked = exactReminder,
            onCheckedChange = onSetExactReminder,
        )

        PermissionRow(
            title = "勿扰模式权限",
            status = if (dndGranted) "已授权" else "未授权",
            granted = dndGranted,
            actionLabel = if (dndGranted) null else "去授权",
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
                title = "勿扰模式下响铃",
                subtitle = "开启后课前/考试提醒在勿扰模式中照常发出",
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
                // 列表会同时含跨年学期的两个年份，日期只写「M月d日」时
                // 「10月1日 · 国庆节」这类两年都有的条目看起来像重复
                val yearPrefix = if (date.year != LocalDate.now().year) "${date.year}年" else ""
                Text(
                    yearPrefix + date.format(DateTimeFormatter.ofPattern("M月d日")) +
                        " · ${ScheduleFormat.dayOfWeekLabel(date.dayOfWeek.value)}",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    when (skip.type) {
                        SkipDateType.HOLIDAY -> skip.label ?: "节假日"
                        SkipDateType.WORKDAY -> "调休补班（仅提示，不生成课程）"
                        SkipDateType.MANUAL -> "手动跳过"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "删除")
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
                Text(if (minutes == 0) "关闭" else "${minutes}分钟", fontSize = 13.sp)
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

private fun examReminderLabel(minutes: Int): String = when {
    minutes == 0 -> "关闭"
    minutes % (24 * 60) == 0 -> "${minutes / (24 * 60)}天"
    minutes % 60 == 0 -> "${minutes / 60}小时"
    else -> "${minutes}分钟"
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

/** 日期选择器默认停在哪个月：今天在学期内就是今天，否则开学那天。 */
private fun defaultDisplayedDay(term: Term): Long {
    val today = LocalDate.now().toEpochDay()
    return if (term.weekOf(today) != null) today else term.firstDayEpochDay
}

/** 日期选择器限制在学期覆盖的日子内（串课的两端都必须在学期里才有意义）。 */
@OptIn(ExperimentalMaterial3Api::class)
private fun termSelectableDates(term: Term): SelectableDates = object : SelectableDates {
    override fun isSelectableDate(utcTimeMillis: Long): Boolean =
        term.weekOf(Math.floorDiv(utcTimeMillis, MILLIS_PER_DAY)) != null

    override fun isSelectableYear(year: Int): Boolean =
        year in LocalDate.ofEpochDay(term.firstDayEpochDay).year..
            LocalDate.ofEpochDay(term.firstDayEpochDay + term.totalWeeks * 7L - 1).year
}
