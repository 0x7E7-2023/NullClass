package com.nullclass.importer.jw

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JwPackageReaderTest {

    private val manifestJson = """
        {"specVersion":1,"key":"demo-univ","name":"示例大学","version":"1.0.0",
         "loginUrl":"https://jw.example.edu.cn/","extract":"extract.js","parse":"parse.js",
         "fixtures":[{"name":"基本","extracted":"fixtures/a.extracted.json","expected":"fixtures/a.expected.json"}]}
    """.trimIndent()

    private fun files(root: String = ""): Map<String, ByteArray> {
        val prefix = if (root.isEmpty()) "" else "$root/"
        return mapOf(
            "${prefix}manifest.json" to manifestJson.toByteArray(),
            "${prefix}extract.js" to "(function(){return '{}';})()".toByteArray(),
            "${prefix}parse.js" to "(function(){return __ncInput;})()".toByteArray(),
            "${prefix}fixtures/a.extracted.json" to "{}".toByteArray(),
            "${prefix}fixtures/a.expected.json" to "{}".toByteArray(),
        )
    }

    private fun zip(entries: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            entries.forEach { (name, bytes) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun `根目录布局解析成功`() {
        val pkg = JwPackageReader.build(files(), JwAdapterSource.USER, appVersionCode = 11)
        assertEquals(1, pkg.adapters.size)
        assertEquals("demo-univ", pkg.adapters[0].key)
        assertTrue(pkg.adapters[0].extractScript.isNotEmpty())
        assertEquals("(function(){return __ncInput;})()", pkg.adapters[0].parseScript)
    }

    @Test
    fun `嵌套目录布局解析成功`() {
        val pkg = JwPackageReader.build(files("demo-univ"), JwAdapterSource.BUILTIN, appVersionCode = 11)
        assertEquals("demo-univ", pkg.adapters.single().key)
    }

    @Test
    fun `整库 zip 解析出多个适配器`() {
        val second = files("other-univ").mapKeys { (k, _) -> k }
            .toMutableMap()
            .apply {
                this["other-univ/manifest.json"] = manifestJson.replace("demo-univ", "other-univ").toByteArray()
            }
        val all = files("demo-univ") + second
        val pkg = JwPackageReader.readZip(zip(all.entries.map { it.key to it.value }), JwAdapterSource.USER, appVersionCode = 11)
        assertEquals(setOf("demo-univ", "other-univ"), pkg.adapters.map { it.key }.toSet())
    }

    @Test
    fun `zip-slip 条目被丢弃而不是解压到目录外`() {
        val entries = files().entries.map { it.key to it.value } + listOf(
            "../evil.js" to "boom".toByteArray(),
            "/etc/passwd" to "boom".toByteArray(),
            "a/../../evil.js" to "boom".toByteArray(),
        )
        val pkg = JwPackageReader.readZip(zip(entries), JwAdapterSource.USER, appVersionCode = 11)
        val adapter = pkg.adapters.single()
        assertTrue(adapter.files.keys.none { it.contains("evil") }, adapter.files.keys.toString())
    }

    @Test
    fun `超过单文件上限的包被拒绝`() {
        val big = ByteArray(JwPackageReader.MAX_FILE_BYTES + 1)
        val entries = files().entries.map { it.key to it.value } + ("big.js" to big)
        val error = assertFailsWith<JwPackageException> {
            JwPackageReader.readZip(zip(entries), JwAdapterSource.USER, appVersionCode = 11)
        }
        assertTrue(error.message!!.contains("上限"), error.message)
    }

    @Test
    fun `条目数超限的包被拒绝`() {
        val entries = files().entries.map { it.key to it.value } +
            (0..JwPackageReader.MAX_ENTRIES).map { "f$it.txt" to "x".toByteArray() }
        assertFailsWith<JwPackageException> {
            JwPackageReader.readZip(zip(entries), JwAdapterSource.USER, appVersionCode = 11)
        }
    }

    @Test
    fun `缺少脚本时拒绝安装`() {
        val withoutParse = files().filterKeys { it != "parse.js" }
        val error = assertFailsWith<JwPackageException> {
            JwPackageReader.build(withoutParse, JwAdapterSource.USER, appVersionCode = 11)
        }
        assertTrue(error.message!!.contains("parse.js"), error.message)
    }

    @Test
    fun `声明了 fixture 但文件缺失时拒绝安装`() {
        val missing = files().filterKeys { it != "fixtures/a.expected.json" }
        assertFailsWith<JwPackageException> {
            JwPackageReader.build(missing, JwAdapterSource.USER, appVersionCode = 11)
        }
    }

    @Test
    fun `没有 manifest 时给出可读错误`() {
        val error = assertFailsWith<JwPackageException> {
            JwPackageReader.build(mapOf("a.js" to "x".toByteArray()), JwAdapterSource.USER, appVersionCode = 11)
        }
        assertTrue(error.message!!.contains("manifest.json"), error.message)
    }

    @Test
    fun `包内深度超过两段被拒绝`() {
        val deep = files() + ("a/b/c.js" to "x".toByteArray())
        assertFailsWith<JwPackageException> {
            JwPackageReader.build(deep, JwAdapterSource.USER, appVersionCode = 11)
        }
    }

    @Test
    fun `路径工具拒绝穿越`() {
        assertNull(JwPaths.normalizeZipEntry("../a.js"))
        assertNull(JwPaths.normalizeZipEntry("a/../../b.js"))
        assertNull(JwPaths.normalizeZipEntry("C:/a.js"))
        assertEquals("a/b.js", JwPaths.normalizeZipEntry("a\\b.js"))
        assertNull(JwPaths.normalizeZipEntry("dir/"))
        assertEquals("dir/a.js", JwPaths.normalizeZipEntry("./dir/a.js"))
        assertTrue(JwPaths.isSafePackagePath("fixtures/a.json"))
        assertTrue(!JwPaths.isSafePackagePath("a/b/c.json"))
    }
}
