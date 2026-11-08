package com.nullclass.feature.settings.jw

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JwImageOcrTest {

    private lateinit var source: MockWebServer
    private lateinit var target: MockWebServer

    @BeforeTest
    fun setUp() {
        source = MockWebServer()
        target = MockWebServer()
        source.start()
        target.start()
    }

    @AfterTest
    fun tearDown() {
        source.shutdown()
        target.shutdown()
    }

    @Test
    fun `重定向到未声明主机时不发起后续请求`() {
        val initialUrl = "http://localhost:${source.port}/image"
        val targetUrl = "http://127.0.0.1:${target.port}/image"
        source.enqueue(MockResponse().setResponseCode(302).addHeader("Location", targetUrl))
        val cookieRequests = mutableListOf<String>()

        val bytes = JwImageOcr.download(initialUrl, listOf("localhost")) { url ->
            cookieRequests += url
            "session=secret"
        }

        assertNull(bytes)
        assertEquals(1, source.requestCount)
        assertEquals(0, target.requestCount)
        assertEquals(listOf(initialUrl), cookieRequests)
    }

    @Test
    fun `允许的跨主机重定向重新校验并改用目标主机 Cookie`() {
        val initialUrl = "http://localhost:${source.port}/image"
        val targetUrl = "http://127.0.0.1:${target.port}/image"
        source.enqueue(MockResponse().setResponseCode(302).addHeader("Location", targetUrl))
        target.enqueue(MockResponse().setBody("image-bytes"))

        val bytes = JwImageOcr.download(initialUrl, listOf("localhost", "127.0.0.1")) { url ->
            if (url.startsWith("http://localhost:")) "session=source" else "session=target"
        }

        assertContentEquals("image-bytes".encodeToByteArray(), bytes)
        assertEquals("session=source", source.takeRequest().getHeader("Cookie"))
        assertEquals("session=target", target.takeRequest().getHeader("Cookie"))
    }
}
