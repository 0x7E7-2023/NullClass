package com.nullclass.importer.jw.ocr

/** OCR 引擎识别出的一个文本框（原图像素坐标，左上原点）。 */
data class OcrBox(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val confidence: Float = 1f,
) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
}

/** 一张图片的识别结果。 */
data class OcrPage(val width: Int, val height: Int, val boxes: List<OcrBox>)
