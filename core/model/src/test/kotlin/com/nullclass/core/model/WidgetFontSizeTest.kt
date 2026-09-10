package com.nullclass.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WidgetFontSizeTest {

    @Test
    fun `缺省与未知值回落标准`() {
        assertEquals(WidgetFontSize.STANDARD, WidgetFontSize.fromName(null))
        assertEquals(WidgetFontSize.STANDARD, WidgetFontSize.fromName(""))
        assertEquals(WidgetFontSize.STANDARD, WidgetFontSize.fromName("HUGE"))
    }

    @Test
    fun `四档名称可还原`() {
        WidgetFontSize.entries.forEach {
            assertEquals(it, WidgetFontSize.fromName(it.name))
        }
    }

    @Test
    fun `档位按字号递增`() {
        val scales = WidgetFontSize.entries.map { it.scale }
        assertEquals(scales, scales.sorted())
        assertEquals(1.0f, WidgetFontSize.STANDARD.scale)
        assertEquals(1.5f, WidgetFontSize.XLARGE.scale)
        assertTrue(WidgetFontSize.SMALL.scale < WidgetFontSize.XLARGE.scale)
    }
}
