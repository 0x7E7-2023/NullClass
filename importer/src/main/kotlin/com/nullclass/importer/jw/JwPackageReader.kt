package com.nullclass.importer.jw

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * 适配器包读取器：目录、zip、内存文件表 → [JwPackage]。
 *
 * 安全边界（用户可导入任意 zip，这里必须挡）：
 * - 条目数 ≤ [MAX_ENTRIES]、单文件 ≤ [MAX_FILE_BYTES]、累计 ≤ [MAX_TOTAL_BYTES]（zip 炸弹）
 * - 路径一律走 [JwPaths]（绝对路径 / `..` / 盘符 / 反斜杠全部拒绝，zip-slip）
 * - 包内文件深度 ≤ 2 段
 */
object JwPackageReader {

    const val MAX_ENTRIES = 64
    const val MAX_FILE_BYTES = 256 * 1024
    const val MAX_TOTAL_BYTES = 2 * 1024 * 1024

    const val MANIFEST = "manifest.json"
    const val INDEX = "index.json"

    /** 解析 zip 字节。含 `index.json` 的整库压缩包会解析出多个适配器。 */
    fun readZip(
        bytes: ByteArray,
        source: JwAdapterSource,
        installInfo: JwInstallInfo? = null,
        appVersionCode: Int,
    ): JwPackage {
        val files = LinkedHashMap<String, ByteArray>()
        var total = 0L
        var count = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                count++
                if (count > MAX_ENTRIES) {
                    throw JwPackageException("适配器包条目过多（超过 $MAX_ENTRIES 个）")
                }
                val path = JwPaths.normalizeZipEntry(entry.name)
                if (path != null) {
                    val data = readCapped(zis, MAX_FILE_BYTES)
                    total += data.size
                    if (total > MAX_TOTAL_BYTES) {
                        throw JwPackageException("适配器包解压后过大（超过 ${MAX_TOTAL_BYTES / 1024}KB）")
                    }
                    if (files.put(path, data) != null) {
                        throw JwPackageException("适配器包内有重复条目：$path")
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        if (files.isEmpty()) throw JwPackageException("适配器包是空的")
        return build(files, source, installInfo, appVersionCode)
    }

    /** 解析**单个适配器目录**（深度 ≤ 2 段）。 */
    fun readDirectory(
        root: File,
        source: JwAdapterSource,
        installInfo: JwInstallInfo? = null,
        appVersionCode: Int,
    ): JwPackage {
        if (!root.isDirectory) throw JwPackageException("适配器目录不存在：${root.path}")
        val files = LinkedHashMap<String, ByteArray>()
        var total = 0L
        collect(root, "", files) { size ->
            total += size
            if (total > MAX_TOTAL_BYTES) {
                throw JwPackageException("适配器目录解压后过大（超过 ${MAX_TOTAL_BYTES / 1024}KB）")
            }
        }
        return build(files, source, installInfo, appVersionCode)
    }

    private fun collect(dir: File, prefix: String, out: MutableMap<String, ByteArray>, onBytes: (Long) -> Unit) {
        val children = dir.listFiles() ?: return
        for (child in children) {
            if (child.name.startsWith(".")) continue
            if (java.nio.file.Files.isSymbolicLink(child.toPath())) {
                throw JwPackageException("适配器目录里不允许符号链接：${child.name}")
            }
            val path = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
            if (child.isDirectory) {
                collect(child, path, out, onBytes)
            } else if (child.isFile) {
                if (!JwPaths.isSafePackagePath(path)) {
                    throw JwPackageException("适配器目录里有非法路径：$path")
                }
                if (out.size >= MAX_ENTRIES) throw JwPackageException("适配器目录文件数过多（超过 $MAX_ENTRIES 个）")
                val data = child.readBytes()
                if (data.size > MAX_FILE_BYTES) {
                    throw JwPackageException("文件超过 ${MAX_FILE_BYTES / 1024}KB 上限：$path")
                }
                onBytes(data.size.toLong())
                out[path] = data
            }
        }
    }

    /**
     * 由「相对路径 → 内容」构建包。
     *
     * 允许两种布局：`manifest.json` 在根，或位于唯一的一级目录下；
     * 多个一级目录各含 `manifest.json` 时视为整库压缩包，全部解析。
     */
    fun build(
        files: Map<String, ByteArray>,
        source: JwAdapterSource,
        installInfo: JwInstallInfo? = null,
        appVersionCode: Int,
    ): JwPackage {
        if (files.size > MAX_ENTRIES) throw JwPackageException("适配器包文件数过多（超过 $MAX_ENTRIES 个）")
        val totalBytes = files.values.sumOf { it.size.toLong() }
        if (totalBytes > MAX_TOTAL_BYTES) {
            throw JwPackageException("适配器包过大（超过 ${MAX_TOTAL_BYTES / 1024}KB）")
        }
        files.forEach { (path, data) ->
            if (!JwPaths.isSafeRelative(path)) throw JwPackageException("适配器包内有非法路径：$path")
            if (data.size > MAX_FILE_BYTES) throw JwPackageException("文件超过 ${MAX_FILE_BYTES / 1024}KB 上限：$path")
        }

        val roots = files.keys
            .filter { it == MANIFEST || it.endsWith("/$MANIFEST") }
            .map { it.removeSuffix(MANIFEST).removeSuffix("/") }
            .distinct()
        if (roots.isEmpty()) throw JwPackageException("适配器包里没有找到 $MANIFEST")

        val adapters = roots.map { readAdapter(files, it, source, installInfo, appVersionCode) }
        val duplicated = adapters.groupBy { it.key }.filterValues { it.size > 1 }.keys
        if (duplicated.isNotEmpty()) {
            throw JwPackageException("适配器包里有重复的 key：${duplicated.joinToString("、")}")
        }
        val libraryName = files[INDEX]?.let { raw ->
            runCatching { JwLibraryIndexCodec.decode(raw.toString(Charsets.UTF_8)).name }.getOrNull()
        }
        return JwPackage(adapters, libraryName)
    }

    /** 读取适配器目录下的 fixture 文件（CI / 自检用）。 */
    fun readFixture(adapterRoot: File, relative: String): ByteArray {
        if (!JwPaths.isSafePackagePath(relative)) throw JwPackageException("fixture 路径不合法：$relative")
        val file = File(adapterRoot, relative)
        if (!file.isFile) throw JwPackageException("fixture 文件不存在：$relative")
        return file.readBytes()
    }

    private fun readAdapter(
        files: Map<String, ByteArray>,
        root: String,
        source: JwAdapterSource,
        installInfo: JwInstallInfo?,
        appVersionCode: Int,
    ): JwAdapter {
        val prefix = if (root.isEmpty()) "" else "$root/"
        val pkg = files
            .filterKeys { it.startsWith(prefix) }
            .mapKeys { (key, _) -> key.removePrefix(prefix) }

        pkg.keys.forEach { path ->
            if (!JwPaths.isSafePackagePath(path)) {
                throw JwPackageException("适配器「$root」里有非法路径：$path")
            }
        }

        val manifestBytes = pkg[MANIFEST]
            ?: throw JwPackageException("适配器「$root」缺少 $MANIFEST")
        val manifest = JwManifestCodec.decode(manifestBytes.toString(Charsets.UTF_8))
        JwManifestCodec.validate(manifest, appVersionCode)

        val extractBytes = pkg[manifest.extract]
            ?: throw JwPackageException("适配器「${manifest.name}」缺少脚本 ${manifest.extract}")
        val parseBytes = manifest.parse?.let { path ->
            pkg[path] ?: throw JwPackageException("适配器「${manifest.name}」缺少脚本 $path")
        }
        manifest.fixtures.forEach { fixture ->
            if (fixture.extracted !in pkg) {
                throw JwPackageException("适配器「${manifest.name}」缺少 fixture 文件 ${fixture.extracted}")
            }
            if (fixture.expected !in pkg) {
                throw JwPackageException("适配器「${manifest.name}」缺少 fixture 文件 ${fixture.expected}")
            }
        }

        return JwAdapter(
            manifest = manifest,
            source = source,
            extractScript = extractBytes.toString(Charsets.UTF_8),
            parseScript = parseBytes?.toString(Charsets.UTF_8),
            files = pkg,
            installInfo = installInfo,
        )
    }

    private fun readCapped(input: InputStream, cap: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(8 * 1024)
        while (true) {
            val read = input.read(chunk)
            if (read < 0) break
            out.write(chunk, 0, read)
            if (out.size() > cap) throw JwPackageException("包内单个文件超过 ${cap / 1024}KB 上限")
        }
        return out.toByteArray()
    }
}
