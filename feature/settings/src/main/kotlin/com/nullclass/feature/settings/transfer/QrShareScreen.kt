package com.nullclass.feature.settings.transfer

import com.nullclass.feature.settings.R
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.nullclass.core.ui.R as CoreR
import com.nullclass.core.ui.layout.AdaptiveDialogContent
import com.nullclass.core.ui.layout.LocalWindowSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 二维码分享弹层：课表二维码 + 截图分享（cache PNG 经 FileProvider ACTION_SEND）。 */
@Composable
internal fun QrShareDialog(
    payload: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    var bitmap by remember(payload) { mutableStateOf<Bitmap?>(null) }
    var encodeFailed by remember(payload) { mutableStateOf(false) }

    LaunchedEffect(payload) {
        try {
            bitmap = withContext(Dispatchers.Default) { QrBitmap.encode(payload) }
            encodeFailed = false
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            bitmap = null
            // zxing 的异常消息是英文的开发者信息，不给用户看
            encodeFailed = true
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_qr_share_title)) },
        text = {
            AdaptiveDialogContent(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth(),
            ) {
                val current = bitmap
                // 二维码边长封顶：aspectRatio(1f) 让高度跟着弹窗宽度走，横屏弹窗
                // 更宽就更高，会把下面的说明文字和「分享图片 / 关闭」挤出屏幕。
                // 矮屏收得更紧，保证按钮始终可见。
                val qrMaxSide = if (LocalWindowSize.current.isCompactHeight) 180.dp else 280.dp
                when {
                    current != null -> Image(
                        bitmap = current.asImageBitmap(),
                        contentDescription = stringResource(R.string.settings_qr_image_desc),
                        contentScale = ContentScale.Fit,
                        // fillMaxWidth + 1:1：高度跟弹窗宽度走，不再用位图像素当 dp
                        // （1024px 图在 mdpi/LDPlayer 上等于 1024dp，会把对话框撑破）。
                        modifier = Modifier
                            .widthIn(max = qrMaxSide)
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .padding(4.dp),
                    )
                    encodeFailed -> Text(
                        stringResource(R.string.settings_qr_render_failed),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    else -> CircularProgressIndicator()
                }
                Text(
                    stringResource(R.string.settings_qr_share_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val current = bitmap ?: return@TextButton
                    scope.launch {
                        val file = withContext(Dispatchers.IO) { writeQrPng(context, current) }
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                        context.startActivity(
                            Intent.createChooser(
                                Intent(Intent.ACTION_SEND).apply {
                                    type = "image/png"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                },
                                resources.getString(R.string.settings_qr_share_entry),
                            ),
                        )
                    }
                },
                enabled = bitmap != null,
            ) { Text(stringResource(R.string.settings_qr_share_image)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CoreR.string.common_close)) }
        },
    )
}

private fun writeQrPng(context: android.content.Context, bitmap: Bitmap): File {
    val dir = File(context.cacheDir, "shared").apply { mkdirs() }
    val file = File(dir, "nullclass-qr.png")
    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    return file
}
