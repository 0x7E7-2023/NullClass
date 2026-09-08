package com.nullclass.importer.jw

import java.io.File

/** 适配器来源：随 APK 内置，或用户自行导入（zip / 仓库）。 */
enum class JwAdapterSource { BUILTIN, USER }

/** 用户适配器的安装来源（内置适配器为 null）。 */
@kotlinx.serialization.Serializable
data class JwInstallInfo(
    val sourceUrl: String? = null,
    val fileName: String? = null,
    val sha256: String? = null,
    val installedAt: Long = 0L,
)

/**
 * 一个可运行的适配器：清单 + 两段脚本 + 包内全部文件。
 *
 * [files] 的键是**适配器根之下的相对路径**（`manifest.json` / `extract.js` / `fixtures/a.json`），
 * 用户库用它落盘，内置库用它做完整性校验。
 */
data class JwAdapter(
    val manifest: JwManifest,
    val source: JwAdapterSource,
    val extractScript: String,
    val parseScript: String?,
    val files: Map<String, ByteArray>,
    val installInfo: JwInstallInfo? = null,
    val rootDir: File? = null,
) {
    val key: String get() = manifest.key
    val displayName: String get() = manifest.name

    /** 允许发请求的域名：同源主机 + 清单声明。 */
    fun allowedHosts(loginHost: String?): List<String> =
        (listOfNotNull(loginHost) + manifest.allowHosts).map { it.lowercase() }.distinct()

    override fun equals(other: Any?): Boolean =
        other is JwAdapter && other.key == key && other.source == source

    override fun hashCode(): Int = key.hashCode() * 31 + source.hashCode()

    override fun toString(): String = "JwAdapter(${source.name.lowercase()}:$key@${manifest.version})"
}

/** 一个 zip / 目录解析出的内容：可能含多个适配器（整个库的压缩包）。 */
data class JwPackage(val adapters: List<JwAdapter>, val libraryName: String? = null)

/** 适配器包不合法（消息面向用户）。 */
class JwPackageException(message: String) : IllegalArgumentException(message)
