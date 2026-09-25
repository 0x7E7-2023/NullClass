package com.nullclass.importer.jw

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * 官方适配器库热更新：从 GitHub Release（官方源 + 公共反代）取最新整库 zip。
 *
 * Release 资产（由 NullClass-adapters 的 CI 发布）：
 * - `latest.json`：`{"version":"2026.09.22"}`，只用来比版本，**不可信**
 * - `nullclass-adapters.zip`：库根（index.json + 各适配器目录）
 * - `nullclass-adapters.zip.sig`：对 zip 的 ECDSA P-256 / SHA-256 签名（DER）
 *
 * 反代是第三方，能任意改包——脚本会跑在用户登录后的教务页面里，所以
 * **只认签名**：签名不过一律丢弃；zip 内 index.json 的版本必须与声称的一致。
 */
object JwOfficialLibrary {

    const val REPO = "0x7E7-2023/NullClass-adapters"
    const val ZIP = "nullclass-adapters.zip"
    const val SIG = "$ZIP.sig"
    const val LATEST = "latest.json"

    /** 官方源在前；其余为公共反代（URL 前缀 + 完整 GitHub 地址）。 */
    val MIRRORS: List<String> = listOf(
        "",
        "https://gh-proxy.com/",
        "https://github.dpik.top/",
        "https://gh.927223.xyz/",
        "https://github.tbap.top/",
    )

    const val MAX_ZIP_BYTES = 16 * 1024 * 1024
    const val MAX_SIG_BYTES = 1024
    const val MAX_LATEST_BYTES = 4 * 1024
    private const val MAX_ENTRIES = 4096
    private const val MAX_TOTAL_BYTES = 64L * 1024 * 1024

    /** 发布私钥对应的公钥（X.509 DER，base64）。私钥只在 NullClass-adapters 的 Actions secret 里。 */
    const val PUBLIC_KEY =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE5yUJl3afGm86jT+r19s05BOk4p02nq8IrrMLTTZMcwascd3dXAQ2tYGANMYGUrivBocvVeTT2b+cMlth86xj2Q=="

    private val VERSION_REGEX = Regex("""\d{1,9}(\.\d{1,9}){0,3}""")
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Latest(val version: String)

    fun latestUrl(mirror: String): String = "${mirror}https://github.com/$REPO/releases/latest/download/$LATEST"

    fun assetUrl(mirror: String, version: String, name: String): String =
        "${mirror}https://github.com/$REPO/releases/download/v$version/$name"

    fun isValidVersion(version: String): Boolean = VERSION_REGEX.matches(version)

    /** 解析 `latest.json`；格式不对抛 [JwPackageException]。 */
    fun parseLatest(raw: String): String {
        val version = runCatching { json.decodeFromString<Latest>(raw).version.trim() }.getOrNull()
            ?: throw JwPackageException("latest.json 格式不对")
        if (!isValidVersion(version)) throw JwPackageException("latest.json 里的版本号不合法：$version")
        return version
    }

    /** 按数字段比较；`null`（没有版本）视为最旧。 */
    fun compareVersions(a: String?, b: String?): Int {
        if (a == null || b == null) return compareValues(a != null, b != null)
        val x = a.split('.').map { it.toLong() }
        val y = b.split('.').map { it.toLong() }
        for (i in 0 until maxOf(x.size, y.size)) {
            val c = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    fun verify(zip: ByteArray, signature: ByteArray, publicKeyBase64: String = PUBLIC_KEY): Boolean = runCatching {
        val key = KeyFactory.getInstance("EC")
            .generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyBase64)))
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(key)
            update(zip)
            verify(signature)
        }
    }.getOrDefault(false)

    /**
     * 验签 → 解包 → 按索引加载全部适配器（来源记为内置：官方签名等同随包发布）。
     *
     * @param expectedVersion 非 null 时要求 zip 内 index.json 版本与之一致（防反代把旧包冒充新版本）
     */
    fun load(
        zip: ByteArray,
        signature: ByteArray,
        appVersionCode: Int,
        expectedVersion: String? = null,
        publicKeyBase64: String = PUBLIC_KEY,
    ): JwLibraryBundle {
        if (!verify(zip, signature, publicKeyBase64)) throw JwPackageException("适配器库签名校验失败，已丢弃")
        val files = unzip(zip)
        val bundle = JwBuiltinLibrary.loadFrom(appVersionCode) { files[it] }
        val version = bundle.version
        if (version == null || !isValidVersion(version)) throw JwPackageException("适配器库缺少合法的版本号")
        if (expectedVersion != null && version != expectedVersion) {
            throw JwPackageException("适配器库版本不一致：声称 $expectedVersion，实际 $version")
        }
        return bundle
    }

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val files = HashMap<String, ByteArray>()
        var total = 0L
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (files.size >= MAX_ENTRIES) throw JwPackageException("适配器库条目过多")
                val path = JwPaths.normalizeZipEntry(entry.name)
                if (path != null) {
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(8 * 1024)
                    while (true) {
                        val read = zis.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        total += read
                        if (out.size() > JwPackageReader.MAX_FILE_BYTES || total > MAX_TOTAL_BYTES) {
                            throw JwPackageException("适配器库解压后过大")
                        }
                    }
                    if (files.put(path, out.toByteArray()) != null) throw JwPackageException("适配器库内有重复条目：$path")
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return files
    }
}

