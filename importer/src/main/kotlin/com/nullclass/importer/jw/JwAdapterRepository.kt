package com.nullclass.importer.jw

/**
 * 适配器总入口：内置（随包）+ 用户添加（zip / 仓库）。
 *
 * 规则：**内置 key 不可被用户适配器覆盖**——直接拒绝。
 * 伪造一个同名名校的适配器是最省事的社会工程入口，宁可让用户改 key 或提 PR。
 */
class JwAdapterRepository(
    builtin: JwLibraryBundle,
    private val store: JwUserAdapterStore,
) {

    /** 官方库（随包内置，或热更新下载的更新版本）。整体替换，读方拿到的永远是一致快照。 */
    @Volatile
    private var library: JwLibraryBundle = builtin
    private val builtinKeys: Set<String> get() = library.adapters.mapTo(HashSet()) { it.key }

    val builtin: List<JwAdapter> get() = library.adapters

    /** 当前官方库版本（旧 APK 内置的库可能没有版本号）。 */
    val builtinVersion: String? get() = library.version

    /**
     * 热更新：换上新的官方库（调用方保证已验签）。读盘，放 IO 线程。
     * @return 被新官方库遮住的用户适配器 key（同名时官方优先，需要告知用户）
     */
    fun replaceBuiltin(bundle: JwLibraryBundle): List<String> {
        val shadowed = store.list().adapters.map { it.key }.filter { key -> bundle.adapters.any { it.key == key } }
        library = bundle
        return shadowed
    }

    /** 用户库；官方库热更新后新占用的 key 会遮住同名用户适配器（与「内置不可覆盖」一致）。 */
    fun userLibrary(): JwUserLibrary = store.list().let { lib ->
        val keys = builtinKeys
        lib.copy(adapters = lib.adapters.filter { it.key !in keys })
    }

    /** 内置 + 用户添加。 */
    fun all(): List<JwAdapter> = builtin + userLibrary().adapters

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
