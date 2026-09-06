package com.nullclass.feature.edit

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * 课程编辑界面。
 *
 * TODO(M2): 课程名 / 教师 / 颜色选择 + 多时间安排（周次范围、单双周、
 * 星期、节次范围、教室）的增删改。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseEditScreen() {
    Scaffold(
        topBar = { TopAppBar(title = { Text("添加课程") }) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "编辑界面开发中",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
