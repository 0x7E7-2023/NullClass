package com.nullclass.importer.jw

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JwHostAllowlistTest {

    @Test
    fun `精确主机名大小写不敏感`() {
        val patterns = listOf("jw.ustc.edu.cn")
        assertTrue(JwHostAllowlist.matches("jw.ustc.edu.cn", patterns))
        assertTrue(JwHostAllowlist.matches("JW.USTC.EDU.CN", patterns))
        assertFalse(JwHostAllowlist.matches("id.ustc.edu.cn", patterns))
        assertFalse(JwHostAllowlist.matches("jw.ustc.edu.cn.evil.com", patterns))
    }

    @Test
    fun `后缀通配匹配自身与任意深度子域`() {
        val patterns = listOf("*.ustc.edu.cn")
        assertTrue(JwHostAllowlist.matches("ustc.edu.cn", patterns))
        assertTrue(JwHostAllowlist.matches("jw.ustc.edu.cn", patterns))
        assertTrue(JwHostAllowlist.matches("analytics.ustc.edu.cn", patterns))
        assertTrue(JwHostAllowlist.matches("a.b.ustc.edu.cn", patterns))
        assertFalse(JwHostAllowlist.matches("ustc.edu.com", patterns))
        assertFalse(JwHostAllowlist.matches("notustc.edu.cn", patterns))
        assertFalse(JwHostAllowlist.matches("ustc.edu.cn.evil.com", patterns))
        assertFalse(JwHostAllowlist.matches("edu.cn", patterns))
    }

    @Test
    fun `精确与通配可以混用`() {
        val patterns = listOf("passport.ustc.edu.cn", "*.example.edu.cn")
        assertTrue(JwHostAllowlist.matches("passport.ustc.edu.cn", patterns))
        assertFalse(JwHostAllowlist.matches("jw.ustc.edu.cn", patterns))
        assertTrue(JwHostAllowlist.matches("cas.example.edu.cn", patterns))
    }

    @Test
    fun `空主机或不合法模式不匹配`() {
        assertFalse(JwHostAllowlist.matches(null, listOf("*.ustc.edu.cn")))
        assertFalse(JwHostAllowlist.matches("", listOf("*.ustc.edu.cn")))
        assertFalse(JwHostAllowlist.matches("jw.ustc.edu.cn", emptyList()))
    }

    @Test
    fun `通配至少三段 拒绝 edu_cn 这种公后缀`() {
        assertNull(JwHostAllowlist.errorOf("jw.ustc.edu.cn"))
        assertNull(JwHostAllowlist.errorOf("*.ustc.edu.cn"))
        assertNull(JwHostAllowlist.errorOf("*.JW.USTC.EDU.CN"))
        assertNotNull(JwHostAllowlist.errorOf("*.edu.cn"))
        assertNotNull(JwHostAllowlist.errorOf("*.com"))
        assertNotNull(JwHostAllowlist.errorOf("*"))
        assertNotNull(JwHostAllowlist.errorOf("*.*"))
        assertNotNull(JwHostAllowlist.errorOf("*ustc.edu.cn"))
        assertNotNull(JwHostAllowlist.errorOf("https://jw.ustc.edu.cn"))
        assertNotNull(JwHostAllowlist.errorOf("jw.ustc.edu.cn/path"))
        assertNotNull(JwHostAllowlist.errorOf("local"))
        assertNotNull(JwHostAllowlist.errorOf(""))
    }
}
