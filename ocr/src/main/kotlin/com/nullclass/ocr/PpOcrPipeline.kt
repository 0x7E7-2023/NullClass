package com.nullclass.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.nullclass.importer.jw.ocr.OcrBox
import com.nullclass.importer.jw.ocr.OcrPage
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * PP-OCR 推理流水线：检测（DB）+ 识别（CRNN + CTC）。
 *
 * 纯 Kotlin 前后处理，只依赖 ONNX Runtime 的 Java API——同一份代码在 Android 与 JVM
 * 单测里跑同一份模型，所以流水线本身是可回归的（真机差异只剩 .so 与预处理性能）。
 *
 * 检测后处理用**连通域 + 轴对齐外接矩形**而不是 DB 原文的轮廓/Vatti 裁剪：
 * 课表是轴对齐的规则表格，外接矩形足够，且不引入 OpenCV。
 */
class PpOcrPipeline(
    private val environment: OrtEnvironment,
    private val det: OrtSession,
    private val rec: OrtSession,
    private val dict: List<String>,
) {

    fun recognize(image: OcrImage): OcrPage {
        val boxes = detect(image)
        val results = boxes.mapNotNull { rect -> recognizeBox(image, rect) }
        return OcrPage(image.width, image.height, results)
    }

    // ---- 检测 ----

    private fun detect(image: OcrImage): List<BoxRect> {
        val longest = max(image.width, image.height).coerceAtLeast(1)
        val ratio = min(1.0, DET_LIMIT_SIDE.toDouble() / longest)
        val inputW = roundUpToMultiple((image.width * ratio).roundToInt().coerceAtLeast(DET_SIZE_MULTIPLE), DET_SIZE_MULTIPLE)
        val inputH = roundUpToMultiple((image.height * ratio).roundToInt().coerceAtLeast(DET_SIZE_MULTIPLE), DET_SIZE_MULTIPLE)

        val input = FloatArray(3 * inputH * inputW)
        val scaleX = image.width.toDouble() / inputW
        val scaleY = image.height.toDouble() / inputH
        for (y in 0 until inputH) {
            val sy = min(image.height - 1, (y * scaleY).toInt())
            for (x in 0 until inputW) {
                val sx = min(image.width - 1, (x * scaleX).toInt())
                val pixel = image.argb[sy * image.width + sx]
                val offset = y * inputW + x
                input[offset] = (((pixel ushr 16) and 0xFF) / 255f - DET_MEAN[0]) / DET_STD[0]
                input[inputH * inputW + offset] = (((pixel ushr 8) and 0xFF) / 255f - DET_MEAN[1]) / DET_STD[1]
                input[2 * inputH * inputW + offset] = ((pixel and 0xFF) / 255f - DET_MEAN[2]) / DET_STD[2]
            }
        }

        val shape = longArrayOf(1, 3, inputH.toLong(), inputW.toLong())
        val prob = OnnxTensor.createTensor(environment, FloatBuffer.wrap(input), shape).use { tensor ->
            det.runSingleInput(tensor).use { result ->
                @Suppress("UNCHECKED_CAST")
                (result.get(0).value as Array<Array<Array<FloatArray>>>)[0][0]
            }
        }

        val mask = BooleanArray(inputH * inputW)
        for (y in 0 until inputH) {
            val row = prob[y]
            for (x in 0 until inputW) {
                mask[y * inputW + x] = row[x] > DET_THRESHOLD
            }
        }

        val boxes = mutableListOf<BoxRect>()
        val visited = BooleanArray(mask.size)
        val queue = ArrayDeque<Int>()
        for (start in mask.indices) {
            if (!mask[start] || visited[start]) continue
            visited[start] = true
            queue.addLast(start)
            var minX = Int.MAX_VALUE
            var maxX = Int.MIN_VALUE
            var minY = Int.MAX_VALUE
            var maxY = Int.MIN_VALUE
            var area = 0
            while (queue.isNotEmpty()) {
                val index = queue.removeFirst()
                val x = index % inputW
                val y = index / inputW
                area++
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = x + dx
                        val ny = y + dy
                        if (nx < 0 || ny < 0 || nx >= inputW || ny >= inputH) continue
                        val next = ny * inputW + nx
                        if (mask[next] && !visited[next]) {
                            visited[next] = true
                            queue.addLast(next)
                        }
                    }
                }
            }
            if (area < MIN_COMPONENT_AREA) continue
            // DB 的 unclip：按面积/周长比向外扩张
            val boxW = (maxX - minX + 1).toDouble()
            val boxH = (maxY - minY + 1).toDouble()
            val perimeter = 2 * (boxW + boxH)
            val distance = if (perimeter <= 0) 0.0 else area * UNCLIP_RATIO / perimeter
            val left = ((minX - distance) * scaleX).roundToInt().coerceAtLeast(0)
            val top = ((minY - distance) * scaleY).roundToInt().coerceAtLeast(0)
            val right = ((maxX + 1 + distance) * scaleX).roundToInt().coerceAtMost(image.width)
            val bottom = ((maxY + 1 + distance) * scaleY).roundToInt().coerceAtMost(image.height)
            if (right - left < MIN_BOX_SIDE || bottom - top < MIN_BOX_SIDE) continue
            boxes += BoxRect(left, top, right, bottom)
        }

        return boxes.sortedWith(compareBy({ it.top }, { it.left }))
    }

    // ---- 识别 ----

    private fun recognizeBox(image: OcrImage, rect: BoxRect): OcrBox? {
        val cropW = rect.width
        val cropH = rect.height
        if (cropW < 2 || cropH < 2) return null

        val targetH = REC_HEIGHT
        var targetW = (targetH.toDouble() * cropW / cropH).roundToInt().coerceIn(16, REC_MAX_WIDTH)
        targetW = roundUpToMultiple(targetW, REC_WIDTH_MULTIPLE)

        val input = FloatArray(3 * targetH * targetW)
        val plane = targetH * targetW
        for (y in 0 until targetH) {
            val sy = rect.top + (y + 0.5) * cropH / targetH - 0.5
            for (x in 0 until targetW) {
                val sx = rect.left + (x + 0.5) * cropW / targetW - 0.5
                val rgb = bilinear(image, sx, sy)
                val offset = y * targetW + x
                input[offset] = (((rgb ushr 16) and 0xFF) / 255f - 0.5f) / 0.5f
                input[plane + offset] = (((rgb ushr 8) and 0xFF) / 255f - 0.5f) / 0.5f
                input[2 * plane + offset] = ((rgb and 0xFF) / 255f - 0.5f) / 0.5f
            }
        }

        val shape = longArrayOf(1, 3, targetH.toLong(), targetW.toLong())
        val logits = OnnxTensor.createTensor(environment, FloatBuffer.wrap(input), shape).use { tensor ->
            rec.runSingleInput(tensor).use { result ->
                @Suppress("UNCHECKED_CAST")
                (result.get(0).value as Array<Array<FloatArray>>)[0]
            }
        }

        val (text, confidence) = ctcDecode(logits)
        if (text.isBlank()) return null
        return OcrBox(text, rect.left, rect.top, rect.right, rect.bottom, confidence)
    }

    private fun ctcDecode(logits: Array<FloatArray>): Pair<String, Float> {
        if (logits.isEmpty() || logits[0].isEmpty()) return "" to 0f
        val builder = StringBuilder()
        var scoreSum = 0f
        var scored = 0
        var previous = -1
        logits.forEach { row ->
            var best = 0
            var bestValue = row[0]
            for (index in row.indices) {
                if (row[index] > bestValue) {
                    bestValue = row[index]
                    best = index
                }
            }
            if (best != 0 && best != previous) {
                val dictIndex = best - 1
                builder.append(if (dictIndex < dict.size) dict[dictIndex] else " ")
                scoreSum += softmaxAt(row, best)
                scored++
            }
            previous = best
        }
        return builder.toString() to if (scored == 0) 0f else scoreSum / scored
    }

    private fun softmaxAt(row: FloatArray, index: Int): Float {
        var max = row[0]
        for (value in row) if (value > max) max = value
        var sum = 0.0
        for (value in row) sum += kotlin.math.exp((value - max).toDouble())
        return (kotlin.math.exp((row[index] - max).toDouble()) / sum).toFloat()
    }

    private fun bilinear(image: OcrImage, x: Double, y: Double): Int {
        val x0 = kotlin.math.floor(x).toInt().coerceIn(0, image.width - 1)
        val y0 = kotlin.math.floor(y).toInt().coerceIn(0, image.height - 1)
        val x1 = min(x0 + 1, image.width - 1)
        val y1 = min(y0 + 1, image.height - 1)
        val fx = (x - x0).coerceIn(0.0, 1.0)
        val fy = (y - y0).coerceIn(0.0, 1.0)

        val p00 = image.argb[y0 * image.width + x0]
        val p01 = image.argb[y0 * image.width + x1]
        val p10 = image.argb[y1 * image.width + x0]
        val p11 = image.argb[y1 * image.width + x1]

        fun channel(shift: Int): Int {
            val c00 = (p00 ushr shift) and 0xFF
            val c01 = (p01 ushr shift) and 0xFF
            val c10 = (p10 ushr shift) and 0xFF
            val c11 = (p11 ushr shift) and 0xFF
            val top = c00 + (c01 - c00) * fx
            val bottom = c10 + (c11 - c10) * fx
            return (top + (bottom - top) * fy).roundToInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    private fun roundUpToMultiple(value: Int, multiple: Int): Int =
        ((value + multiple - 1) / multiple) * multiple

    private companion object {
        const val DET_LIMIT_SIDE = 960
        const val DET_SIZE_MULTIPLE = 32
        const val DET_THRESHOLD = 0.3f
        const val UNCLIP_RATIO = 1.6
        const val MIN_COMPONENT_AREA = 8
        const val MIN_BOX_SIDE = 6

        val DET_MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        val DET_STD = floatArrayOf(0.229f, 0.224f, 0.225f)

        const val REC_HEIGHT = 48
        const val REC_MAX_WIDTH = 640
        const val REC_WIDTH_MULTIPLE = 8
    }
}

/** 两个模型的输入名都是 `x`，这里统一收口，避免散落的魔法字符串。 */
private fun OrtSession.runSingleInput(input: OnnxTensor): OrtSession.Result =
    run(java.util.Collections.singletonMap("x", input))
