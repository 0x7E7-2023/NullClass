package com.nullclass.feature.settings.transfer

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** 二维码位图生成（zxing-core，30 行自己画，不用 embedded 的生成侧）。 */
internal object QrBitmap {

    fun encode(content: String, size: Int = 1024): Bitmap {
        val matrix = QRCodeWriter().encode(
            content,
            BarcodeFormat.QR_CODE,
            size,
            size,
            mapOf(
                EncodeHintType.MARGIN to 2,
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
                EncodeHintType.CHARACTER_SET to "ISO-8859-1",
            ),
        )
        // 实际矩阵可能略大于请求尺寸（模块数对不齐时 zxing 会取 max），按真实宽高画，
        // 避免按 1024 硬取越界。
        val width = matrix.width
        val height = matrix.height
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val offset = y * width
            for (x in 0 until width) {
                pixels[offset + x] = if (matrix.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }
}
