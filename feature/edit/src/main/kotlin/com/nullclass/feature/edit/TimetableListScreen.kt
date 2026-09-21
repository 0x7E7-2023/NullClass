package com.nullclass.feature.edit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.nullclass.core.ui.layout.AdaptiveWidthWrapper

/**
 * 课表管理：列表切换当前课表、重命名、删除、新建。
 * 点整行 = 切为当前课表（今日/课表/小组件/提醒全部跟着切）；与学期管理同款交互。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimetableListScreen(
    onBack: () -> Unit,
    onCreateTimetable: () -> Unit,
    viewModel: TimetableListViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    var pendingDelete by remember { mutableStateOf<TimetableListItem?>(null) }
    // 重命名对话框里有用户正在输入的文本，旋转重建后不该连对话框带内容一起消失。
    // TimetableListItem 不是 Parcelable，存 id 再从列表查回。
    // （pendingDelete 是纯确认框、没有输入，旋转关掉即可，不值得一并复杂化。）
    var pendingRenameId by rememberSaveable { mutableStateOf<String?>(null) }
    val pendingRename = pendingRenameId?.let { id ->
        state.items.firstOrNull { it.timetable.id == id }
    }

    val appBarScrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(appBarScrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text("课表管理") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(onClick = onCreateTimetable) { Text("新建") }
                },
                scrollBehavior = appBarScrollBehavior,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        if (state.items.isEmpty()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("还没有课表", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "新建一张开始排课",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = onCreateTimetable) { Text("创建课表") }
                }
            }
        } else {
            AdaptiveWidthWrapper(modifier = Modifier.padding(padding)) {
                LazyColumn(
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.items, key = { it.timetable.id }) { item ->
                        TimetableRow(
                            item = item,
                            onSelect = { viewModel.setActive(item.timetable.id) },
                            onRename = { pendingRenameId = item.timetable.id },
                            onDelete = { pendingDelete = item },
                        )
                    }
                }
            }
        }
    }

    pendingDelete?.let { item ->
        // 最后一张不删：删光后「当前课表」没了落点，引导页也不该在老用户面前复活
        if (state.items.size <= 1) {
            AlertDialog(
                onDismissRequest = { pendingDelete = null },
                title = { Text("无法删除") },
                text = { Text("至少保留一张课表。要清空内容，用学期管理删除里面的学期。") },
                confirmButton = {
                    TextButton(onClick = { pendingDelete = null }) { Text("知道了") }
                },
            )
        } else {
            AlertDialog(
                onDismissRequest = { pendingDelete = null },
                title = { Text("删除课表") },
                text = {
                    Text(
                        "将删除课表「${item.timetable.name}」及其 ${item.termCount} 个学期和全部课程。" +
                            "删除会同步到其他设备。",
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            pendingDelete = null
                            viewModel.delete(item.timetable.id)
                        },
                    ) { Text("删除", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = {
                    TextButton(onClick = { pendingDelete = null }) { Text("取消") }
                },
            )
        }
    }

    pendingRename?.let { item ->
        // 用 id 而非 item 实例作组合键：实例每次重组都可能是新的，会把输入打回原名
        var name by rememberSaveable(item.timetable.id) { mutableStateOf(item.timetable.name) }
        AlertDialog(
            onDismissRequest = { pendingRenameId = null },
            title = { Text("重命名课表") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("课表名称") },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val trimmed = name.trim()
                        pendingRenameId = null
                        if (trimmed.isNotEmpty()) viewModel.rename(item.timetable.id, trimmed)
                    },
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRenameId = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun TimetableRow(
    item: TimetableListItem,
    onSelect: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        onClick = onSelect,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 6.dp),
            ) {
                Text(
                    item.timetable.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "${item.termCount} 个学期",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (item.isActive) {
                Text(
                    "当前",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(end = 4.dp),
                )
            }
            IconButton(onClick = onRename) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = "重命名课表",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "删除课表",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
