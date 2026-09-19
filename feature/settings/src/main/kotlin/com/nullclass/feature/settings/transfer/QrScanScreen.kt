package com.nullclass.feature.settings.transfer

import android.graphics.Rect
import android.util.Size
import androidx.activity.compose.BackHandler
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

/**
 * 课表二维码扫码页：CameraX 1080p 分析流 + ML Kit 端侧模型。
 *
 * 不另开横屏 Activity，方向跟当前界面走。码在画面里太小（巨型 QR 模块不够像素）
 * 时按检测框自动拉近，解出来再回调。
 */
@Composable
internal fun QrScanScreen(
    onPayload: (String) -> Unit,
    onCancel: () -> Unit,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val view = LocalView.current
    var torchOn by remember { mutableStateOf(false) }
    var torchAvailable by remember { mutableStateOf(false) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    val done = remember { AtomicBoolean(false) }

    BackHandler(onBack = onCancel)

    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    DisposableEffect(previewView, lifecycleOwner) {
        val view = previewView ?: return@DisposableEffect onDispose { }
        val executor = Executors.newSingleThreadExecutor()
        val scanner = BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build(),
        )
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        val mainExecutor = ContextCompat.getMainExecutor(context)

        cameraProviderFuture.addListener({
            if (done.get()) return@addListener
            val provider = try {
                cameraProviderFuture.get()
            } catch (e: Exception) {
                onError("打不开相机：${e.message ?: e.javaClass.simpleName}")
                return@addListener
            }
            val preview = Preview.Builder().build().also {
                it.surfaceProvider = view.surfaceProvider
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(1920, 1080),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                            ),
                        )
                        .build(),
                )
                .build()

            val analyzer = DenseQrAnalyzer(
                scanner = scanner,
                onPayload = { payload ->
                    if (done.compareAndSet(false, true)) {
                        mainExecutor.execute { onPayload(payload) }
                    }
                },
                onSmallBox = { fill ->
                    val cam = camera ?: return@DenseQrAnalyzer
                    val zoom = cam.cameraInfo.zoomState.value ?: return@DenseQrAnalyzer
                    val target = (zoom.zoomRatio * 0.62f / fill)
                        .coerceAtMost(zoom.maxZoomRatio)
                        .coerceAtMost(zoom.zoomRatio * 1.8f)
                        .coerceAtLeast(1f)
                    if (target > zoom.zoomRatio * 1.08f) {
                        cam.cameraControl.setZoomRatio(target)
                    }
                },
            )
            analysis.setAnalyzer(executor, analyzer)

            try {
                provider.unbindAll()
                val bound = provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis,
                )
                camera = bound
                torchAvailable = bound.cameraInfo.hasFlashUnit()
                val zoom = bound.cameraInfo.zoomState.value
                if (zoom != null && zoom.maxZoomRatio >= 1.4f) {
                    bound.cameraControl.setZoomRatio(min(1.4f, zoom.maxZoomRatio))
                }
            } catch (e: Exception) {
                onError("打不开相机：${e.message ?: e.javaClass.simpleName}")
            }
        }, mainExecutor)

        onDispose {
            done.set(true)
            executor.shutdown()
            scanner.close()
            try {
                cameraProviderFuture.get().unbindAll()
            } catch (_: Exception) {
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                }.also { previewView = it }
            },
            modifier = Modifier.fillMaxSize(),
        )
        IconButton(
            onClick = onCancel,
            modifier = Modifier
                .statusBarsPadding()
                .align(Alignment.TopStart)
                .padding(8.dp),
        ) {
            Icon(Icons.Filled.Close, contentDescription = "关闭", tint = Color.White)
        }
        if (torchAvailable) {
            TextButton(
                onClick = {
                    val next = !torchOn
                    camera?.cameraControl?.enableTorch(next)
                    torchOn = next
                },
                modifier = Modifier
                    .statusBarsPadding()
                    .align(Alignment.TopEnd)
                    .padding(8.dp),
            ) {
                Text(if (torchOn) "关闭闪光灯" else "闪光灯", color = Color.White)
            }
        }
        Text(
            "把二维码尽量填满画面；太小会自动拉近",
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(24.dp),
        )
    }
}

/**
 * ML Kit 分析器：解出内容立刻回调；只检出框、模块还不够大时通知上层拉近。
 */
private class DenseQrAnalyzer(
    private val scanner: BarcodeScanner,
    private val onPayload: (String) -> Unit,
    private val onSmallBox: (fill: Float) -> Unit,
) : ImageAnalysis.Analyzer {

    private val busy = AtomicBoolean(false)

    override fun analyze(imageProxy: ImageProxy) {
        val media = imageProxy.image
        if (media == null || !busy.compareAndSet(false, true)) {
            imageProxy.close()
            return
        }
        val width = imageProxy.width
        val height = imageProxy.height
        val image = InputImage.fromMediaImage(media, imageProxy.imageInfo.rotationDegrees)
        scanner.process(image)
            .addOnSuccessListener { barcodes ->
                val qr = barcodes.firstOrNull { it.format == Barcode.FORMAT_QR_CODE } ?: return@addOnSuccessListener
                val payload = payloadFrom(qr)
                if (!payload.isNullOrEmpty()) {
                    onPayload(payload)
                    return@addOnSuccessListener
                }
                val box: Rect = qr.boundingBox ?: return@addOnSuccessListener
                val minSide = min(width, height).toFloat().coerceAtLeast(1f)
                val boxSide = max(box.width(), box.height()).toFloat()
                val fill = boxSide / minSide
                if (fill in 0.04f..0.5f) onSmallBox(fill)
            }
            .addOnCompleteListener {
                busy.set(false)
                imageProxy.close()
            }
    }
}

private fun payloadFrom(barcode: Barcode): String? {
    val bytes = barcode.rawBytes
    if (bytes != null && bytes.isNotEmpty()) return String(bytes, Charsets.ISO_8859_1)
    return barcode.rawValue
}

