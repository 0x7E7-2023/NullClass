package com.nullclass.sync

import com.nullclass.importer.BlockDto
import com.nullclass.importer.CourseDto
import com.nullclass.importer.ManifestDto
import com.nullclass.importer.NullClassCodec
import com.nullclass.importer.PeriodTimeDto
import com.nullclass.importer.ScheduleDocument
import com.nullclass.importer.TermDto

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** WebDAV 连接配置。 */
data class WebDavConfig(
    val url: String,
    val username: String,
    val password: String,
) {
    /**
     * 校验：必须 https；http 仅允许私网地址（局域网 NAS 场景）。
     * @return 错误信息，null 表示合法
     */
    fun validate(): String? {
        val normalized = url.trim()
        if (normalized.isBlank()) return "服务器地址不能为空"
        val isHttps = normalized.startsWith("https://", ignoreCase = true)
        val isHttp = normalized.startsWith("http://", ignoreCase = true)
        if (!isHttps && !isHttp) return "地址必须以 http:// 或 https:// 开头"
        val host = normalized.removePrefix("https://").removePrefix("http://")
            .substringBefore(':').substringBefore('/')
        if (isHttp && !isPrivateHost(host)) {
            return "公网地址必须使用 https，否则密码会明文传输"
        }
        if (username.isBlank()) return "用户名不能为空"
        return null
    }

    /**
     * 严格 IPv4 字面量 / localhost 才算私网。
     * 整段主机名匹配（matchEntire），杜绝 "10.0.0.1.evil.com" 这类公网域名绕过（B5）。
     */
    internal fun isPrivateHost(host: String): Boolean {
        if (host.equals("localhost", ignoreCase = true) || host == "127.0.0.1") return true
        val match = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""").matchEntire(host)
            ?: return false
        val octets = match.destructured.toList().map { it.toIntOrNull() ?: return false }
        if (octets.any { it !in 0..255 }) return false
        return octets[0] == 10 ||
            (octets[0] == 192 && octets[1] == 168) ||
            (octets[0] == 172 && octets[1] in 16..31)
    }

    /** 远端目录：{url}/nullclass/。 */
    internal fun baseUrl(): String {
        var base = url.trim().trimEnd('/')
        if (base.startsWith("http://", true).not() && base.startsWith("https://", true).not()) {
            base = "https://$base"
        }
        return "$base/nullclass"
    }
}

sealed interface WebDavResult {
    data object Ok : WebDavResult
    data class Error(val message: String) : WebDavResult
}

/**
 * 轻量 WebDAV 客户端（OkHttp 自实现，覆盖 PUT/GET/MKCOL/DELETE，Basic Auth）。
 * 不引第三方 DAV 库，保持 F-Droid 友好。
 */
class WebDavClient(private val config: WebDavConfig) {

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    // manifest.json 专用（同步指针，不进 .nullclass 文件）；
    // snapshot 走 NullClassCodec —— v2 格式契约唯一入口（encodeDefaults + 未来版本守卫，B7）
    private val manifestJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private fun authHeader(): String = Credentials.basic(config.username, config.password)

    /**
     * 测试连接：建目录（已存在也算成功）→ 写探测文件 → 读回比对 → 清理。
     */
    suspend fun testConnection(): WebDavResult = withContext(Dispatchers.IO) {
        try {
            mkcol("${config.baseUrl()}/")
            val probePath = "${config.baseUrl()}/probe.txt"
            val probeContent = "nullclass-probe-${System.currentTimeMillis()}"
            http.newCall(
                Request.Builder()
                    .url(probePath)
                    .header("Authorization", authHeader())
                    .put(probeContent.toRequestBody("text/plain".toMediaType()))
                    .build(),
            ).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext WebDavResult.Error("写入测试失败：HTTP ${response.code}")
                }
            }
            http.newCall(
                Request.Builder()
                    .url(probePath)
                    .header("Authorization", authHeader())
                    .get()
                    .build(),
            ).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext WebDavResult.Error("读取测试失败：HTTP ${response.code}")
                }
                val body = response.body?.string().orEmpty()
                if (!body.startsWith("nullclass-probe-")) {
                    return@withContext WebDavResult.Error("读取内容不匹配，请确认这是专用目录")
                }
            }
            http.newCall(
                Request.Builder()
                    .url(probePath)
                    .header("Authorization", authHeader())
                    .delete()
                    .build(),
            ).execute().use { response ->
                if (!response.isSuccessful && response.code != 404) {
                    return@withContext WebDavResult.Error("清理测试文件失败：HTTP ${response.code}")
                }
            }
            WebDavResult.Ok
        } catch (e: IOException) {
            WebDavResult.Error("连接失败：${e.message ?: e.javaClass.simpleName}")
        }
    }

    /**
     * 下载远端快照与 manifest。
     * @return null 表示远端为空（首次同步）
     */
    suspend fun download(): Downloaded? = withContext(Dispatchers.IO) {
        val manifest = downloadManifest() ?: return@withContext null
        val snapshot = downloadSnapshot() ?: return@withContext null
        Downloaded(manifest, snapshot)
    }

    data class Downloaded(val manifest: ManifestDto, val snapshot: ScheduleDocument)

    private suspend fun downloadManifest(): ManifestDto? = withContext(Dispatchers.IO) {
        http.newCall(
            Request.Builder()
                .url("${config.baseUrl()}/manifest.json")
                .header("Authorization", authHeader())
                .get()
                .build(),
        ).execute().use { response ->
            when {
                response.code == 404 -> null
                response.isSuccessful -> response.body?.string()?.let { manifestJson.decodeFromString<ManifestDto>(it) }
                else -> throw IOException("下载 manifest 失败：HTTP ${response.code}")
            }
        }
    }

    private suspend fun downloadSnapshot(): ScheduleDocument? = withContext(Dispatchers.IO) {
        http.newCall(
            Request.Builder()
                .url("${config.baseUrl()}/snapshot.json")
                .header("Authorization", authHeader())
                .get()
                .build(),
        ).execute().use { response ->
            when {
                response.code == 404 -> null
                response.isSuccessful -> response.body?.string()?.let { NullClassCodec.decode(it) }
                else -> throw IOException("下载快照失败：HTTP ${response.code}")
            }
        }
    }

    /** 上传合并后的快照与 manifest（rev+1）。 */
    suspend fun upload(snapshot: ScheduleDocument, previousRev: Long) = withContext(Dispatchers.IO) {
        val snapshotJson = NullClassCodec.encode(snapshot)
        http.newCall(
            Request.Builder()
                .url("${config.baseUrl()}/snapshot.json")
                .header("Authorization", authHeader())
                .put(snapshotJson.toRequestBody(jsonMedia))
                .build(),
        ).execute().use { response ->
            if (!response.isSuccessful) throw IOException("上传快照失败：HTTP ${response.code}")
        }
        val manifest = ManifestDto(
            deviceId = snapshot.deviceId,
            generatedAt = snapshot.generatedAt,
            rev = previousRev + 1,
        )
        http.newCall(
            Request.Builder()
                .url("${config.baseUrl()}/manifest.json")
                .header("Authorization", authHeader())
                .put(manifestJson.encodeToString(manifest).toRequestBody(jsonMedia))
                .build(),
        ).execute().use { response ->
            if (!response.isSuccessful) throw IOException("上传 manifest 失败：HTTP ${response.code}")
        }
    }

    /** MKCOL 建目录；已存在（405）视为成功。 */
    private fun mkcol(url: String) {
        val body = "".toRequestBody(null)
        http.newCall(
            Request.Builder()
                .url(url)
                .header("Authorization", authHeader())
                .method("MKCOL", body)
                .build(),
        ).execute().use { response ->
            if (!response.isSuccessful && response.code != 405 && response.code != 301) {
                throw IOException("创建目录失败：HTTP ${response.code}")
            }
        }
    }
}
