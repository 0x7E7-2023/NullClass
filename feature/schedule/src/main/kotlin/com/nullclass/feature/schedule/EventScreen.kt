package com.nullclass.feature.schedule

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DisplayMode
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.nullclass.core.model.CalendarEvent
import com.nullclass.core.model.EventReminderPlanner
import com.nullclass.core.model.ExamFormat
import com.nullclass.core.model.ScheduleFormat
import com.nullclass.core.ui.layout.AdaptiveWidthWrapper
import com.nullclass.core.ui.layout.LocalWindowSize
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.UUID

private val WeekdayLabels = listOf("一", "二", "三", "四", "五", "六", "日")

/** 定时日程的提醒提前量（分钟）；全天日程只有开/关，开 = 当天 8:00。 */
private val LeadOptions = listOf(0, 5, 15, 30, 60, 24 * 60)

/**
 * 日程安排（课表页右上角进入）：月历 + 选中日的日程 + 近期日程；
 * 新增/编辑都在本页弹窗里完成。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventScreen(
    onBack: () -> Unit,
    viewModel: EventViewModel = hiltViewModel(),
) {
    val events by viewModel.events.collectAsState()
    val message by viewModel.message.collectAsState()
    // ponytail: 页面打开期间跨零点「今天」不刷新，重进页面即更新
    val today = remember { LocalDate.now() }
    var month by rememberSaveable { mutableStateOf(YearMonth.from(today)) }
    var selectedDay by rememberSaveable { mutableStateOf(today.toEpochDay()) }
    var editing by remember { mutableStateOf<CalendarEvent?>(null) }
    var deleteTarget by remember { mutableStateOf<CalendarEvent?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("日程安排") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (selectedDay != today.toEpochDay() || month != YearMonth.from(today)) {
                        TextButton(onClick = {
                            month = YearMonth.from(today)
                            selectedDay = today.toEpochDay()
                        }) { Text("今天") }
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { editing = newEvent(selectedDay) }) {
                Icon(Icons.Default.Add, contentDescription = "添加日程")
            }
        },
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        val all = events
        if (all == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        val byDay = remember(all) { all.groupBy { it.dateEpochDay } }
        val dayEvents = byDay[selectedDay].orEmpty()
        val upcoming = all.filter { it.dateEpochDay >= today.toEpochDay() && it.dateEpochDay != selectedDay }.take(10)

        AdaptiveWidthWrapper(modifier = Modifier.padding(padding)) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    MonthCalendar(
                        month = month,
                        today = today.toEpochDay(),
                        selectedDay = selectedDay,
                        eventDays = byDay.keys,
                        onMonthChange = { month = it },
                        onSelectDay = { selectedDay = it },
                    )
                }
                item { SectionTitle("${ExamFormat.monthDayLabel(selectedDay)} ${weekdayLabel(selectedDay)}") }
                if (dayEvents.isEmpty()) {
                    item { HintText("这天没有日程，点右下角 + 添加") }
                } else {
                    items(dayEvents, key = { "day-${it.id}" }) { event ->
                        EventCard(event, showDate = false, onClick = { editing = event }, onDelete = { deleteTarget = event })
                    }
                }
                if (upcoming.isNotEmpty()) {
                    item { SectionTitle("近期日程") }
                    items(upcoming, key = { "up-${it.id}" }) { event ->
                        EventCard(event, showDate = true, onClick = { editing = event }, onDelete = { deleteTarget = event })
                    }
                }
            }
        }
    }

    editing?.let { initial ->
        EventEditorDialog(
            initial = initial,
            onDismiss = { editing = null },
            onSave = { event ->
                viewModel.save(event) {
                    // 保存到别的日期时跟过去，免得用户以为没存上
                    selectedDay = event.dateEpochDay
                    month = YearMonth.from(LocalDate.ofEpochDay(event.dateEpochDay))
                    editing = null
                }
            },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除日程") },
            text = { Text("确定删除「${target.title}」吗？") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(target.id)
                    deleteTarget = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } },
        )
    }

    message?.let {
        AlertDialog(
            onDismissRequest = viewModel::dismissMessage,
            title = { Text("操作失败") },
            text = { Text(it) },
            confirmButton = { TextButton(onClick = viewModel::dismissMessage) { Text("知道了") } },
        )
    }
}

/** 新日程默认：选中日、下一个整点（23 点后取 23:59，不落在过去）、提前 15 分钟提醒。 */
private fun newEvent(day: Long): CalendarEvent {
    val start = minOf((LocalTime.now().hour + 1) * 60, 24 * 60 - 1)
    // 打开弹窗就定 id：保存是异步的，连点两下「保存」只会 upsert 同一条
    return CalendarEvent(
        id = UUID.randomUUID().toString(),
        dateEpochDay = day,
        startMinuteOfDay = start,
        remindLeadMinutes = 15,
    )
}

