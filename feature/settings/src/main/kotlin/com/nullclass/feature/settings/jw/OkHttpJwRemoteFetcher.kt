package com.nullclass.feature.settings.jw

import com.nullclass.importer.jw.JwRemoteException
import com.nullclass.importer.jw.JwRemoteFetcher
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/** 适配器库下载（索引 + 包内文件）。只取文本，且有大小上限。 */
class OkHttpJwRemoteFetcher(
    private val allowInsecure: Boolean,
) : JwRemoteFetcher {

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(JwRemoteFetcher.TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(JwRemoteFetcher.TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
            .build()
    }

    override fun fetchText(url: String, maxBytes: Int): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build()
        val response = try {
            http.newCall(request).execute()
        } catch (e: Exception) {
            throw JwRemoteException("网络请求失败：${e.message}", e)
        }
        response.use {
            if (!response.isSuccessful) throw JwRemoteException("HTTP ${response.code}")
            val finalUrl = response.request.url
            if (!allowInsecure && finalUrl.scheme != "https") {
                throw JwRemoteException("被重定向到非 https 地址：$finalUrl")
            }
            val body = response.body ?: throw JwRemoteException("响应为空")
            val bytes = body.byteStream().use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    if (out.size() > maxBytes) throw JwRemoteException("响应超过 ${maxBytes / 1024}KB 上限")
                }
                out.toByteArray()
            }
            return bytes.toString(Charsets.UTF_8)
        }
    }

    private companion object {
        const val USER_AGENT = "NullClass"
    }
}
