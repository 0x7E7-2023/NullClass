package com.nullclass.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.nio.FloatBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 在 JVM 上用**同一份模型**跑通整条流水线（预处理 → 检测 → 后处理 → 识别 → CTC → 字典）。
 *
 * Android 侧加载的是 `onnxruntime-android` 的 .so，JVM 单测换成桌面版 ORT（同一个 Java API、
 * 同一份 .onnx），所以除了「.so 能否加载」之外，算法路径是 CI 可回归的。
 */
class PpOcrPipelineTest {

    private val assetsDir = File(
        System.getProperty("ocrAssetsDir") ?: error("缺少 ocrAssetsDir 系统属性（见 ocr/build.gradle.kts）"),
    )

    private val environment = OrtEnvironment.getEnvironment()

    private fun session(name: String): OrtSession {
        val file = File(assetsDir, name)
        assertTrue(file.isFile, "缺少模型文件：${file.path}")
        return environment.createSession(file.readBytes(), OrtSession.SessionOptions())
    }

    private fun dictionary(): List<String> {
        val lines = File(assetsDir, "ppocrv6_tiny_dict.txt").readText(Charsets.UTF_8)
            .split('\n').map { it.trimEnd('\r') }
        return if (lines.isNotEmpty() && lines.last().isEmpty()) lines.dropLast(1) else lines
    }

    @Test
    fun `字典规模与模型输出类别匹配`() {
        val dict = dictionary()
        assertEquals(6904, dict.size, "字典行数与 PP-OCRv6 tiny 的 6904 字不符")

        val rec = session("ppocrv6_tiny_rec.onnx")
        val input = FloatArray(3 * 48 * 32)
        val tensor = OnnxTensor.createTensor(
            environment,
            FloatBuffer.wrap(input),
            longArrayOf(1, 3, 48, 32),
        )
        tensor.use {
            rec.run(mapOf("x" to it)).use { result ->
                @Suppress("UNCHECKED_CAST")
                val logits = result.get(0).value as Array<Array<FloatArray>>
                // 空白符 + 6904 字典字 + 空格 = 6906
                assertEquals(dict.size + 2, logits[0][0].size, "模型输出类别数与字典不匹配")
            }
        }
    }

    @Test
    fun `整条流水线能识别合成图片里的文字`() {
        val image = renderText("AB12")
        val pipeline = PpOcrPipeline(
            environment = environment,
            det = session("ppocrv6_tiny_det.onnx"),
            rec = session("ppocrv6_tiny_rec.onnx"),
            dict = dictionary(),
        )

        val page = pipeline.recognize(image)

        assertTrue(page.boxes.isNotEmpty(), "没有检测到任何文本框")
        assertTrue(
            page.boxes.any { box -> box.text.any { it.isLetterOrDigit() } },
            "识别结果里没有字母数字：${page.boxes.map { it.text }}",
        )
        assertTrue(page.boxes.all { it.confidence in 0f..1f }, "置信度超出 0..1")
    }

    /** 用 AWT 渲染一张白底黑字图片（JVM 单测没有 Android Bitmap）。 */
    private fun renderText(text: String): OcrImage {
        val width = 320
        val height = 96
        val buffered = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = buffered.createGraphics()
        graphics.color = Color.WHITE
        graphics.fillRect(0, 0, width, height)
        graphics.color = Color.BLACK
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        graphics.font = Font(Font.SANS_SERIF, Font.BOLD, 64)
        graphics.drawString(text, 20, 72)
        graphics.dispose()

        val pixels = IntArray(width * height)
        buffered.getRGB(0, 0, width, height, pixels, 0, width)
        return OcrImage(width, height, pixels)
    }
}
