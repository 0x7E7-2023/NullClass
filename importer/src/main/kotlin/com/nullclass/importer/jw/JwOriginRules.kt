package com.nullclass.importer.jw

/**
 * `addWebMessageListener` 的 origin 白名单规则。
 *
 * 规则是 **origin 语义**（`scheme://host[:port]`），省略端口只表示默认端口 ——
 * 教务系统跑在 8080/8081 这类非默认端口上很常见，漏掉端口桥就不会注入
 * （页面里 `window.ncBridge` 是 undefined，适配器拿不到 `__ncOcr`）。
 *
 * 登录页与课表页可能不同源（CAS 单点登录尤其常见），所以两个 URL 都取；
 * `allowHosts` 按规范只写主机名、拿不到端口，http/https 的默认端口各发一条。
 */
object JwOriginRules {

    fun forAdapter(manifest: JwManifest, allowedHosts: List<String>): Set<String> {
        val rules = linkedSetOf<String>()
        addUrl(rules, manifest.loginUrl)
        addUrl(rules, manifest.scheduleUrlHint)
        allowedHosts.forEach { host ->
            val lower = host.lowercase()
            rules += "https://$lower"
            rules += "http://$lower"
        }
        return rules
    }

    private fun addUrl(rules: MutableSet<String>, url: String?) {
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return
        val scheme = uri.scheme?.lowercase() ?: return
        if (scheme != "http" && scheme != "https") return
        val host = uri.host?.lowercase() ?: return
        rules += if (uri.port > 0) "$scheme://$host:${uri.port}" else "$scheme://$host"
    }
}
