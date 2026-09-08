package com.nullclass.importer.jw

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JwUserAdapterStoreTest {

    private val tempDir: File = Files.createTempDirectory("jw-store-test").toFile()

    @AfterTest
    fun cleanUp() {
        tempDir.deleteRecursively()
    }

    private fun adapter(key: String = "demo-univ"): JwAdapter {
        val manifest = JwManifest(
            key = key,
            name = "示例大学",
            version = "1.0.0",
            loginUrl = "https://jw.example.edu.cn/",
        )
        return JwAdapter(
            manifest = manifest,
            source = JwAdapterSource.USER,
            extractScript = "(function(){return '{}';})()",
            parseScript = null,
            files = mapOf(
                "manifest.json" to JwManifestCodec.encode(manifest).toByteArray(),
                "extract.js" to "(function(){return '{}';})()".toByteArray(),
            ),
        )
    }

    @Test
    fun `安装后能列出 能找到 能删除`() {
        val store = JwUserAdapterStore(tempDir, appVersionCode = 11)
        store.install(listOf(adapter()), installedAt = 99L)

        val library = store.list()
        assertEquals(1, library.adapters.size)
        assertTrue(library.broken.isEmpty())
        assertEquals("demo-univ", library.adapters[0].key)
        assertEquals(99L, library.adapters[0].installInfo?.installedAt)
        assertTrue(library.adapters[0].rootDir!!.isDirectory)

        assertTrue(store.delete("demo-univ"))
        assertTrue(store.list().adapters.isEmpty())
        assertFalse(store.delete("demo-univ"))
    }

    @Test
    fun `重复安装覆盖旧版本`() {
        val store = JwUserAdapterStore(tempDir, appVersionCode = 11)
        store.install(listOf(adapter()), installedAt = 1L)
        val upgraded = adapter().let { it.copy(manifest = it.manifest.copy(version = "2.0.0")) }
        store.install(listOf(upgraded), installedAt = 2L)

        val loaded = store.find("demo-univ")!!
        assertEquals("2.0.0", loaded.manifest.version)
        assertEquals(2L, loaded.installInfo?.installedAt)
    }

    @Test
    fun `同 key 重装后内容变了就不相等`() {
        val first = adapter()
        val updated = first.copy(extractScript = "(function(){return '{\"v\":2}';})()")
        // 只比 key+来源的话，重装同一 key 会被判成「状态没变」→ StateFlow 不发新值 →
        // 界面继续用旧脚本，直到杀进程才生效
        assertFalse(first == updated, "脚本变了必须不相等")
        assertEquals(first, adapter())
        assertEquals(first.hashCode(), adapter().hashCode())
    }

    @Test
    fun `损坏的目录只影响自己 不炸整个列表`() {
        val store = JwUserAdapterStore(tempDir, appVersionCode = 11)
        store.install(listOf(adapter()), installedAt = 1L)
        File(tempDir, "broken-univ").apply { mkdirs() }.let { File(it, "manifest.json").writeText("{oops") }

        val library = store.list()
        assertEquals(1, library.adapters.size)
        assertEquals(1, library.broken.size)
        assertEquals("broken-univ", library.broken[0].key)
        assertTrue(store.delete("broken-univ"))
    }

    @Test
    fun `非法 key 不被落盘`() {
        val store = JwUserAdapterStore(tempDir, appVersionCode = 11)
        val bad = adapter("../evil")
        assertTrue(runCatching { store.install(listOf(bad), installedAt = 1L) }.isFailure)
        assertNull(store.find("../evil"))
    }

    @Test
    fun `仓库层拒绝覆盖内置 key`() {
        val store = JwUserAdapterStore(tempDir, appVersionCode = 11)
        val builtin = adapter("builtin-univ").copy(source = JwAdapterSource.BUILTIN)
        val repository = JwAdapterRepository(builtin = listOf(builtin), store = store)

        assertTrue(repository.isBuiltinKey("builtin-univ"))
        val error = runCatching {
            repository.install(JwPackage(listOf(adapter("builtin-univ"))), installedAt = 1L)
        }.exceptionOrNull()
        assertTrue(error is JwPackageException)
        assertTrue(error.message!!.contains("内置"))
        assertFalse(repository.delete("builtin-univ"))
    }

    @Test
    fun `仓库层合并内置与用户适配器`() {
        val store = JwUserAdapterStore(tempDir, appVersionCode = 11)
        val builtin = adapter("builtin-univ").copy(source = JwAdapterSource.BUILTIN)
        val repository = JwAdapterRepository(builtin = listOf(builtin), store = store)
        repository.install(JwPackage(listOf(adapter("user-univ"))), installedAt = 1L)

        assertEquals(setOf("builtin-univ", "user-univ"), repository.all().map { it.key }.toSet())
        assertEquals("user-univ", repository.byKey("user-univ")!!.key)
    }
}
