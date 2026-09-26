package com.nullclass.feature.settings.transfer

import com.nullclass.feature.settings.R
import android.graphics.Rect
import android.util.Size
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.camera.core.Camera
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as ComposeSize
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.nullclass.core.ui.R as CoreR
import com.nullclass.core.ui.i18n.UiText
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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
    onPickImage: () -> Unit,
    onError: (UiText) -> Unit,
) {
    val context = LocalContext.current
    // ponytail: Dialog's LocalLifecycleOwner can swap after first frame and restart the
    // effect mid-open; Activity owner is stable and already RESUMED while the dialog shows.
    val lifecycleOwner = context as? LifecycleOwner ?: LocalLifecycleOwner.current
    val hostView = LocalView.current
    var torchOn by remember { mutableStateOf(false) }
    var torchAvailable by remember { mutableStateOf(false) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var manualZoom by remember { mutableStateOf(false) }
    var streaming by remember { mutableStateOf(false) }
    var statusRes by remember { mutableStateOf(R.string.settings_qr_scan_hint) }
    val currentOnPayload by rememberUpdatedState(onPayload)
    val currentOnError by rememberUpdatedState(onError)
    val previewView = remember(context) {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            // 优先使用 SurfaceView，避免部分设备在弹窗中合成 TextureView 时绿屏。
            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
        }
    }

    BackHandler(onBack = onCancel)

    DisposableEffect(hostView) {
        hostView.keepScreenOn = true
        onDispose { hostView.keepScreenOn = false }
    }

    DisposableEffect(previewView, lifecycleOwner) {
        val observer = Observer<PreviewView.StreamState> { streaming = it == PreviewView.StreamState.STREAMING }
        previewView.previewStreamState.observe(lifecycleOwner, observer)
        onDispose { previewView.previewStreamState.removeObserver(observer) }
    }

    DisposableEffect(previewView, lifecycleOwner) {
        val done = AtomicBoolean(false)
        val executor = Executors.newSingleThreadExecutor()
        val scanner = BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .enableAllPotentialBarcodes()
                .build(),
        )
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        val mainExecutor = ContextCompat.getMainExecutor(context)
        var lastAutoZoomAt = SystemClock.elapsedRealtime()

        cameraProviderFuture.addListener({
            if (done.get()) return@addListener
            val provider = try {
                cameraProviderFuture.get()
            } catch (e: Exception) {
                currentOnError(
                    UiText.Res(R.string.settings_qr_scan_camera_failed, e.message ?: e.javaClass.simpleName),
                )
                return@addListener
            }
            val preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
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
                        mainExecutor.execute { currentOnPayload(payload) }
                    }
                },
                onSmallBox = { fill ->
                    if (!done.get() && !manualZoom) {
                        val cam = camera
                        val zoom = cam?.cameraInfo?.zoomState?.value
                        val now = SystemClock.elapsedRealtime()
                        if (cam != null && zoom != null && now - lastAutoZoomAt >= 1200) {
                            val target = nextAutoZoom(zoom.zoomRatio, zoom.maxZoomRatio, fill)
                            if (target > zoom.zoomRatio) {
                                lastAutoZoomAt = now
                                cam.cameraControl.setZoomRatio(target)
                                statusRes = R.string.settings_qr_scan_zooming
                            } else {
                                statusRes = R.string.settings_qr_scan_decoding
                            }
                        }
                    }
                },
                onError = { cause ->
                    val message = UiText.Res(
                        R.string.settings_qr_scan_failed,
                        cause?.message ?: UiText.Res(R.string.settings_qr_scan_failed_retry),
                    )
                    if (done.compareAndSet(false, true)) mainExecutor.execute { currentOnError(message) }
                },
            )
            analysis.setAnalyzer(executor, analyzer)

            try {
                if (done.get()) return@addListener
                provider.unbindAll()
                val bound = provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis,
                )
                if (done.get()) {
                    provider.unbindAll()
                    return@addListener
                }
                camera = bound
                torchAvailable = bound.cameraInfo.hasFlashUnit()
            } catch (e: Exception) {
                currentOnError(
                    UiText.Res(R.string.settings_qr_scan_camera_failed, e.message ?: e.javaClass.simpleName),
                )
            }
        }, mainExecutor)

        onDispose {
            done.set(true)
            try {
                if (cameraProviderFuture.isDone) cameraProviderFuture.get().unbindAll()
            } catch (_: Exception) {
            }
            try {
                scanner.close()
            } catch (_: Exception) {
            }
            executor.shutdown()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize(),
        )
        Canvas(
            Modifier.fillMaxSize().pointerInput(camera) {
                var requestedZoom = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f
                detectTransformGestures { _, _, zoomChange, _ ->
                    if (zoomChange != 1f) {
                        val cam = camera ?: return@detectTransformGestures
                        val zoom = cam.cameraInfo.zoomState.value ?: return@detectTransformGestures
                        if (!manualZoom) requestedZoom = zoom.zoomRatio
                        manualZoom = true
                        requestedZoom = (requestedZoom * zoomChange).coerceIn(1f, zoom.maxZoomRatio)
                        cam.cameraControl.setZoomRatio(requestedZoom)
                        statusRes = R.string.settings_qr_scan_hint
                    }
                }
            }.pointerInput(camera) {
                detectTapGestures { point ->
                    val cam = camera ?: return@detectTapGestures
                    val meteringPoint = previewView.meteringPointFactory.createPoint(point.x, point.y)
                    cam.cameraControl.startFocusAndMetering(
                        FocusMeteringAction.Builder(meteringPoint)
                            .setAutoCancelDuration(3, TimeUnit.SECONDS)
                            .build(),
                    )
                }
            },
        ) {
            val side = min(size.width * 0.78f, size.height * 0.42f)
            val left = (size.width - side) / 2f
            val top = (size.height - side) / 2f
            val shade = Color.Black.copy(alpha = 0.5f)
            drawRect(shade, size = ComposeSize(size.width, top))
            drawRect(shade, Offset(0f, top + side), ComposeSize(size.width, size.height - top - side))
            drawRect(shade, Offset(0f, top), ComposeSize(left, side))
            drawRect(shade, Offset(left + side, top), ComposeSize(left, side))
            drawRect(Color.White, Offset(left, top), ComposeSize(side, side), style = Stroke(2.dp.toPx()))
        }
        IconButton(
            onClick = onCancel,
            modifier = Modifier
                .statusBarsPadding()
                .align(Alignment.TopStart)
                .padding(8.dp),
        ) {
            Icon(
                Icons.Filled.Close,
                contentDescription = stringResource(CoreR.string.common_close),
                tint = Color.White,
            )
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
                Text(
                    stringResource(
                        if (torchOn) {
                            R.string.settings_qr_scan_torch_off
                        } else {
                            R.string.settings_qr_scan_torch_on
                        },
                    ),
                    color = Color.White,
                )
            }
        }
        Column(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .navigationBarsPadding().padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                if (streaming) stringResource(statusRes) else stringResource(R.string.settings_qr_scan_starting),
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = onPickImage) {
                Text(stringResource(R.string.settings_qr_scan_album), color = Color.White)
            }
        }
    }
}