/** 一次检查的结果：各源报告的版本 → 报告该版本的源（按版本从新到旧）。 */
data class JwOfficialCheck(val versions: List<Pair<String, List<String>>>, val failedSources: Int) {
    val latest: String? get() = versions.firstOrNull()?.first
}

/** 下载并验签通过的新库。 */
class JwOfficialDownload(val bundle: JwLibraryBundle, val zip: ByteArray, val signature: ByteArray) {
    val version: String get() = bundle.version!!
}

/**
 * 编排：并发问所有源的 `latest.json`，取最高版本；下载时按版本从高到低、每个版本把所有源轮一遍，
 * 第一个验签通过的即为结果（某个反代谎报高版本 / 给坏包，只会被跳过）。
 */
class JwOfficialUpdater(
    private val fetcher: JwRemoteFetcher,
    private val appVersionCode: Int,
    private val mirrors: List<String> = JwOfficialLibrary.MIRRORS,
    private val publicKeyBase64: String = JwOfficialLibrary.PUBLIC_KEY,
) {

    fun check(): JwOfficialCheck {
        val pool = Executors.newFixedThreadPool(mirrors.size)
        try {
            val results = pool.invokeAll(
                mirrors.map { mirror ->
                    Callable {
                        runCatching {
                            JwOfficialLibrary.parseLatest(
                                fetcher.fetchText(JwOfficialLibrary.latestUrl(mirror), JwOfficialLibrary.MAX_LATEST_BYTES),
                            )
                        }.getOrNull()
                    }
                },
                CHECK_TIMEOUT_S,
                TimeUnit.SECONDS,
            ).map { runCatching { it.get() }.getOrNull() }
            val versions = mirrors.zip(results)
                .filter { it.second != null }
                .groupBy({ it.second!! }, { it.first })
                .entries
                .sortedWith { a, b -> JwOfficialLibrary.compareVersions(b.key, a.key) }
                .map { it.key to it.value }
            return JwOfficialCheck(versions, results.count { it == null })
        } finally {
            pool.shutdownNow()
        }
    }

    /** 下载比 [currentVersion] 新的最高可用版本；都失败抛最后一个错误。 */
    fun download(check: JwOfficialCheck, currentVersion: String?): JwOfficialDownload {
        var lastError: Exception = JwRemoteException("没有比当前更新的版本", code = JwErrorCode.NO_NEWER_VERSION)
        check.versions
            .filter { JwOfficialLibrary.compareVersions(it.first, currentVersion) > 0 }
            .forEach { (version, reporters) ->
                (reporters + (mirrors - reporters.toSet())).forEach { mirror ->
                    try {
                        val zip = fetcher.fetchBytes(
                            JwOfficialLibrary.assetUrl(mirror, version, JwOfficialLibrary.ZIP),
                            JwOfficialLibrary.MAX_ZIP_BYTES,
                        )
                        val sig = fetcher.fetchBytes(
                            JwOfficialLibrary.assetUrl(mirror, version, JwOfficialLibrary.SIG),
                            JwOfficialLibrary.MAX_SIG_BYTES,
                        )
                        val bundle = JwOfficialLibrary.load(zip, sig, appVersionCode, version, publicKeyBase64)
                        return JwOfficialDownload(bundle, zip, sig)
                    } catch (e: Exception) {
                        lastError = e
                    }
                }
            }
        throw lastError
    }

    private companion object {
        const val CHECK_TIMEOUT_S = 20L
    }
}

/**
 * 已下载的官方库：`filesDir/jw-official/` 下一份 zip + 签名。每次加载都重新验签。
 */
class JwOfficialStore(private val dir: File, private val publicKeyBase64: String = JwOfficialLibrary.PUBLIC_KEY) {

    fun save(download: JwOfficialDownload) {
        if (!dir.exists() && !dir.mkdirs()) throw JwPackageException("无法创建目录：${dir.path}")
        // 先删签名再换 zip：中途崩溃最多留下「zip 无签名」，加载时验签失败回落内置库
        val zipFile = File(dir, JwOfficialLibrary.ZIP)
        val sigFile = File(dir, JwOfficialLibrary.SIG)
        sigFile.delete()
        writeAtomically(zipFile, download.zip)
        writeAtomically(sigFile, download.signature)
    }

    /** 读取并验签；没有 / 损坏 / 与本应用不兼容都返回 null（调用方回落内置库）。 */
    fun load(appVersionCode: Int): JwLibraryBundle? = runCatching {
        val zip = File(dir, JwOfficialLibrary.ZIP).takeIf { it.isFile }?.readBytes() ?: return null
        val sig = File(dir, JwOfficialLibrary.SIG).takeIf { it.isFile }?.readBytes() ?: return null
        JwOfficialLibrary.load(zip, sig, appVersionCode, publicKeyBase64 = publicKeyBase64)
    }.getOrNull()

    private fun writeAtomically(target: File, bytes: ByteArray) {
        val temp = File(dir, ".${target.name}.tmp")
        temp.writeBytes(bytes)
        if (!temp.renameTo(target)) {
            temp.delete()
            throw JwPackageException("写入失败：${target.path}")
        }
    }

    companion object {
        const val DIR_NAME = "jw-official"
    }
}
