package com.nullclass.ocr

import android.graphics.Bitmap

/** 与平台无关的图片容器（ARGB_8888），便于在 JVM 单测里直接构造。 */
class OcrImage(val width: Int, val height: Int, val argb: IntArray) {

    init {
        require(argb.size >= width * height) { "argb 长度不足：${argb.size} < ${width * height}" }
    }

    fun alphaAt(x: Int, y: Int): Int = (argb[y * width + x] ushr 24) and 0xFF

    fun redAt(x: Int, y: Int): Int = (argb[y * width + x] ushr 16) and 0xFF

    fun greenAt(x: Int, y: Int): Int = (argb[y * width + x] ushr 8) and 0xFF

    fun blueAt(x: Int, y: Int): Int = argb[y * width + x] and 0xFF

    companion object {
        fun fromBitmap(bitmap: Bitmap): OcrImage {
            val width = bitmap.width
            val height = bitmap.height
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            return OcrImage(width, height, pixels)
        }
    }
}

internal data class BoxRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}
