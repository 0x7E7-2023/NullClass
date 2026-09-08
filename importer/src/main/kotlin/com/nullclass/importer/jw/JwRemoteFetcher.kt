package com.nullclass.importer.jw

/** 远端取文本（Android 侧用 OkHttp 实现；测试用假实现）。 */
interface JwRemoteFetcher {
    /**
     * @param maxBytes 超过上限应抛 [JwRemoteException]，不要静默截断
     */
    fun fetchText(url: String, maxBytes: Int = DEFAULT_MAX_BYTES): String

    companion object {
        const val DEFAULT_MAX_BYTES = 256 * 1024
        const val TIMEOUT_MS = 10_000
    }
}

class JwRemoteException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 适配器库客户端：把「用户粘的一个 URL」变成可安装的适配器。
 *
 * 只做编排（归一化 → 取索引 → 逐文件取包 → 校验），网络细节交给 [JwRemoteFetcher]。
 */
class JwLibraryClient(
    private val fetcher: JwRemoteFetcher,
    private val appVersionCode: Int,
    private val allowInsecure: Boolean = false,
) {

    /** 用户输入的地址 → 归一化后的 index.json URL。 */
    fun normalizeUrl(input: String): String = JwLibraryUrl.normalize(input, allowInsecure)

    fun loadIndex(input: String): JwLibrarySnapshot {
        val url = normalizeUrl(input)
        val raw = try {
            fetcher.fetchText(url)
        } catch (e: JwRemoteException) {
            throw JwRemoteException("无法读取适配器库索引：${e.message}", e)
        }
        return JwLibrarySnapshot(url, JwLibraryIndexCodec.decode(raw))
    }

    /** 按索引条目取回并校验一个适配器。 */
    fun fetchAdapter(snapshot: JwLibrarySnapshot, entry: JwLibraryEntry): JwAdapter {
        val base = JwLibraryUrl.resolve(snapshot.url, entry.path)
        val files = LinkedHashMap<String, ByteArray>()
        var totalBytes = 0L

        fun fetch(relative: String): ByteArray {
            if (!JwPaths.isSafePackagePath(relative)) {
                throw JwPackageException("库内路径不合法：$relative")
            }
            val url = "$base/$relative"
            val text = try {
                fetcher.fetchText(url)
            } catch (e: JwRemoteException) {
                throw JwRemoteException("无法读取 ${entry.key} 的 $relative：${e.message}", e)
            }
            val bytes = text.toByteArray(Charsets.UTF_8)
            totalBytes += bytes.size
            if (totalBytes > JwPackageReader.MAX_TOTAL_BYTES) {
                throw JwPackageException("适配器包下载超过 ${JwPackageReader.MAX_TOTAL_BYTES / 1024}KB 上限，已中止")
            }
            return bytes.also { files[relative] = it }
        }

        val manifestBytes = fetch(JwPackageReader.MANIFEST)
        val manifest = JwManifestCodec.decode(manifestBytes.toString(Charsets.UTF_8))
        JwManifestCodec.validate(manifest, appVersionCode)
        if (manifest.key != entry.key) {
            throw JwPackageException("库索引里「${entry.key}」指向的 manifest key 是「${manifest.key}」，不一致")
        }
        if (manifest.fixtures.size > MAX_FIXTURES) {
            throw JwPackageException("适配器「${entry.key}」声明的 fixtures 过多（超过 $MAX_FIXTURES 个）")
        }
        fetch(manifest.extract)
        manifest.parse?.let { fetch(it) }
        manifest.fixtures.forEach { fixture ->
            fetch(fixture.extracted)
            fetch(fixture.expected)
        }

        val installInfo = JwInstallInfo(sourceUrl = "$base/", installedAt = 0L)
        return JwPackageReader.build(
            files = files,
            source = JwAdapterSource.USER,
            installInfo = installInfo,
            appVersionCode = appVersionCode,
        ).adapters.first()
    }

    private companion object {
        /** 单个适配器允许声明的 fixture 数量上限（防止远端 manifest 逼出上千次请求）。 */
        const val MAX_FIXTURES = 16
    }
}

/** 一次库会话：归一化后的索引地址 + 索引内容。 */
data class JwLibrarySnapshot(val url: String, val index: JwLibraryIndex)
