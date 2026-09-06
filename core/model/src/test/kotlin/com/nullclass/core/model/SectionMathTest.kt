package com.nullclass.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SectionMathTest {

    @Test
    fun `节次配对成大节`() {
        assertEquals(1, SectionMath.sectionIndex(1))
        assertEquals(1, SectionMath.sectionIndex(2))
        assertEquals(2, SectionMath.sectionIndex(3))
        assertEquals(2, SectionMath.sectionIndex(4))
        assertEquals(6, SectionMath.sectionIndex(12))
    }

    @Test
    fun `大节展开为小节范围`() {
        assertEquals(1..2, SectionMath.sectionPeriodRange(1))
        assertEquals(3..4, SectionMath.sectionPeriodRange(2))
        assertEquals(11..12, SectionMath.sectionPeriodRange(6))
    }

    @Test
    fun `换算往返一致`() {
        for (period in 1..12) {
            val range = SectionMath.sectionPeriodRange(SectionMath.sectionIndex(period))
            assert(period in range) { "period $period not in $range" }
        }
    }

    @Test
    fun `总大节数 奇偶节次都正确`() {
        assertEquals(6, SectionMath.sectionCount(12))
        assertEquals(6, SectionMath.sectionCount(11))
        assertEquals(1, SectionMath.sectionCount(1))
        assertEquals(1, SectionMath.sectionCount(2))
    }

    @Test
    fun `非法输入抛异常`() {
        assertFailsWith<IllegalArgumentException> { SectionMath.sectionIndex(0) }
        assertFailsWith<IllegalArgumentException> { SectionMath.sectionPeriodRange(0) }
        assertFailsWith<IllegalArgumentException> { SectionMath.sectionCount(0) }
    }
}
