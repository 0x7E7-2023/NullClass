package com.nullclass.importer.jw

/**
 * 适配器包内相对路径的安全规则（防 zip-slip / 目录穿越）。
 *
 * 分两层：**通用安全**（不许绝对路径、`..`、反斜杠、盘符、空段）+ **深度上限**
 * （包内最多 2 段；库 zip 里因为多一层学校目录，放宽到 6 段）。
 */
object JwPaths {

    /** 包内文件最大目录深度（相对适配器根）：`a.js` 与 `dir/a.js` 合法，`a/b/c.js` 不合法。 */
    const val MAX_PACKAGE_SEGMENTS = 2

    /** 库 zip 条目最大段数（`school/fixtures/a.json` = 3 段）。 */
    const val MAX_ZIP_SEGMENTS = 6

    const val MAX_PATH_LENGTH = 300

    /** 通用安全：相对、无穿越、无盘符、无空段。不限深度。 */
    fun isSafeRelative(path: String): Boolean {
        if (path.isEmpty() || path.length > MAX_PATH_LENGTH) return false
        if (path.startsWith("/") || path.startsWith("\\")) return false
        if (path.contains('\\') || path.contains(':')) return false
        val segments = path.split('/')
        return segments.all { it.isNotEmpty() && it != "." && it != ".." }
    }

    /** 包内路径（适配器根之下）：安全 + 深度 ≤ [MAX_PACKAGE_SEGMENTS]。 */
    fun isSafePackagePath(path: String): Boolean =
        isSafeRelative(path) && path.split('/').size <= MAX_PACKAGE_SEGMENTS

    /**
     * zip 条目名 → 安全相对路径；不合法返回 null。
     * 目录条目（以 `/` 结尾）返回 null（调用方跳过）。
     */
    fun normalizeZipEntry(name: String): String? {
        val cleaned = name.replace('\\', '/').removePrefix("./")
        if (cleaned.isEmpty() || cleaned.endsWith("/")) return null
        if (!isSafeRelative(cleaned)) return null
        if (cleaned.split('/').size > MAX_ZIP_SEGMENTS) return null
        return cleaned
    }
}
