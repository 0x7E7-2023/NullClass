package com.nullclass.importer.jw

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JwOfficialLibraryTest {

    private val libraryDir = File(System.getProperty("jwLibraryDir") ?: error("缺少 jwLibraryDir"))
    private val keyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
        .generateKeyPair()
    private val publicKey = Base64.getEncoder().encodeToString(keyPair.public.encoded)
    private val tempDir: File = Files.createTempDirectory("jw-official-test").toFile()

    @AfterTest
    fun cleanUp() {
        tempDir.deleteRecursively()
    }

    /** 用真实适配器库打包，index.json 的版本改成 [version]。 */
    private fun libraryZip(version: String): ByteArray {
        val indexRaw = File(libraryDir, "index.json").readText()
        val index = JwLibraryIndexCodec.decode(indexRaw).copy(version = version)
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("index.json"))
            zip.write(JwLibraryIndexCodec.encode(index).toByteArray())
            index.adapters.forEach { entry ->
                File(libraryDir, entry.path).walkTopDown().filter { it.isFile }.forEach { file ->
                    zip.putNextEntry(ZipEntry("${entry.path}/${file.relativeTo(File(libraryDir, entry.path)).invariantSeparatorsPath}"))
                    zip.write(file.readBytes())
                }
            }
        }
        return out.toByteArray()
    }

    private fun sign(bytes: ByteArray, key: PrivateKey = keyPair.private): ByteArray =
        Signature.getInstance("SHA256withECDSA").run {
            initSign(key)
            update(bytes)
            sign()
        }

    @Test
    fun `signed zip loads the whole library`() {
        val zip = libraryZip("2026.9.22")
        val bundle = JwOfficialLibrary.load(zip, sign(zip), appVersionCode = Int.MAX_VALUE, "2026.9.22", publicKey)
        assertEquals("2026.9.22", bundle.version)
        assertEquals(JwBuiltinLibrary.loadLibrary(Int.MAX_VALUE).adapters.size, bundle.adapters.size)
    }

    @Test
    fun `tampered zip, foreign key and version mismatch are rejected`() {
        val zip = libraryZip("2.0")
        val sig = sign(zip)
        val tampered = zip.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
        assertFailsWith<JwPackageException> { JwOfficialLibrary.load(tampered, sig, Int.MAX_VALUE, null, publicKey) }
        assertFailsWith<JwPackageException> { JwOfficialLibrary.load(zip, sig, Int.MAX_VALUE, null) } // 生产公钥
        assertFailsWith<JwPackageException> { JwOfficialLibrary.load(zip, sig, Int.MAX_VALUE, "3.0", publicKey) }
    }

    @Test
    fun `versions compare numerically and null is oldest`() {
        assertTrue(JwOfficialLibrary.compareVersions("2026.10.1", "2026.9.30") > 0)
        assertEquals(0, JwOfficialLibrary.compareVersions("1.0", "1"))
        assertTrue(JwOfficialLibrary.compareVersions("1", null) > 0)
        assertEquals("1.2", JwOfficialLibrary.parseLatest("""{"version":"1.2"}"""))
        assertFailsWith<JwPackageException> { JwOfficialLibrary.parseLatest("""{"version":"1.2-beta"}""") }
        assertFailsWith<JwPackageException> {
            JwBuiltinLibrary.loadFrom(1) { if (it == "index.json") """{"version":"1.0-rc1"}""".toByteArray() else null }
        }
    }

    @Test
    fun `lying mirror is skipped and download falls back to the next valid version`() {
        val good = libraryZip("2.0")
        val goodSig = sign(good)
        val bad = libraryZip("9.0")
        val badSig = sign(bad, KeyPairGenerator.getInstance("EC").generateKeyPair().private)
        val evil = "https://evil.example/"
        val fetcher = object : JwRemoteFetcher {
            override fun fetchBytes(url: String, maxBytes: Int): ByteArray = when {
                url.startsWith(evil) && url.endsWith(JwOfficialLibrary.LATEST) -> """{"version":"9.0"}""".toByteArray()
                url.startsWith(evil) && url.endsWith(".sig") -> badSig
                url.startsWith(evil) -> bad
                url.endsWith(JwOfficialLibrary.LATEST) -> """{"version":"2.0"}""".toByteArray()
                url.contains("/v2.0/") && url.endsWith(".sig") -> goodSig
                url.contains("/v2.0/") -> good
                else -> throw JwRemoteException("404")
            }
        }
        val updater = JwOfficialUpdater(fetcher, Int.MAX_VALUE, listOf("", evil, "https://down.example/"), publicKey)
        val check = updater.check()
        assertEquals("9.0", check.latest)
        val download = updater.download(check, currentVersion = "1.0")
        assertEquals("2.0", download.version)

        val store = JwOfficialStore(tempDir, publicKey)
        assertNull(store.load(Int.MAX_VALUE))
        store.save(download)
        assertEquals("2.0", store.load(Int.MAX_VALUE)?.version)
        File(tempDir, JwOfficialLibrary.ZIP).writeBytes(bad)
        assertNull(store.load(Int.MAX_VALUE))
    }
}
