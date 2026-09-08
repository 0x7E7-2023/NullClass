package com.nullclass.importer.jw

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JwOriginRulesTest {

    private fun manifest(loginUrl: String, scheduleUrlHint: String? = null) = JwManifest(
        key = "demo-univ",
        name = "示例大学",
        version = "1.0.0",
        loginUrl = loginUrl,
        scheduleUrlHint = scheduleUrlHint,
    )

    @Test
    fun `非默认端口必须写进 origin 规则`() {
        // addWebMessageListener 的规则是 origin 语义：省略端口只等于默认端口，
        // 教务系统跑在 8080 时规则写成 http://host 就永远匹配不上，桥不注入。
        val rules = JwOriginRules.forAdapter(
            manifest("http://jw.example.edu.cn:8080/login"),
            allowedHosts = listOf("jw.example.edu.cn"),
        )
        assertTrue("http://jw.example.edu.cn:8080" in rules, rules.toString())
    }

    @Test
    fun `登录页与课表页不同源时两条规则都要有`() {
        val rules = JwOriginRules.forAdapter(
            manifest("https://cas.example.edu.cn/login", "https://jw.example.edu.cn:8443/kb"),
            allowedHosts = listOf("cas.example.edu.cn", "jw.example.edu.cn"),
        )
        assertTrue("https://cas.example.edu.cn" in rules, rules.toString())
        assertTrue("https://jw.example.edu.cn:8443" in rules, rules.toString())
    }

    @Test
    fun `allowHosts 只有主机名时 http 与 https 默认端口都发`() {
        val rules = JwOriginRules.forAdapter(
            manifest("https://jw.example.edu.cn/"),
            allowedHosts = listOf("jw.example.edu.cn", "CAS.Example.edu.cn"),
        )
        assertEquals(
            setOf(
                "https://jw.example.edu.cn",
                "http://jw.example.edu.cn",
                "https://cas.example.edu.cn",
                "http://cas.example.edu.cn",
            ),
            rules,
        )
    }

    @Test
    fun `非 http 协议与非法地址被忽略`() {
        val rules = JwOriginRules.forAdapter(
            manifest("file:///android_asset/index.html", "javascript:alert(1)"),
            allowedHosts = emptyList(),
        )
        assertEquals(emptySet(), rules)
    }
}
