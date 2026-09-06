package com.nullclass.importer.jw

import com.nullclass.importer.jw.adapters.ExampleUniv

/**
 * 内建教务适配器注册表。新学校 = `adapters/` 加一个文件 + 在这里注册。
 */
object JwAdapterRegistry {

    /** 「提交我的学校适配」issue 入口（README 与界面共用）。 */
    const val ADAPTER_REQUEST_URL =
        "https://github.com/0x7E7-2023/NullClass/issues/new?template=jw-adapter-request.md"

    val adapters: List<JwAdapter> = listOf(
        ExampleUniv,
    )

    fun byKey(key: String): JwAdapter? = adapters.firstOrNull { it.schoolKey == key }
}
