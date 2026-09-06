package com.nullclass.feature.edit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nullclass.core.model.ScheduleFormat
import com.nullclass.core.model.SectionMath
import com.nullclass.core.model.WeekType
import com.nullclass.core.ui.theme.CoursePalette

/**
 * 单条时间安排编辑卡：周次范围、单双周、星期、节次（小节/大节双模式）、教室。
 */
@Composable
fun BlockEditor(
    block: EditableBlock,
    totalWeeks: Int,
    totalPeriods: Int,
    onUpdate: (EditableBlock) -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("时间安排", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Delete, contentDescription = "删除该安排", tint = MaterialTheme.colorScheme.error)
                }
            }

            // 周次范围
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberStepper(
                    label = "第",
                    value = block.startWeek,
                    range = 1..block.endWeek,
                    onChange = { onUpdate(block.copy(startWeek = it)) },
                )
                Text("—", color = MaterialTheme.colorScheme.onSurfaceVariant)
                NumberStepper(
                    label = "",
                    value = block.endWeek,
                    range = block.startWeek..totalWeeks,
                    onChange = { onUpdate(block.copy(endWeek = it)) },
                )
                Text("周", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            // 单双周
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                val options = listOf(WeekType.ALL to "每周", WeekType.ODD to "单周", WeekType.EVEN to "双周")
                options.forEachIndexed { index, (type, label) ->
                    SegmentedButton(
                        selected = block.weekType == type,
                        onClick = { onUpdate(block.copy(weekType = type)) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                    ) { Text(label, fontSize = 12.sp) }
                }
            }

            // 星期
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                val days = listOf("一", "二", "三", "四", "五", "六", "日")
                days.forEachIndexed { index, label ->
                    FilterChip(
                        selected = block.dayOfWeek == index + 1,
                        onClick = { onUpdate(block.copy(dayOfWeek = index + 1)) },
                        label = { Text(label, fontSize = 12.sp) },
                    )
                }
            }

            // 节次：小节 / 大节双模式
            PeriodPicker(
                block = block,
                totalPeriods = totalPeriods,
                onUpdate = onUpdate,
            )

            OutlinedTextField(
                value = block.location,
                onValueChange = { onUpdate(block.copy(location = it)) },
                label = { Text("教室（可选）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** 节次选择：按小节（起-止）/ 按大节（大节 N ⇄ 2N-1..2N）即时互转。 */
@Composable
private fun PeriodPicker(
    block: EditableBlock,
    totalPeriods: Int,
    onUpdate: (EditableBlock) -> Unit,
) {
    var sectionMode by remember(block.id) { mutableStateOf(false) }
    val sectionCount = SectionMath.sectionCount(totalPeriods)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("节次", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FilterChip(
                selected = !sectionMode,
                onClick = { sectionMode = false },
                label = { Text("按小节", fontSize = 12.sp) },
            )
            FilterChip(
                selected = sectionMode,
                onClick = { sectionMode = true },
                label = { Text("按大节", fontSize = 12.sp) },
            )
        }

        if (sectionMode) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                (1..sectionCount).forEach { section ->
                    val range = SectionMath.sectionPeriodRange(section)
                    val end = range.last.coerceAtMost(totalPeriods)
                    val selected = block.startPeriod == range.first && block.endPeriod == end
                    FilterChip(
                        selected = selected,
                        onClick = { onUpdate(block.copy(startPeriod = range.first, endPeriod = end)) },
                        label = { Text("大$section", fontSize = 12.sp) },
                    )
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberStepper(
                    label = "第",
                    value = block.startPeriod,
                    range = 1..block.endPeriod,
                    onChange = { onUpdate(block.copy(startPeriod = it)) },
                )
                Text("—", color = MaterialTheme.colorScheme.onSurfaceVariant)
                NumberStepper(
                    label = "",
                    value = block.endPeriod,
                    range = block.startPeriod..totalPeriods,
                    onChange = { onUpdate(block.copy(endPeriod = it)) },
                )
                Text("节", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Text(
            text = ScheduleFormat.periodRange(
                com.nullclass.core.model.ScheduleBlock(
                    startWeek = 1,
                    endWeek = 1,
                    dayOfWeek = 1,
                    startPeriod = block.startPeriod,
                    endPeriod = block.endPeriod,
                ),
            ),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 紧凑数字步进器：[−] 值 [+]，范围外禁用（核心图标集无 Remove，用文本符号）。 */
@Composable
internal fun NumberStepper(
    label: String,
    value: Int,
    range: IntRange,
    onChange: (Int) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (label.isNotEmpty()) {
            Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            text = "−",
            fontSize = 18.sp,
            color = if (value > range.first) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
            modifier = Modifier
                .size(28.dp)
                .clickable(enabled = value > range.first) { onChange((value - 1).coerceIn(range.first, range.last)) }
                .wrapContentSize(Alignment.Center),
        )
        Text(
            value.toString(),
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
        IconButton(
            onClick = { onChange((value + 1).coerceIn(range.first, range.last)) },
            enabled = value < range.last,
            modifier = Modifier.size(28.dp),
        ) {
            Icon(Icons.Default.Add, contentDescription = "加", modifier = Modifier.size(16.dp))
        }
    }
}

/** 12 色选择器。 */
@Composable
internal fun ColorPalettePicker(
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        CoursePalette.forEachIndexed { index, color ->
            val selected = index == selectedIndex
            Box(
                modifier = Modifier
                    .size(if (selected) 32.dp else 28.dp)
                    .border(
                        width = if (selected) 3.dp else 1.dp,
                        color = if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                        shape = CircleShape,
                    )
                    .padding(if (selected) 5.dp else 4.dp)
                    .background(color, CircleShape)
                    .clickable { onSelect(index) },
            )
        }
    }
}
