package com.nullclass.feature.settings.jw

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.webkit.CookieManager
import com.nullclass.importer.jw.JwHostAllowlist
import com.nullclass.importer.jw.JwImageRef
import com.nullclass.importer.jw.ocr.OcrPage
import com.nullclass.ocr.OcrEngines
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * 图片课表的取图 + 识别。
 *
 * 图片可能是适配器给的 URL（用 WebView 的 Cookie 下载，教务多半要登录态），
 * 也可能是适配器直接从页面里取到的 data URL。
 *
 * **信任边界**：URL 只允许 https/http 且**主机必须在适配器白名单内**（同源或 `allowHosts`）——
 * 否则第三方适配器能把宿主的 Cookie 当成盲 SSRF 打内网。
 */
object JwImageOcr {

    private const val MAX_IMAGE_BYTES = 8 * 1024 * 1024

    /** 解码后的像素上限（8MB 的 PNG 可以解成 20000×20000 的位图，必须先拦）。 */
    private const val MAX_PIXELS = 40_000_000L

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    suspend fun recognize(context: Context, image: JwImageRef, allowedHosts: List<String>): OcrPage {
        val bitmap = loadBitmap(image, allowedHosts)
            ?: throw IllegalArgumentException(
                "图片无法读取或不在白名单内（只允许 https/http 且域名在适配器声明范围内），" +
                    "也可能只是图片损坏——建议用原始截图，不要用微信转发后的压缩图",
            )
        return OcrEngines.default(context).recognize(bitmap)
    }

    /** 桥接口用：输入是 URL 或 data URL。 */
    suspend fun recognizeInput(context: Context, input: String, allowedHosts: List<String>): OcrPage {
        val image = if (input.startsWith("data:")) JwImageRef(data = input) else JwImageRef(url = input)
        return recognize(context, image, allowedHosts)
    }

    suspend fun loadBitmap(image: JwImageRef, allowedHosts: List<String>): Bitmap? = withContext(Dispatchers.IO) {
        val data = image.data
        val url = image.url
        val bytes = when {
            !data.isNullOrBlank() -> decodeDataUrl(data)
            !url.isNullOrBlank() -> download(url, allowedHosts)
            else -> null
        } ?: return@withContext null
        decodeWithCap(bytes)
    }

    private fun decodeDataUrl(dataUrl: String): ByteArray? {
        val comma = dataUrl.indexOf(',')
        if (!dataUrl.startsWith("data:") || comma < 0) return null
        return try {
            Base64.decode(dataUrl.substring(comma + 1), Base64.DEFAULT)
        } catch (e: IllegalArgumentException) {
            null
        }?.takeIf { it.size <= MAX_IMAGE_BYTES }
    }

    private fun download(url: String, allowedHosts: List<String>): ByteArray? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host?.lowercase() ?: return null
        if (!JwHostAllowlist.matches(host, allowedHosts)) return null

        val cookie = CookieManager.getInstance().getCookie(url)
        val request = Request.Builder()
            .url(url)
            .apply { if (!cookie.isNullOrBlank()) header("Cookie", cookie) }
            .build()
        return try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body ?: return null
                if (body.contentLength() > MAX_IMAGE_BYTES) return null
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                body.byteStream().use { input ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        if (out.size() > MAX_IMAGE_BYTES) return null
                    }
                }
                out.toByteArray()
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun decodeWithCap(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        if (bounds.outWidth.toLong() * bounds.outHeight > MAX_PIXELS) return null
        return try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: OutOfMemoryError) {
            null
        }
    }
}
