package com.nullclass.ocr

import android.content.Context
import android.graphics.Bitmap
import com.nullclass.importer.jw.ocr.OcrPage

/**
 * 图片 → 文本框。实现必须**离线**运行（模型随包），且不得联网。
 */
interface OcrEngine {

    /** 引擎是否可用（模型缺失 / ABI 不支持时为 false）。 */
    val available: Boolean

    /** 不可用时给出原因（面向用户）。 */
    val unavailableReason: String?

    suspend fun recognize(bitmap: Bitmap): OcrPage
}

object OcrEngines {

    @Volatile
    private var cached: OcrEngine? = null

    fun default(context: Context): OcrEngine {
        cached?.let { return it }
        return synchronized(this) {
            cached ?: PpOcrEngine(context.applicationContext).also { cached = it }
        }
    }

    /** 测试用：清掉缓存。 */
    fun reset() {
        synchronized(this) { cached = null }
    }
}

/** 引擎不可用（缺模型等）。 */
class OcrUnavailableException(message: String) : IllegalStateException(message)
