package com.nullclass.importer.jw

/**
 * `allowHosts` 的匹配与校验。
 *
 * 精确主机名之外，允许 `*.ustc.edu.cn` 这种**后缀通配**：匹配该域本身及其所有子域
 * （`jw.ustc.edu.cn`、`a.b.ustc.edu.cn`）。通配后缀至少三段，挡住 `*.edu.cn` / `*.com`。
 *
 * JS 沙箱里有一份同语义的 ES5 实现（[JwScriptContract] 的 preamble）；改规则两边一起改。
 */
object JwHostAllowlist {

    private val HOST = Regex(
        "^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$",
    )

    fun isValidPattern(pattern: String): Boolean = errorOf(pattern) == null

    /** 不合法时返回面向用户的错误；合法返回 null。 */
    fun errorOf(pattern: String): String? {
        val lower = pattern.lowercase()
        if (lower.startsWith("*.")) {
            val suffix = lower.substring(2)
            if (!HOST.matches(suffix)) {
                return "allowHosts 中的「$pattern」不是合法的通配域名（写成 *.学校.edu.cn，只写主机名）"
            }
            if (suffix.count { it == '.' } < 2) {
                return "allowHosts 中的「$pattern」通配范围过大（至少写成 *.学校.edu.cn 这种三段域名，不允许 *.edu.cn）"
            }
            return null
        }
        if (!HOST.matches(lower)) {
            return "allowHosts 中的「$pattern」不是合法域名（只写主机名，不带协议与路径；通配写成 *.学校.edu.cn）"
        }
        return null
    }

    fun matches(host: String?, patterns: Collection<String>): Boolean {
        val h = host?.lowercase()?.trim('.') ?: return false
        if (h.isEmpty()) return false
        for (raw in patterns) {
            val pattern = raw.lowercase().trim()
            if (pattern.startsWith("*.")) {
                val suffix = pattern.substring(2).trim('.')
                if (suffix.isEmpty()) continue
                if (h == suffix || h.endsWith(".$suffix")) return true
            } else if (pattern == h) {
                return true
            }
        }
        return false
    }
}
