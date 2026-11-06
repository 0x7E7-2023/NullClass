package com.nullclass.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DisplayMode
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nullclass.core.model.ClassMove
import com.nullclass.core.model.ScheduleBlock
import com.nullclass.core.model.Term
import com.nullclass.core.ui.R as CoreR
import com.nullclass.core.ui.i18n.periodRangeLabel
import com.nullclass.core.ui.layout.LocalWindowSize
import java.time.LocalDate

/**
 * 单次调课：把某一天某一节的某门课挪到另一天/另一节，原位置这一次空出来。
 * 四步：原日期 → 哪门课 → 目标日期 → 目标节次（课长沿用原课，只挑从第几节开始）。
 */
@Composable
internal fun ClassMoveSection(
    data: ClassMoveData?,
    onAdd: (blockId: String, fromEpochDay: Long, toEpochDay: Long, startPeriod: Int, endPeriod: Int) -> Unit,
    onDelete: (id: Long) -> Unit,
) {
    Text(
        stringResource(R.string.settings_class_move_title),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 12.dp),
    )
    Text(
        stringResource(R.string.settings_class_move_desc),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (data == null) return

    // 步骤与已选值都要 rememberSaveable：转屏重建时进度不能静默归零（同串课两步流程）
    var step by rememberSaveable { mutableStateOf(0) }
    var fromDay by rememberSaveable { mutableStateOf<Long?>(null) }
    var blockId by rememberSaveable { mutableStateOf<String?>(null) }
    var toDay by rememberSaveable { mutableStateOf<Long?>(null) }
    val close = {
        step = 0
        fromDay = null
        blockId = null
        toDay = null
    }

    OutlinedButton(
        onClick = {
            close()
            step = 1
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.settings_class_move_add))
    }

    val from = fromDay
    val to = toDay
    val picked = blockId?.let { id -> from?.let { day -> data.classesOn(day).firstOrNull { it.block.id == id } } }
    when {
        step == 1 -> TermDayPickerDialog(
            term = data.term,
            stepKey = 1,
            title = stringResource(R.string.settings_class_move_step1),
            initialDay = defaultDisplayedDay(data.term),
            confirmLabel = stringResource(R.string.settings_day_swap_next),
            onDismiss = close,
            onConfirm = { day ->
                fromDay = day
                step = 2
            },
        )

        step == 2 && from != null -> {
            val classes = data.classesOn(from)
            AlertDialog(
                onDismissRequest = close,
                title = { Text(stringResource(R.string.settings_class_move_step2)) },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        if (classes.isEmpty()) {
                            Text(stringResource(R.string.settings_class_move_no_class))
                        }
                        classes.forEach { placed ->
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        blockId = placed.block.id
                                        step = 3
                                    }
                                    .padding(vertical = 10.dp),
                            ) {
                                Text(placed.course.name, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    periodRangeLabel(placed.block),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = close) { Text(stringResource(CoreR.string.common_cancel)) }
                },
            )
        }

        step == 3 && from != null && picked != null -> TermDayPickerDialog(
            term = data.term,
            stepKey = 3,
            title = stringResource(R.string.settings_class_move_step3, picked.course.name),
            initialDay = from,
            confirmLabel = stringResource(R.string.settings_day_swap_next),
            onDismiss = close,
            onConfirm = { day ->
                toDay = day
                step = 4
            },
        )

        step == 4 && from != null && picked != null && to != null -> {
            val target = to
            val length = picked.block.periodCount
            val lastStart = data.periodCount - length + 1
            var start by rememberSaveable(target) {
                mutableStateOf(picked.block.startPeriod.coerceIn(1, lastStart.coerceAtLeast(1)))
            }
            val end = start + length - 1
            // 原地不动（同一天同几节）不算调课
            val unchanged = target == from && start == picked.block.startPeriod
            val overlap = data.finalClassesOn(target).firstOrNull { other ->
                !(target == from && other.block.id == picked.block.id) &&
                    other.block.startPeriod <= end && start <= other.block.endPeriod
            }
            AlertDialog(
                onDismissRequest = close,
                title = { Text(stringResource(R.string.settings_class_move_step4, dayLabel(target))) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (lastStart < 1) {
                            Text(stringResource(R.string.settings_class_move_no_room))
                        } else {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                (1..lastStart).forEach { s ->
                                    FilterChip(
                                        selected = s == start,
                                        onClick = { start = s },
                                        label = { Text(periodRangeLabel(periodsOnly(s, s + length - 1))) },
                                    )
                                }
                            }
                        }
                        if (overlap != null) {
                            Text(
                                stringResource(R.string.settings_class_move_overlap, overlap.course.name),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = lastStart >= 1 && !unchanged,
                        onClick = {
                            onAdd(picked.block.id, from, target, start, end)
                            close()
                        },
                    ) { Text(stringResource(CoreR.string.common_confirm)) }
                },
                dismissButton = {
                    TextButton(onClick = close) { Text(stringResource(CoreR.string.common_cancel)) }
                },
            )
        }

        // 选中的课在中途没了（课被删、学期换了）：收起流程，不留半截弹窗
        step != 0 -> LaunchedEffect(Unit) { close() }
    }

    val today = remember { LocalDate.now().toEpochDay() }
    // shortcut: 原课块已删的记录不列（也不生效），留在库里无害；要清理时再加「失效记录」入口
    val upcoming = data.moves
        .filter { it.fromEpochDay >= today || it.toEpochDay >= today }
        .mapNotNull { move -> data.placedOf(move.blockId)?.let { move to it } }
    if (upcoming.isEmpty()) {
        Text(
            stringResource(R.string.settings_class_move_empty),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        upcoming.forEach { (move, placed) ->
            ClassMoveRow(move, placed.course.name, placed.block, onDelete = { onDelete(move.id) })
        }
    }
}