private fun weekdayLabel(epochDay: Long): String =
    "周" + WeekdayLabels[LocalDate.ofEpochDay(epochDay).dayOfWeek.value - 1]

private fun leadLabel(lead: Int?, allDay: Boolean): String = when {
    lead == null -> "不提醒"
    lead == 0 -> if (allDay) "当天 8:00" else "准时"
    lead % (24 * 60) == 0 -> "提前 ${lead / (24 * 60)} 天"
    lead % 60 == 0 -> "提前 ${lead / 60} 小时"
    else -> "提前 $lead 分钟"
}

@Composable
private fun MonthCalendar(
    month: YearMonth,
    today: Long,
    selectedDay: Long,
    eventDays: Set<Long>,
    onMonthChange: (YearMonth) -> Unit,
    onSelectDay: (Long) -> Unit,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onMonthChange(month.minusMonths(1)) }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "上个月")
            }
            Text(
                "${month.year}年${month.monthValue}月",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { onMonthChange(month.plusMonths(1)) }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "下个月")
            }
        }
        Row {
            WeekdayLabels.forEach {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).padding(vertical = 4.dp),
                )
            }
        }
        // 周一起始；前导空格 = 1 号是周几 - 1
        val first = month.atDay(1).toEpochDay()
        val offset = month.atDay(1).dayOfWeek.value - 1
        val cells = offset + month.lengthOfMonth()
        for (row in 0 until (cells + 6) / 7) {
            Row {
                for (col in 0 until 7) {
                    val index = row * 7 + col - offset
                    Box(Modifier.weight(1f).height(48.dp), contentAlignment = Alignment.Center) {
                        if (index in 0 until month.lengthOfMonth()) {
                            val day = first + index
                            DayCell(
                                dayOfMonth = index + 1,
                                selected = day == selectedDay,
                                isToday = day == today,
                                hasEvents = day in eventDays,
                                onClick = { onSelectDay(day) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(dayOfMonth: Int, selected: Boolean, isToday: Boolean, hasEvents: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = if (selected) colors.primary else Color.Transparent,
            border = if (isToday && !selected) BorderStroke(1.dp, colors.primary) else null,
            modifier = Modifier.size(36.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    "$dayOfMonth",
                    style = MaterialTheme.typography.bodyMedium,
                    color = when {
                        selected -> colors.onPrimary
                        isToday -> colors.primary
                        else -> colors.onSurface
                    },
                )
            }
        }
        Box(
            Modifier
                .padding(top = 2.dp)
                .size(4.dp)
                .clip(CircleShape)
                .background(if (hasEvents) colors.primary else Color.Transparent),
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 12.dp),
    )
}

@Composable
private fun HintText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun EventCard(event: CalendarEvent, showDate: Boolean, onClick: () -> Unit, onDelete: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(event.title, style = MaterialTheme.typography.titleMedium)
                val time = EventReminderPlanner.timeLabel(event)
                Text(
                    (if (showDate) "${ExamFormat.monthDayLabel(event.dateEpochDay)} ${weekdayLabel(event.dateEpochDay)} · " else "") +
                        "$time · ${leadLabel(event.remindLeadMinutes, event.startMinuteOfDay == null)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                event.note?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "删除日程")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EventEditorDialog(
    initial: CalendarEvent,
    onDismiss: () -> Unit,
    onSave: (CalendarEvent) -> Unit,
) {
    var title by rememberSaveable { mutableStateOf(initial.title) }
    var day by rememberSaveable { mutableStateOf(initial.dateEpochDay) }
    var allDay by rememberSaveable { mutableStateOf(initial.startMinuteOfDay == null) }
    var startMinute by rememberSaveable { mutableStateOf(initial.startMinuteOfDay ?: 9 * 60) }
    var note by rememberSaveable { mutableStateOf(initial.note.orEmpty()) }
    var remind by rememberSaveable { mutableStateOf(initial.remindLeadMinutes != null) }
    // 全天存 0，切回定时后默认给 15 分钟
    var lead by rememberSaveable { mutableStateOf(initial.remindLeadMinutes?.takeIf { it > 0 || initial.startMinuteOfDay != null } ?: 15) }
    var showLeadMenu by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.title.isEmpty()) "添加日程" else "编辑日程") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("标题 *") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(onClick = { showDatePicker = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("${ExamFormat.dateLabel(day)} ${weekdayLabel(day)}")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("全天", modifier = Modifier.weight(1f))
                    Switch(checked = allDay, onCheckedChange = { allDay = it })
                }
                if (!allDay) {
                    OutlinedButton(onClick = { showTimePicker = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("开始时间 ${ScheduleFormat.minuteLabel(startMinute)}")
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (allDay && remind) "提醒（当天 8:00）" else "提醒", modifier = Modifier.weight(1f))
                    Switch(checked = remind, onCheckedChange = { remind = it })
                }
                if (remind && !allDay) {
                    Box {
                        OutlinedButton(onClick = { showLeadMenu = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(leadLabel(lead, allDay = false))
                        }
                        DropdownMenu(expanded = showLeadMenu, onDismissRequest = { showLeadMenu = false }) {
                            LeadOptions.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(leadLabel(option, allDay = false)) },
                                    onClick = { lead = option; showLeadMenu = false },
                                )
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("备注（可选）") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = title.isNotBlank(),
                onClick = {
                    onSave(
                        initial.copy(
                            title = title.trim(),
                            dateEpochDay = day,
                            startMinuteOfDay = if (allDay) null else startMinute,
                            note = note.trim().takeIf { it.isNotEmpty() },
                            remindLeadMinutes = when {
                                !remind -> null
                                allDay -> 0
                                else -> lead
                            },
                        ),
                    )
                },
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )

    if (showDatePicker) {
        // DatePicker 的毫秒是 UTC 零点（同考试编辑页）
        val state = rememberDatePickerState(
            initialSelectedDateMillis = LocalDate.ofEpochDay(day).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
            initialDisplayMode = if (LocalWindowSize.current.isCompactHeight) DisplayMode.Input else DisplayMode.Picker,
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let {
                        day = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay()
                    }
                    showDatePicker = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("取消") } },
        ) { DatePicker(state = state) }
    }

    if (showTimePicker) {
        val state = rememberTimePickerState(startMinute / 60, startMinute % 60, is24Hour = true)
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text("开始时间") },
            text = {
                // 矮屏放不下表盘，改键盘输入
                if (LocalWindowSize.current.isCompactHeight) TimeInput(state) else TimePicker(state)
            },
            confirmButton = {
                TextButton(onClick = {
                    startMinute = state.hour * 60 + state.minute
                    showTimePicker = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showTimePicker = false }) { Text("取消") } },
        )
    }
}
