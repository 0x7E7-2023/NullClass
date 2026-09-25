package com.nullclass.feature.settings.jw

import com.nullclass.importer.jw.JwErrorCode
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
            // readTimeout 只管单次读空闲；慢速/恶意反代能一点点吐字节无限拖住，必须有总时限
            .callTimeout(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
    }

    override fun fetchBytes(url: String, maxBytes: Int): ByteArray {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build()
        val response = try {
            http.newCall(request).execute()
        } catch (e: Exception) {
            val detail = e.message ?: e.javaClass.simpleName
            throw JwRemoteException("网络请求失败：$detail", e, JwErrorCode.NETWORK_FAILED, listOf(detail))
        }
        response.use {
            if (!response.isSuccessful) {
                throw JwRemoteException("HTTP ${response.code}", code = JwErrorCode.HTTP_STATUS, codeArgs = listOf(response.code))
            }
            val finalUrl = response.request.url
            if (!allowInsecure && finalUrl.scheme != "https") {
                throw JwRemoteException(
                    "被重定向到非 https 地址：$finalUrl",
                    code = JwErrorCode.INSECURE_REDIRECT,
                    codeArgs = listOf(finalUrl.toString()),
                )
            }
            val body = response.body ?: throw JwRemoteException("响应为空", code = JwErrorCode.EMPTY_RESPONSE)
            val bytes = body.byteStream().use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    if (out.size() > maxBytes) {
                        throw JwRemoteException(
                            "响应超过 ${maxBytes / 1024}KB 上限",
                            code = JwErrorCode.RESPONSE_TOO_LARGE,
                            codeArgs = listOf(maxBytes / 1024),
                        )
                    }
                }
                out.toByteArray()
            }
            return bytes
        }
    }

    private companion object {
        const val USER_AGENT = "NullClass"
        const val CALL_TIMEOUT_MS = 60_000L
    }
}
