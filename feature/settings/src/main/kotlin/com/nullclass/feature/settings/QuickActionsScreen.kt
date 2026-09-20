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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.nullclass.core.model.DayOverride
import com.nullclass.core.model.ScheduleFormat
import com.nullclass.core.model.Term
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 「我的 → 快捷操作」：学期当中随时会用一下的操作，目前是调课（串课）。
 *
 * 独立成页而不是散在设置里：这些是「今天要做的事」，不是配一次就不动的开关。
 * 调课原先寄居在「通知与提醒」页尾，调休时要翻到最底下才找得到，与那页主题也不搭。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickActionsScreen(
    onBack: () -> Unit,
    viewModel: DaySwapViewModel = hiltViewModel(),
) {
    val term by viewModel.currentTerm.collectAsState()
    val overrides by viewModel.dayOverrides.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("快捷操作") },
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
            DaySwapSection(
                term = term,
                overrides = overrides,
                onSet = viewModel::setDayOverride,
                onClear = viewModel::clearDayOverride,
            )
        }
    }
}

/**
 * 调课（串课）：把某一天设成上另一天的课，调休专用。
 *
 * 周视图点某一列的星期表头是更快的就地入口，这里是总览与兜底（能看见全部、能删）。
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
    Text(
        "今天及以后",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (upcoming.isEmpty()) {
        Text(
            "暂无调课",
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
