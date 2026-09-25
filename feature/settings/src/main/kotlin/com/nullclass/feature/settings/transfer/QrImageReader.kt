package com.nullclass.feature.settings.transfer

import com.nullclass.feature.settings.R
import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.nullclass.core.ui.i18n.UiText
import com.nullclass.core.ui.i18n.UiTextException
import com.nullclass.importer.QrPayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 相册与相机沿用相同的字节解码，避免二进制课表二维码被 UTF-8 损坏。 */
internal fun payloadFrom(barcode: Barcode): String? {
    val bytes = barcode.rawBytes
    if (bytes != null && bytes.isNotEmpty()) return String(bytes, Charsets.ISO_8859_1)
    return barcode.rawValue
}

internal suspend fun readQrImage(context: Context, uri: Uri): String {
    val image = withContext(Dispatchers.IO) { InputImage.fromFilePath(context, uri) }
    return suspendCancellableCoroutine { continuation ->
        val scanner = BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build(),
        )
        try {
            scanner.process(image)
                .addOnSuccessListener { barcodes ->
                    if (!continuation.isActive) return@addOnSuccessListener
                    val payloads = barcodes.mapNotNull(::payloadFrom).filter { it.isNotEmpty() }
                    val payload = payloads.firstOrNull(QrPayload::isNullClassPayload) ?: payloads.firstOrNull()
                    if (payload == null) {
                        continuation.resumeWithException(
                            UiTextException(UiText.Res(R.string.settings_qr_image_not_found), "no qr code in image"),
                        )
                    } else {
                        continuation.resume(payload)
                    }
                }
                .addOnFailureListener { error ->
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
                .addOnCompleteListener { scanner.close() }
        } catch (e: Exception) {
            scanner.close()
            if (continuation.isActive) continuation.resumeWithException(e)
        }
    }
}
