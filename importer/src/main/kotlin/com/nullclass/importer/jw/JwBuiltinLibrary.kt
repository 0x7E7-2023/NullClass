package com.nullclass.importer.jw

/**
 * 内置适配器库：构建时由仓库根目录 `jw-adapters/` 打进 Java 资源（见 `importer/build.gradle.kts` 的 Sync 任务）。
 *
 * 资源里只需按索引逐文件读取——脚本名与 fixture 路径都在 manifest 里，不需要列目录。
 */
object JwBuiltinLibrary {

    const val RESOURCE_ROOT = "jw-adapters"

    /**
     * 加载全部内置适配器。任何一个清单不合法都抛异常（内置库是随包发布的，坏了必须炸在 CI 里）。
     */
    fun load(
        appVersionCode: Int,
        classLoader: ClassLoader = JwBuiltinLibrary::class.java.classLoader
            ?: Thread.currentThread().contextClassLoader
            ?: throw JwPackageException("无法获取 ClassLoader"),
    ): List<JwAdapter> = loadLibrary(appVersionCode, classLoader).adapters

    /** 同 [load]，并带上整库版本（index.json 的 `version`）。 */
    fun loadLibrary(
        appVersionCode: Int,
        classLoader: ClassLoader = JwBuiltinLibrary::class.java.classLoader
            ?: Thread.currentThread().contextClassLoader
            ?: throw JwPackageException("无法获取 ClassLoader"),
    ): JwLibraryBundle = loadFrom(appVersionCode) { path -> readResource(classLoader, "$RESOURCE_ROOT/$path") }

    /**
     * 按「库根下相对路径 → 字节」读取整库（内置资源、热更新下载的 zip 共用）。
     * 任何一个清单不合法都抛异常。
     */
    fun loadFrom(appVersionCode: Int, read: (String) -> ByteArray?): JwLibraryBundle {
        val indexRaw = read(JwPackageReader.INDEX)
            ?: throw JwPackageException("适配器库缺少 ${JwPackageReader.INDEX}")
        val index = JwLibraryIndexCodec.decode(indexRaw.toString(Charsets.UTF_8))
        // 版本号参与热更新比较，格式不对必须炸在 CI 里，不能留到启动时比较才崩
        if (index.version != null && !JwOfficialLibrary.isValidVersion(index.version)) {
            throw JwPackageException("适配器库版本号不合法：${index.version}（应为 2026.09.22 这类数字点分）")
        }
        val adapters = index.adapters.map { entry -> loadOne(read, entry, appVersionCode) }
        val dupes = adapters.groupBy { it.key }.filterValues { it.size > 1 }.keys
        if (dupes.isNotEmpty()) throw JwPackageException("适配器库里有重复的 key：${dupes.joinToString("、")}")
        return JwLibraryBundle(index.version, adapters)
    }

    private fun loadOne(
        read: (String) -> ByteArray?,
        entry: JwLibraryEntry,
        appVersionCode: Int,
    ): JwAdapter {
        val base = entry.path
        val manifestBytes = read("$base/${JwPackageReader.MANIFEST}")
            ?: throw JwPackageException("内置适配器「${entry.key}」缺少 ${JwPackageReader.MANIFEST}")
        val manifest = JwManifestCodec.decode(manifestBytes.toString(Charsets.UTF_8))
        JwManifestCodec.validate(manifest, appVersionCode)
        if (manifest.key != entry.key) {
            throw JwPackageException("内置库索引里的 key「${entry.key}」与 manifest 的「${manifest.key}」不一致")
        }

        val files = LinkedHashMap<String, ByteArray>()
        files[JwPackageReader.MANIFEST] = manifestBytes

        fun readScript(relative: String, field: String): ByteArray {
            val bytes = read("$base/$relative")
                ?: throw JwPackageException("内置适配器「${manifest.name}」缺少 $field 脚本 $relative")
            files[relative] = bytes
            return bytes
        }

        val extractBytes = readScript(manifest.extract, "extract")
        val parseBytes = manifest.parse?.let { readScript(it, "parse") }
        manifest.fixtures.forEach { fixture ->
            listOf(fixture.extracted, fixture.expected).forEach { path ->
                val bytes = read("$base/$path")
                    ?: throw JwPackageException("内置适配器「${manifest.name}」缺少 fixture 文件 $path")
                files[path] = bytes
            }
        }

        return JwAdapter(
            manifest = manifest,
            source = JwAdapterSource.BUILTIN,
            extractScript = extractBytes.toString(Charsets.UTF_8),
            parseScript = parseBytes?.toString(Charsets.UTF_8),
            files = files,
        )
    }

    private fun readResource(classLoader: ClassLoader, path: String): ByteArray? =
        classLoader.getResourceAsStream(path)?.use { it.readBytes() }
}

/** 一整个库：版本（可空）+ 适配器。 */
data class JwLibraryBundle(val version: String?, val adapters: List<JwAdapter>)
