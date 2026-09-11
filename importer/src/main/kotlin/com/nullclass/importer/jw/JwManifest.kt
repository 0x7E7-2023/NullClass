package com.nullclass.importer.jw

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * 教务适配器 `manifest.json`（specVersion 1）。
 *
 * 未知字段忽略（向前兼容）；`specVersion` 高于应用支持值时明确拒绝而不是行为诡异。
 */
@Serializable
data class JwManifest(
    val specVersion: Int = SPEC_VERSION,
    val key: String,
    val name: String,
    val version: String,
    val author: String? = null,
    val homepage: String? = null,
    /**
     * 登录页或课表页。**声明 [startUrlPrompt] 时可以留空**——通用适配器不认学校，
     * 地址由用户在应用里输入。
     */
    val loginUrl: String = "",
    val scheduleUrlHint: String? = null,
    /**
     * 地址由用户现场输入（应用会先弹一个输入框，并把地址记成「一键刷新」的入口）。
     * 用于不针对具体学校的通用适配器：它没有已知的教务域名。
     */
    val startUrlPrompt: Boolean = false,
    /**
     * 置底展示。这类适配器是「兜底手段」而非某所学校，应用会把它们排在列表最下方，
     * 并附上说明——正常找得到学校的用户不该被它分散注意力。
     */
    val fallback: Boolean = false,
    val minAppVersionCode: Int = 0,
    val extract: String = DEFAULT_EXTRACT,
    val parse: String? = null,
    val allowHosts: List<String> = emptyList(),
    val fixtures: List<JwFixture> = emptyList(),
) {
    companion object {
        const val SPEC_VERSION = 1
        const val DEFAULT_EXTRACT = "extract.js"
        const val MAX_NAME_LENGTH = 60
    }
}

/** 适配器自带的回归用例：extract 的真实输出 + 期望的课表载荷。 */
@Serializable
data class JwFixture(val name: String, val extracted: String, val expected: String)

/** manifest 不合法（消息面向用户，可直接展示）。 */
class JwManifestException(message: String) : IllegalArgumentException(message)

object JwManifestCodec {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    private val KEY_REGEX = Regex("^[a-z0-9][a-z0-9-]{1,39}$")
    private val VERSION_REGEX = Regex("^\\d+\\.\\d+\\.\\d+$")

    fun decode(raw: String): JwManifest = try {
        json.decodeFromString<JwManifest>(raw)
    } catch (e: SerializationException) {
        val detail = e.message?.lineSequence()?.firstOrNull()?.trim().orEmpty()
        throw JwManifestException("manifest.json 不是合法的适配器清单${if (detail.isEmpty()) "" else "：$detail"}")
    }

    fun encode(manifest: JwManifest): String = json.encodeToString(manifest)

    /**
     * 校验清单与当前应用版本的兼容性。不通过时抛 [JwManifestException]。
     *
     * @param appVersionCode 应用 versionCode（`BuildConfig.VERSION_CODE`）
     */
    fun validate(manifest: JwManifest, appVersionCode: Int) {
        if (manifest.specVersion > JwManifest.SPEC_VERSION) {
            throw JwManifestException(
                "适配器规范版本 v${manifest.specVersion} 高于本应用支持的 v${JwManifest.SPEC_VERSION}，请先升级空课",
            )
        }
        if (manifest.specVersion < 1) {
            throw JwManifestException("适配器规范版本 v${manifest.specVersion} 不合法")
        }
        if (!KEY_REGEX.matches(manifest.key)) {
            throw JwManifestException("适配器 key「${manifest.key}」不合法（只允许小写字母、数字、连字符，2-40 字符）")
        }
        if (manifest.name.isBlank()) throw JwManifestException("适配器缺少学校名称 name")
        if (manifest.name.length > JwManifest.MAX_NAME_LENGTH) {
            throw JwManifestException("适配器名称过长（最多 ${JwManifest.MAX_NAME_LENGTH} 字）")
        }
        if (!VERSION_REGEX.matches(manifest.version)) {
            throw JwManifestException("适配器版本「${manifest.version}」不合法（应为 x.y.z）")
        }
        if (manifest.loginUrl.isBlank()) {
            if (!manifest.startUrlPrompt) {
                throw JwManifestException("适配器缺少 loginUrl（或声明 startUrlPrompt: true 让用户输入学校地址）")
            }
        } else {
            validateUrl(manifest.loginUrl, "loginUrl")
        }
        manifest.scheduleUrlHint?.let { validateUrl(it, "scheduleUrlHint") }
        if (manifest.minAppVersionCode > appVersionCode) {
            throw JwManifestException(
                "此适配器需要 versionCode ≥ ${manifest.minAppVersionCode} 的空课（当前 $appVersionCode），请先升级应用",
            )
        }
        requireScriptPath(manifest.extract, "extract")
        manifest.parse?.let { requireScriptPath(it, "parse") }
        manifest.allowHosts.forEach { host ->
            JwHostAllowlist.errorOf(host)?.let { throw JwManifestException(it) }
        }
        manifest.fixtures.forEach { fixture ->
            if (fixture.name.isBlank()) throw JwManifestException("fixtures 中有用例缺少 name")
            if (!JwPaths.isSafePackagePath(fixture.extracted)) {
                throw JwManifestException("fixture 路径「${fixture.extracted}」不合法")
            }
            if (!JwPaths.isSafePackagePath(fixture.expected)) {
                throw JwManifestException("fixture 路径「${fixture.expected}」不合法")
            }
        }
    }

    private fun requireScriptPath(path: String, field: String) {
        if (!JwPaths.isSafePackagePath(path)) {
            throw JwManifestException("$field 脚本路径「$path」不合法（必须是包内相对路径）")
        }
        if (!path.endsWith(".js")) {
            throw JwManifestException("$field 脚本必须是 .js 文件（当前「$path」）")
        }
    }

    private fun validateUrl(url: String, field: String) {
        val uri = runCatching { java.net.URI(url) }.getOrNull()
            ?: throw JwManifestException("$field「$url」不是合法 URL")
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            throw JwManifestException("$field 只支持 http/https（当前「$url」）")
        }
        if (uri.host.isNullOrBlank()) throw JwManifestException("$field「$url」缺少主机名")
    }
}
