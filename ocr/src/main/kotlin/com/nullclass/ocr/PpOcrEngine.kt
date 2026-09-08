package com.nullclass.ocr

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import com.nullclass.importer.jw.ocr.OcrPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 随包发布的 PP-OCRv6 tiny 引擎（det 1.8MB + rec 4.5MB + 字典 6904 字）。
 *
 * **完全离线**：模型在 assets 里，不下载、不联网、不埋点。
 * 懒加载：首次识别才建 session（冷启动不背这个开销）。
 */
class PpOcrEngine(private val context: Context) : OcrEngine {

    private val lock = Any()

    @Volatile
    private var pipeline: PpOcrPipeline? = null

    @Volatile
    private var failure: String? = null

    override val available: Boolean
        get() = ensurePipeline() != null

    override val unavailableReason: String?
        get() {
            ensurePipeline()
            return failure
        }

    override suspend fun recognize(bitmap: Bitmap): OcrPage = withContext(Dispatchers.Default) {
        val engine = ensurePipeline() ?: throw OcrUnavailableException(failure ?: "OCR 引擎不可用")
        engine.recognize(OcrImage.fromBitmap(bitmap))
    }

    private fun ensurePipeline(): PpOcrPipeline? {
        pipeline?.let { return it }
        synchronized(lock) {
            pipeline?.let { return it }
            if (failure != null) return null
            return try {
                val environment = OrtEnvironment.getEnvironment()
                val options = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(2)
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                }
                val det = environment.createSession(readAsset(DET_MODEL), options)
                val rec = environment.createSession(readAsset(REC_MODEL), options)
                PpOcrPipeline(environment, det, rec, readDictionary()).also { pipeline = it }
            } catch (e: Throwable) {
                failure = "OCR 模型加载失败：${e.message ?: e.javaClass.simpleName}"
                null
            }
        }
    }

    private fun readAsset(name: String): ByteArray =
        context.assets.open("$ASSET_DIR/$name").use { it.readBytes() }

    private fun readDictionary(): List<String> {
        val text = readAsset(DICTIONARY).toString(Charsets.UTF_8)
        val lines = text.split('\n').map { it.trimEnd('\r') }
        // 末尾换行会split出一个空元素，去掉它；中间的空行保留以维持下标对齐
        return if (lines.isNotEmpty() && lines.last().isEmpty()) lines.dropLast(1) else lines
    }

    private companion object {
        const val ASSET_DIR = "ocr"
        const val DET_MODEL = "ppocrv6_tiny_det.onnx"
        const val REC_MODEL = "ppocrv6_tiny_rec.onnx"
        const val DICTIONARY = "ppocrv6_tiny_dict.txt"
    }
}