/** 只为拼「第 N–M 节」文案用的课块，周次星期无意义。 */
private fun periodsOnly(start: Int, end: Int) =
    ScheduleBlock(startWeek = 1, endWeek = 1, dayOfWeek = 1, startPeriod = start, endPeriod = end)

/** 单次调课行：「高等数学」/「10月9日 · 周五 3-4节 → 10月12日 · 周日 5-6节」+ 删除。 */
@Composable
private fun ClassMoveRow(move: ClassMove, courseName: String, original: ScheduleBlock, onDelete: () -> Unit) {
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
                Text(courseName, style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(
                        R.string.settings_class_move_row,
                        dayLabel(move.fromEpochDay),
                        periodRangeLabel(original),
                        dayLabel(move.toEpochDay),
                        periodRangeLabel(periodsOnly(move.toStartPeriod, move.toEndPeriod)),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(CoreR.string.common_delete))
            }
        }
    }
}

/** 限定在学期内的选日弹窗；[stepKey] 换了就换一份选择器状态（不沿用上一步选中的日子）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TermDayPickerDialog(
    term: Term,
    stepKey: Int,
    title: String,
    initialDay: Long,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit,
) {
    key(stepKey) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = null,
            initialDisplayedMonthMillis = initialDay * MILLIS_PER_DAY,
            selectableDates = remember(term) { termSelectableDates(term) },
            initialDisplayMode = if (LocalWindowSize.current.isCompactHeight) DisplayMode.Input else DisplayMode.Picker,
        )
        DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                val picked = pickerState.selectedDateMillis?.let { epochDayOfMillis(it) }
                TextButton(enabled = picked != null, onClick = { picked?.let(onConfirm) }) { Text(confirmLabel) }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(CoreR.string.common_cancel)) }
            },
        ) {
            // 提示走 DatePicker 的 title 槽，理由见 DaySwapSection
            DatePicker(
                state = pickerState,
                title = { Text(title, modifier = Modifier.padding(start = 24.dp, end = 12.dp, top = 16.dp)) },
            )
        }
    }
}