/**
 * ML Kit 分析器：解出内容立刻回调；只检出框、模块还不够大时通知上层拉近。
 */
private class DenseQrAnalyzer(
    private val scanner: BarcodeScanner,
    private val onPayload: (String) -> Unit,
    private val onSmallBox: (fill: Float) -> Unit,
    /** 识别失败。传异常本身而不是文字：文案由调用方按当前语言取。 */
    private val onError: (Throwable?) -> Unit,
) : ImageAnalysis.Analyzer {

    private val busy = AtomicBoolean(false)

    // imageProxy.image 是 CameraX 标注的实验 API；ML Kit 的 fromMediaImage 只收 Image，绕不开
    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val media = imageProxy.image
        if (media == null || !busy.compareAndSet(false, true)) {
            imageProxy.close()
            return
        }
        val width = imageProxy.width
        val height = imageProxy.height
        val task = try {
            val image = InputImage.fromMediaImage(media, imageProxy.imageInfo.rotationDegrees)
            scanner.process(image)
        } catch (e: Exception) {
            busy.set(false)
            imageProxy.close()
            onError(e)
            return
        }
        task
            .addOnSuccessListener { barcodes ->
                val qrCodes = barcodes.filter { it.format == Barcode.FORMAT_QR_CODE }
                val qr = qrCodes.firstOrNull { !payloadFrom(it).isNullOrEmpty() }
                    ?: barcodes.firstOrNull() ?: return@addOnSuccessListener
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
            .addOnFailureListener { onError(it) }
            .addOnCompleteListener {
                busy.set(false)
                imageProxy.close()
            }
    }
}

