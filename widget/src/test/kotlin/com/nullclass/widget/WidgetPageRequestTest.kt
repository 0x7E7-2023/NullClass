package com.nullclass.widget

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WidgetPageRequestTest {
    @Test
    fun `连续点击第五页向下只提交一次并留在第六页`() {
        var page = 4
        repeat(20) {
            val next = resolveWidgetPageRequest(page, "today", 4, 5, 6, "today", true)
            if (next != null) page = next
        }
        assertEquals(5, page)
        repeat(20) {
            assertNull(resolveWidgetPageRequest(page, "today", 5, 6, 6, "today", false))
            assertNull(resolveWidgetPageRequest(page, "today", 3, 4, 6, "today", true))
        }
        assertEquals(5, page)
    }

    @Test
    fun `末页事件即使错误标为可用也不能越界`() {
        assertNull(resolveWidgetPageRequest(5, "today", 5, 6, 6, "today", true))
        assertNull(resolveWidgetPageRequest(0, "today", 0, -1, 6, "today", true))
        assertNull(resolveWidgetPageRequest(0, "today", 0, 1, 0, "today", true))
    }

    @Test
    fun `末页仍可正常向上再向下`() {
        val previous = resolveWidgetPageRequest(5, "today", 5, 4, 6, "today", true)
        assertEquals(4, previous)
        assertEquals(5, resolveWidgetPageRequest(previous!!, "today", 4, 5, 6, "today", true))
        assertNull(resolveWidgetPageRequest(4, "today", 5, 4, 6, "today", true))
    }

    @Test
    fun `另一尺寸布局存下的更大页码按本布局末页续翻`() {
        // 横屏布局翻到第 4 页（共 4 页），竖屏布局只有 2 页、显示在末页
        assertEquals(0, resolveWidgetPageRequest(3, "today", 1, 0, 2, "today", true))
        assertNull(resolveWidgetPageRequest(3, "today", 0, 1, 2, "today", true))
        // 竖屏布局翻到第 2 页后，横屏布局从第 2 页继续
        assertEquals(2, resolveWidgetPageRequest(1, "today", 1, 2, 4, "today", true))
    }

    @Test
    fun `日期或容量变化后从显示的第一页开始翻页`() {
        assertEquals(1, resolveWidgetPageRequest(5, "yesterday", 0, 1, 3, "today", true))
        assertNull(resolveWidgetPageRequest(5, "yesterday", 1, 2, 3, "today", true))
        assertEquals(1, resolveWidgetPageRequest(0, null, 0, 1, 6, "today", true))
    }
}
