package com.nullclass.sync

import com.nullclass.importer.BlockDto
import com.nullclass.importer.CourseDto
import com.nullclass.importer.ManifestDto
import com.nullclass.importer.NullClassCodec
import com.nullclass.importer.PeriodTimeDto
import com.nullclass.importer.ScheduleDocument
import com.nullclass.importer.TermDto

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebDavClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: WebDavClient

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = WebDavClient(
            WebDavConfig(
                url = server.url("/").toString(),
                username = "user",
                password = "pass",
            ),
        )
    }

    @AfterTest
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `远端为空时下载返回 null`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404)) // manifest

        assertNull(client.download())
    }

    @Test
    fun `上传后可下载回同内容`() = runTest {
        val snapshot = ScheduleDocument(
            deviceId = "dev",
            generatedAt = 100,
            terms = listOf(
                TermDto("t1", "2026-1", 20000, 20, true, 1, 2),
            ),
            courses = listOf(
                CourseDto("c1", "t1", "高数", colorIndex = 3, createdAt = 1, updatedAt = 2),
            ),
            blocks = listOf(
                BlockDto("b1", "c1", "t1", 1, 20, "ALL", 2, 1, 2, "A101", 1, 2),
            ),
            periodTimes = listOf(
                PeriodTimeDto("t1", 1, 480, 525, 0, 2),
            ),
        )

        server.enqueue(MockResponse().setResponseCode(201)) // snapshot PUT
        server.enqueue(MockResponse().setResponseCode(201)) // manifest PUT

        client.upload(snapshot, previousRev = 5)

        // 校验请求带了 Basic Auth 且路径正确
        val snapshotRequest = server.takeRequest()
        assertEquals("/nullclass/snapshot.json", snapshotRequest.path)
        assertTrue(snapshotRequest.getHeader("Authorization")!!.startsWith("Basic "))
        // B7：snapshot 走 NullClassCodec（encodeDefaults），默认值字段必须显式写出
        assertTrue(snapshotRequest.body.readUtf8().contains("\"formatVersion\":2"))
        val manifestRequest = server.takeRequest()
        assertTrue(manifestRequest.body.readUtf8().contains("\"formatVersion\":2"))

        // 下载回来
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"formatVersion":2,"deviceId":"dev","generatedAt":9,"rev":6}""",
            ),
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                kotlinx.serialization.json.Json.encodeToString(snapshot),
            ),
        )

        val downloaded = client.download()!!

        assertEquals(6, downloaded.manifest.rev)
        assertEquals(snapshot, downloaded.snapshot)
    }

    @Test
    fun `远端快照版本过新 - 拒绝解码`() = runTest {
        // B7：snapshot 解码必须过 NullClassCodec 的未来版本守卫，而不是静默丢未知字段
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"formatVersion":2,"deviceId":"dev","generatedAt":9,"rev":1}""",
            ),
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"formatVersion":3,"deviceId":"dev","generatedAt":1,"terms":[],"courses":[],"blocks":[],"periodTimes":[]}""",
            ),
        )

        val error = assertFailsWith<NullClassCodec.FutureVersionException> { client.download() }
        assertEquals(3, error.fileVersion)
    }

    @Test
    fun `测试连接全链路成功`() = runTest {
        server.enqueue(MockResponse().setResponseCode(201)) // MKCOL
        server.enqueue(MockResponse().setResponseCode(201)) // PUT probe
        server.enqueue(MockResponse().setResponseCode(200).setBody("nullclass-probe-1")) // GET probe
        server.enqueue(MockResponse().setResponseCode(204)) // DELETE probe

        assertEquals(WebDavResult.Ok, client.testConnection())

        val mkcol = server.takeRequest()
        assertEquals("MKCOL", mkcol.method)
    }

    @Test
    fun `写入失败时测试连接报错`() = runTest {
        server.enqueue(MockResponse().setResponseCode(201)) // MKCOL
        server.enqueue(MockResponse().setResponseCode(403)) // PUT probe 拒绝

        val result = client.testConnection()

        assertTrue(result is WebDavResult.Error)
        assertTrue(result.message.contains("403"))
    }

    @Test
    fun `http 公网地址被拒绝 https 不受限`() {
        val publicHttp = WebDavConfig("http://dav.example.com/dav/", "u", "p")
        assertEquals("公网地址必须使用 https，否则密码会明文传输", publicHttp.validate())

        val publicHttps = WebDavConfig("https://dav.example.com/dav/", "u", "p")
        assertNull(publicHttps.validate())

        val lanHttp = WebDavConfig("http://192.168.1.10:5005/", "u", "p")
        assertNull(lanHttp.validate())
    }

    @Test
    fun `公网域名伪装私网 IP 前缀无法绕过`() {
        // 反向域名攻击：主机名以 "10."/"192.168." 开头但实际是公网域名
        val evil1 = WebDavConfig("http://10.0.0.1.evil.com/", "u", "p")
        assertTrue(evil1.validate() != null)

        val evil2 = WebDavConfig("http://192.168.attacker.example/", "u", "p")
        assertTrue(evil2.validate() != null)

        val evil3 = WebDavConfig("http://172.16.foo.bar/", "u", "p")
        assertTrue(evil3.validate() != null)

        // 真私网 IP 与 localhost 不受影响
        assertNull(WebDavConfig("http://10.1.2.3/", "u", "p").validate())
        assertNull(WebDavConfig("http://172.16.0.1/", "u", "p").validate())
        assertNull(WebDavConfig("http://localhost:5005/", "u", "p").validate())

        // 非法 IP 字面量不算私网
        assertTrue(WebDavConfig("http://999.168.1.1/", "u", "p").validate() != null)
    }
}
