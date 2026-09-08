package com.nullclass.importer.jw

/**
 * 适配器总入口：内置（随包）+ 用户添加（zip / 仓库）。
 *
 * 规则：**内置 key 不可被用户适配器覆盖**——直接拒绝。
 * 伪造一个同名名校的适配器是最省事的社会工程入口，宁可让用户改 key 或提 PR。
 */
class JwAdapterRepository(
    val builtin: List<JwAdapter>,
    private val store: JwUserAdapterStore,
) {

    private val builtinKeys: Set<String> = builtin.map { it.key }.toSet()

    fun userLibrary(): JwUserLibrary = store.list()

    /** 内置 + 用户添加（key 不冲突，无需覆盖语义）。 */
    fun all(): List<JwAdapter> = builtin + store.list().adapters

    fun byKey(key: String): JwAdapter? =
        builtin.firstOrNull { it.key == key } ?: store.find(key)

    fun isBuiltinKey(key: String): Boolean = key in builtinKeys

    /**
     * 安装一个包（可能含多个适配器）。任一 key 与内置冲突则整体拒绝。
     */
    fun install(
        pkg: JwPackage,
        installedAt: Long,
        sourceUrl: String? = null,
        fileName: String? = null,
    ): List<JwAdapter> {
        val conflicts = pkg.adapters.map { it.key }.filter { it in builtinKeys }
        if (conflicts.isNotEmpty()) {
            throw JwPackageException(
                "适配器 key「${conflicts.joinToString("、")}」已被内置适配器占用；" +
                    "请改用别的 key，或向空课提交改进这个内置适配器",
            )
        }
        return store.install(pkg.adapters, installedAt, sourceUrl, fileName)
    }

    fun delete(key: String): Boolean {
        if (key in builtinKeys) return false
        return store.delete(key)
    }
}
