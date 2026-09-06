package com.nullclass.feature.settings.transfer

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 二维码分享弹层：课表二维码 + 截图分享（cache PNG 经 FileProvider ACTION_SEND）。 */
@Composable
internal fun QrShareDialog(
    payload: String,
    viewModel: TransferViewModel,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val bitmap = remember(payload) { QrBitmap.encode(payload) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("扫码导入课表") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "课表二维码",
                    modifier = Modifier.fillMaxWidth().padding(4.dp),
                )
                Text(
                    "让另一台设备用空课扫码即可导入整份课表。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    val file = withContext(Dispatchers.IO) { writeQrPng(context, bitmap) }
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                    context.startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).apply {
                                type = "image/png"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            },
                            "分享课表二维码",
                        ),
                    )
                }
            }) { Text("分享图片") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

private fun writeQrPng(context: android.content.Context, bitmap: android.graphics.Bitmap): File {
    val dir = File(context.cacheDir, "shared").apply { mkdirs() }
    val file = File(dir, "nullclass-qr.png")
    file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    return file
}
