package com.nullclass.feature.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.nullclass.core.model.PlacedBlock
import com.nullclass.core.model.ScheduleFormat
import com.nullclass.core.model.Session
import com.nullclass.core.model.TodaySnapshot
import com.nullclass.core.ui.theme.courseColor

/**
 * 今日 Tab：当天课程的时间轴列表。
 *
 * @param onEditCourse 详情弹层里编辑课程
 * @param onEditTerm 无学期时引导创建
 */
@Composable
fun TodayScreen(
    onEditCourse: (courseId: String) -> Unit,
    onEditTerm: (termId: String?) -> Unit,
    viewModel: TodayViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    var detailBlock by remember { mutableStateOf<PlacedBlock?>(null) }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        when (val s = state) {
            TodayUiState.Loading -> Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            TodayUiState.NoTerm -> Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("还没有学期", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "创建一个学期开始排课",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = { onEditTerm(null) }) { Text("创建学期") }
                }
            }

            is TodayUiState.Ready -> {
                val ready = s
                val nowMinute = rememberNowMinute()
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                ) {
                    TodayHeader(snapshot = ready.snapshot, nowMinute = nowMinute)

                    if (ready.snapshot.blocks.isEmpty()) {
                        EmptyDay(weekNumber = ready.snapshot.weekNumber)
                    } else {
                        ready.snapshot.blocks
                            .groupBy { it.session }
                            .forEach { (session, entries) ->
                                Text(
                                    sessionLabel(session),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
                                )
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    entries.forEach { entry ->
                                        TodayCard(
                                            entry = entry,
                                            inProgress = ready.snapshot.inProgress(entry, nowMinute),
                                            finished = nowMinute >= 0 && nowMinute >= entry.endMinuteOfDay,
                                            onClick = { detailBlock = entry.placed },
                                        )
                                    }
                                }
                            }
                    }
                }

                detailBlock?.let { placed ->
                    CourseDetailSheet(
                        placed = placed,
                        courseWithBlocks = ready.schedule.firstOrNull { it.course.id == placed.course.id },
                        onEdit = {
                            detailBlock = null
                            onEditCourse(placed.course.id)
                        },
                        onDelete = {
                            viewModel.deleteCourse(placed.course.id)
                            detailBlock = null
                        },
                        onDismiss = { detailBlock = null },
                    )
                }
            }
        }
    }
}

/** 顶部标题区：日期 + 周次；有课时附一句「正在上/下一节」状态。 */
@Composable
private fun TodayHeader(snapshot: TodaySnapshot, nowMinute: Int) {
    // 不 remember：nowMinute 每 30 秒一拍触发重组，跨天后日期随之更新
    val today = java.time.LocalDate.now()
    Column(modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)) {
        Text(
            "${today.monthValue}月${today.dayOfMonth}日 · ${ScheduleFormat.dayOfWeekLabel(today.dayOfWeek.value)}",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            when {
                snapshot.weekNumber == null -> "今天不在学期内"
                else -> "第 ${snapshot.weekNumber} 周"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val status = when {
            nowMinute < 0 || snapshot.blocks.isEmpty() -> null
            snapshot.blocks.any { snapshot.inProgress(it, nowMinute) } ->
                snapshot.blocks.first { snapshot.inProgress(it, nowMinute) }.let {
                    "正在上：${it.placed.course.name}"
                }
            else -> snapshot.nextUp(nowMinute)?.let { "下一节：${it.placed.course.name} · ${it.startTime} 开始" }
        }
        status?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** 单节课卡片：时间列 + 课程色条 + 名称/节次/地点；进行中高亮徽标，已结束置灰。 */
@Composable
private fun TodayCard(
    entry: TodaySnapshot.TodayEntry,
    inProgress: Boolean,
    finished: Boolean,
    onClick: () -> Unit,
) {
    val color = courseColor(entry.placed.course.colorIndex)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (finished) 0.55f else 1f)
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        color = if (inProgress) MaterialTheme.colorScheme.secondaryContainer else color.container,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .padding(vertical = 10.dp),
        ) {
            Column(
                modifier = Modifier
                    .width(64.dp)
                    .padding(start = 12.dp),
            ) {
                Text(entry.startTime, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(
                    entry.endTime,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(2.dp))
                    .background(color.content),
            )
            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                Text(
                    entry.placed.course.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    buildList {
                        add(ScheduleFormat.periodRange(entry.placed.block))
                        entry.placed.block.location?.takeIf { it.isNotBlank() }?.let { add(it) }
                    }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (inProgress) {
                Text(
                    "进行中",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(start = 8.dp, end = 12.dp)
                        .background(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                            RoundedCornerShape(4.dp),
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
}

/** 无课空态：在学期内 vs 学期外措辞不同。 */
@Composable
private fun EmptyDay(weekNumber: Int?) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 64.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (weekNumber == null) "今天不在学期内" else "今天没有课",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                if (weekNumber == null) "到期末再回来看看" else "好好休息",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun sessionLabel(session: Int): String = when (session) {
    Session.MORNING -> "上午"
    Session.AFTERNOON -> "下午"
    Session.EVENING -> "晚上"
    else -> "其他"
}
