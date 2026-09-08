package com.nullclass.importer.jw

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.net.URI

/**
 * 适配器库索引（仓库根 `index.json`）。
 *
 * 任何人都可以 fork 一份目录结构、托管自己的 `index.json`，应用直接引用即可。
 */
@Serializable
data class JwLibraryIndex(
    val specVersion: Int = 1,
    val name: String? = null,
    val homepage: String? = null,
    val adapters: List<JwLibraryEntry> = emptyList(),
)

@Serializable
data class JwLibraryEntry(
    val key: String,
    val name: String,
    val version: String? = null,
    /** 相对索引所在目录的适配器子目录。 */
    val path: String,
)

object JwLibraryIndexCodec {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    fun decode(raw: String): JwLibraryIndex {
        val index = try {
            json.decodeFromString<JwLibraryIndex>(raw)
        } catch (e: SerializationException) {
            val detail = e.message?.lineSequence()?.firstOrNull()?.trim().orEmpty()
            throw JwPackageException("库索引不是合法 JSON${if (detail.isEmpty()) "" else "：$detail"}")
        }
        if (index.specVersion > JwManifest.SPEC_VERSION) {
            throw JwPackageException("适配器库规范版本 v${index.specVersion} 高于本应用支持的 v${JwManifest.SPEC_VERSION}，请先升级空课")
        }
        index.adapters.forEach { entry ->
            if (entry.name.isBlank()) throw JwPackageException("库索引里有条目缺少 name")
            if (!JwPaths.isSafeRelative(entry.path)) {
                throw JwPackageException("库索引里「${entry.key}」的 path「${entry.path}」不合法")
            }
        }
        return index
    }

    fun encode(index: JwLibraryIndex): String = json.encodeToString(index)
}

/**
 * 库地址归一化与相对路径解析。
 *
 * 支持用户直接粘 GitHub 仓库地址：`github.com/o/r[/tree/branch]` → `raw.githubusercontent.com/o/r/<branch|HEAD>/index.json`。
 */
object JwLibraryUrl {

    /**
     * @param allowInsecure 仅 debug 构建为 true（本地调试用 http），发布版一律只允许 https。
     */
    fun normalize(input: String, allowInsecure: Boolean = false): String {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) throw JwPackageException("库地址为空")
        val uri = runCatching { URI(trimmed) }.getOrNull()
            ?: throw JwPackageException("库地址不是合法 URL：$trimmed")
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            throw JwPackageException("库地址必须是 http(s) 链接：$trimmed")
        }
        if (scheme == "http" && !allowInsecure) {
            throw JwPackageException("只允许 https 的适配器库地址（当前是 http，传输途中可能被替换）")
        }
        val host = uri.host?.lowercase() ?: throw JwPackageException("库地址缺少主机名：$trimmed")

        if (host == "github.com" || host == "www.github.com") {
            val segments = uri.path.trim('/').split('/').filter { it.isNotEmpty() }
            if (segments.size < 2) throw JwPackageException("GitHub 地址需要形如 github.com/用户名/仓库名")
            val owner = segments[0]
            val repo = segments[1].removeSuffix(".git")
            val branch: String
            val rest: List<String>
            if (segments.size >= 4 && (segments[2] == "tree" || segments[2] == "blob")) {
                branch = segments[3]
                rest = segments.drop(4)
            } else {
                branch = "HEAD"
                rest = segments.drop(2)
            }
            val suffix = if (rest.isEmpty() || rest.last() != INDEX_FILE) rest + INDEX_FILE else rest
            return "https://raw.githubusercontent.com/$owner/$repo/$branch/${suffix.joinToString("/")}"
        }

        val path = uri.path.orEmpty()
        if (path.endsWith("/$INDEX_FILE") || path == "/$INDEX_FILE") return trimmed
        return trimmed.trimEnd('/') + "/$INDEX_FILE"
    }

    /** 把索引里的相对 path 解析成绝对 URL。 */
    fun resolve(baseUrl: String, relative: String): String {
        if (!JwPaths.isSafeRelative(relative)) {
            throw JwPackageException("库内路径不合法：$relative")
        }
        val base = runCatching { URI(baseUrl) }.getOrNull()
            ?: throw JwPackageException("库地址不是合法 URL：$baseUrl")
        return base.resolve(relative).toString()
    }

    private const val INDEX_FILE = "index.json"
}
