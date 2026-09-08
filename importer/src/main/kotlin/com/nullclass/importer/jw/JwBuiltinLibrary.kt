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
    ): List<JwAdapter> {
        val indexRaw = readResource(classLoader, "$RESOURCE_ROOT/${JwPackageReader.INDEX}")
            ?: throw JwPackageException("内置适配器库缺少 $RESOURCE_ROOT/${JwPackageReader.INDEX}（构建接线有问题）")
        val index = JwLibraryIndexCodec.decode(indexRaw.toString(Charsets.UTF_8))
        return index.adapters.map { entry ->
            loadOne(classLoader, entry, appVersionCode)
        }
    }

    private fun loadOne(
        classLoader: ClassLoader,
        entry: JwLibraryEntry,
        appVersionCode: Int,
    ): JwAdapter {
        val base = if (entry.path.isEmpty()) RESOURCE_ROOT else "$RESOURCE_ROOT/${entry.path}"
        val manifestBytes = readResource(classLoader, "$base/${JwPackageReader.MANIFEST}")
            ?: throw JwPackageException("内置适配器「${entry.key}」缺少 ${JwPackageReader.MANIFEST}")
        val manifest = JwManifestCodec.decode(manifestBytes.toString(Charsets.UTF_8))
        JwManifestCodec.validate(manifest, appVersionCode)
        if (manifest.key != entry.key) {
            throw JwPackageException("内置库索引里的 key「${entry.key}」与 manifest 的「${manifest.key}」不一致")
        }

        val files = LinkedHashMap<String, ByteArray>()
        files[JwPackageReader.MANIFEST] = manifestBytes

        fun readScript(relative: String, field: String): ByteArray {
            val bytes = readResource(classLoader, "$base/$relative")
                ?: throw JwPackageException("内置适配器「${manifest.name}」缺少 $field 脚本 $relative")
            files[relative] = bytes
            return bytes
        }

        val extractBytes = readScript(manifest.extract, "extract")
        val parseBytes = manifest.parse?.let { readScript(it, "parse") }
        manifest.fixtures.forEach { fixture ->
            listOf(fixture.extracted, fixture.expected).forEach { path ->
                val bytes = readResource(classLoader, "$base/$path")
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
