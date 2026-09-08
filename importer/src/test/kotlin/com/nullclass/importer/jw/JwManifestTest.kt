package com.nullclass.importer.jw

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JwManifestTest {

    private fun manifest(
        key: String = "demo-univ",
        specVersion: Int = 1,
        version: String = "1.0.0",
        loginUrl: String = "https://jw.example.edu.cn/",
        minAppVersionCode: Int = 0,
        extract: String = "extract.js",
        parse: String? = "parse.js",
        allowHosts: List<String> = emptyList(),
    ) = JwManifest(
        specVersion = specVersion,
        key = key,
        name = "示例大学",
        version = version,
        loginUrl = loginUrl,
        minAppVersionCode = minAppVersionCode,
        extract = extract,
        parse = parse,
        allowHosts = allowHosts,
    )

    @Test
    fun `合法清单通过校验`() {
        JwManifestCodec.validate(manifest(allowHosts = listOf("jw.example.edu.cn")), appVersionCode = 11)
    }

    @Test
    fun `未知字段被忽略 向前兼容`() {
        val raw = """
            {"specVersion":1,"key":"demo-univ","name":"示例大学","version":"1.0.0",
             "loginUrl":"https://jw.example.edu.cn/","extract":"extract.js","futureField":{"a":1}}
        """.trimIndent()
        val decoded = JwManifestCodec.decode(raw)
        assertEquals("demo-univ", decoded.key)
    }

    @Test
    fun `key 非法被拒绝`() {
        listOf("Demo", "a", "-demo", "demo_univ", "demo univ").forEach { bad ->
            val error = assertFailsWith<JwManifestException> {
                JwManifestCodec.validate(manifest(key = bad), 11)
            }
            assertTrue(error.message!!.contains("key"), error.message)
        }
    }

    @Test
    fun `规范版本过新提示升级应用`() {
        val error = assertFailsWith<JwManifestException> {
            JwManifestCodec.validate(manifest(specVersion = 2), 11)
        }
        assertTrue(error.message!!.contains("升级"), error.message)
    }

    @Test
    fun `最低版本高于当前应用时明确报错`() {
        val error = assertFailsWith<JwManifestException> {
            JwManifestCodec.validate(manifest(minAppVersionCode = 99), appVersionCode = 11)
        }
        assertTrue(error.message!!.contains("99"), error.message)
    }

    @Test
    fun `登录地址协议与主机名校验`() {
        assertFailsWith<JwManifestException> { JwManifestCodec.validate(manifest(loginUrl = "ftp://x.cn/"), 11) }
        assertFailsWith<JwManifestException> { JwManifestCodec.validate(manifest(loginUrl = "https:///a"), 11) }
        // http 允许（国内教务现实），不报错
        JwManifestCodec.validate(manifest(loginUrl = "http://jw.example.edu.cn/"), 11)
    }

    @Test
    fun `脚本路径必须安全且是 js`() {
        assertFailsWith<JwManifestException> {
            JwManifestCodec.validate(manifest(extract = "../evil.js"), 11)
        }
        assertFailsWith<JwManifestException> {
            JwManifestCodec.validate(manifest(extract = "extract.txt"), 11)
        }
        assertFailsWith<JwManifestException> {
            JwManifestCodec.validate(manifest(parse = "a/b/c.js"), 11)
        }
    }

    @Test
    fun `allowHosts 必须是纯主机名`() {
        listOf("https://jw.example.edu.cn", "jw.example.edu.cn/path", "local", "").forEach { bad ->
            assertFailsWith<JwManifestException> {
                JwManifestCodec.validate(manifest(allowHosts = listOf(bad)), 11)
            }
        }
    }

    @Test
    fun `json 编码解码往返一致`() {
        val original = manifest(allowHosts = listOf("jw.example.edu.cn"))
        val decoded = JwManifestCodec.decode(JwManifestCodec.encode(original))
        assertEquals(original, decoded)
    }

    @Test
    fun `坏 json 给出可读错误`() {
        val error = assertFailsWith<JwManifestException> { JwManifestCodec.decode("{not json") }
        assertTrue(error.message!!.contains("manifest.json"), error.message)
    }
}
