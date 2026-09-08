package com.nullclass.importer.jw

import java.io.File
import java.security.MessageDigest

/** 用户库里的一个坏目录（manifest 损坏等），UI 需要能显示并删除它。 */
data class JwBrokenAdapter(val key: String, val reason: String)

data class JwUserLibrary(
    val adapters: List<JwAdapter> = emptyList(),
    val broken: List<JwBrokenAdapter> = emptyList(),
)

/**
 * 用户适配器存储：`filesDir/jw-adapters/<key>/` 一个目录一个适配器。
 *
 * 目录存在即「已安装」；不额外维护索引文件（避免索引与磁盘不一致）。
 */
class JwUserAdapterStore(
    private val root: File,
    private val appVersionCode: Int,
) {

    /** 扫描已安装的用户适配器；坏目录单独返回而不是让整个列表失败。 */
    fun list(): JwUserLibrary {
        val dirs = root.listFiles { file -> file.isDirectory && !file.name.startsWith(".") }
            ?.sortedBy { it.name.lowercase() }
            .orEmpty()
        val adapters = mutableListOf<JwAdapter>()
        val broken = mutableListOf<JwBrokenAdapter>()
        dirs.forEach { dir ->
            val loaded = runCatching {
                JwPackageReader.readDirectory(dir, JwAdapterSource.USER, appVersionCode = appVersionCode)
                    .adapters.firstOrNull()
                    ?: throw JwPackageException("目录里没有适配器")
            }
            loaded.fold(
                onSuccess = { adapters += it.copy(rootDir = dir, installInfo = readInstallInfo(dir)) },
                onFailure = { broken += JwBrokenAdapter(dir.name, it.message ?: "读取失败") },
            )
        }
        return JwUserLibrary(adapters, broken)
    }

    fun find(key: String): JwAdapter? = list().adapters.firstOrNull { it.key == key }

    /** 安装（覆盖同名 key）。返回落盘后的适配器列表。 */
    fun install(
        adapters: List<JwAdapter>,
        installedAt: Long,
        sourceUrl: String? = null,
        fileName: String? = null,
    ): List<JwAdapter> {
        if (!root.exists() && !root.mkdirs()) {
            throw JwPackageException("无法创建适配器目录：${root.path}")
        }
        val installed = mutableListOf<JwAdapter>()
        adapters.forEach { adapter ->
            val key = adapter.key
            if (!isSafeKey(key)) throw JwPackageException("适配器 key 不合法：$key")
            val target = File(root, key)
            val temp = File(root, ".tmp-$key")
            temp.deleteRecursively()
            if (!temp.mkdirs()) throw JwPackageException("无法创建临时目录：${temp.path}")
            try {
                // 以 manifest 模型为准重新序列化，避免 adapter.manifest 与包内文件不一致；
                // install.json 自己不进哈希（它是元数据，且每次安装都会重写）
                val payload = adapter.files.toMutableMap().apply {
                    remove(INSTALL_FILE)
                    put(JwPackageReader.MANIFEST, JwManifestCodec.encode(adapter.manifest).toByteArray(Charsets.UTF_8))
                }
                payload.forEach { (path, bytes) ->
                    if (!JwPaths.isSafePackagePath(path)) {
                        throw JwPackageException("适配器「$key」内有非法路径：$path")
                    }
                    val out = File(temp, path)
                    out.parentFile?.mkdirs()
                    out.writeBytes(bytes)
                }
                val info = JwInstallInfo(
                    sourceUrl = sourceUrl ?: adapter.installInfo?.sourceUrl,
                    fileName = fileName ?: adapter.installInfo?.fileName,
                    sha256 = sha256Of(payload),
                    installedAt = installedAt,
                )
                File(temp, INSTALL_FILE).writeText(installInfoToJson(info), Charsets.UTF_8)
                if (target.exists() && !target.deleteRecursively()) {
                    throw JwPackageException("无法覆盖已存在的适配器目录：${target.path}")
                }
                if (!temp.renameTo(target)) {
                    throw JwPackageException("适配器落盘失败：${target.path}")
                }
                installed += JwPackageReader.readDirectory(
                    target,
                    JwAdapterSource.USER,
                    installInfo = info,
                    appVersionCode = appVersionCode,
                ).adapters.first().copy(rootDir = target)
            } finally {
                temp.deleteRecursively()
            }
        }
        return installed
    }

    /** 删除；返回是否真的删掉了。 */
    fun delete(key: String): Boolean {
        if (!isSafeKey(key)) return false
        val target = File(root, key)
        if (!target.exists()) return false
        return target.deleteRecursively()
    }

    private fun isSafeKey(key: String): Boolean =
        key.isNotEmpty() && key.all { it.isLetterOrDigit() || it == '-' || it == '_' } && !key.startsWith(".")

    private fun readInstallInfo(dir: File): JwInstallInfo? {
        val file = File(dir, INSTALL_FILE)
        if (!file.isFile) return null
        return runCatching { installInfoFromJson(file.readText(Charsets.UTF_8)) }.getOrNull()
    }

    private fun sha256Of(files: Map<String, ByteArray>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        files.entries.sortedBy { it.key }.forEach { (path, bytes) ->
            digest.update(path.toByteArray(Charsets.UTF_8))
            digest.update(0)
            digest.update(bytes)
            digest.update(0)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun installInfoToJson(info: JwInstallInfo): String = kotlinx.serialization.json.Json.encodeToString(
        JwInstallInfo.serializer(),
        info,
    )

    private fun installInfoFromJson(raw: String): JwInstallInfo =
        kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString(JwInstallInfo.serializer(), raw)

    companion object {
        const val DIR_NAME = "jw-adapters"
        const val INSTALL_FILE = "install.json"
    }
}
