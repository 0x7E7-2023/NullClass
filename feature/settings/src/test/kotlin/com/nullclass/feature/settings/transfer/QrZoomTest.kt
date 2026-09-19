package com.nullclass.feature.settings.transfer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QrZoomTest {
    @Test
    fun `small code approaches auto limit gradually without overshooting camera limit`() {
        var zoom = 1f
        repeat(30) {
            val next = nextAutoZoom(zoom, 2.5f, 0.1f)
            assertTrue(next >= zoom && next <= zoom * 1.15f)
            assertTrue(next <= 2.5f)
            zoom = next
        }
        assertEquals(2.5f, zoom)
    }

    @Test
    fun `automatic zoom stops at three and never undoes manual zoom`() {
        assertEquals(3f, nextAutoZoom(3f, 10f, 0.1f))
        assertEquals(5f, nextAutoZoom(5f, 10f, 0.1f))
    }

    @Test
    fun `large or unreliable detection leaves zoom unchanged`() {
        for (fill in listOf(0f, 0.02f, 0.8f, Float.NaN)) {
            assertEquals(1f, nextAutoZoom(1f, 10f, fill))
        }
        assertEquals(1f, nextAutoZoom(1f, 1f, 0.1f))
    }
}
