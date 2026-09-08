package com.nullclass.importer.jw

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JwLibraryIndexTest {

    @Test
    fun `github 仓库地址归一化到 raw index`() {
        assertEquals(
            "https://raw.githubusercontent.com/0x7E7-2023/NullClass/HEAD/index.json",
            JwLibraryUrl.normalize("https://github.com/0x7E7-2023/NullClass"),
        )
        assertEquals(
            "https://raw.githubusercontent.com/0x7E7-2023/NullClass/HEAD/index.json",
            JwLibraryUrl.normalize("https://github.com/0x7E7-2023/NullClass.git"),
        )
    }

    @Test
    fun `github 分支地址保留分支`() {
        assertEquals(
            "https://raw.githubusercontent.com/o/r/dev/index.json",
            JwLibraryUrl.normalize("https://github.com/o/r/tree/dev"),
        )
    }

    @Test
    fun `直接给 json 地址时原样使用`() {
        assertEquals(
            "https://example.com/lib/index.json",
            JwLibraryUrl.normalize("https://example.com/lib/index.json"),
        )
    }

    @Test
    fun `目录地址自动补 index json`() {
        assertEquals(
            "https://example.com/lib/index.json",
            JwLibraryUrl.normalize("https://example.com/lib"),
        )
    }

    @Test
    fun `发布版拒绝 http 调试版放行`() {
        val error = assertFailsWith<JwPackageException> { JwLibraryUrl.normalize("http://example.com/lib") }
        assertTrue(error.message!!.contains("https"), error.message)
        assertEquals(
            "http://example.com/lib/index.json",
            JwLibraryUrl.normalize("http://example.com/lib", allowInsecure = true),
        )
    }

    @Test
    fun `相对路径解析到索引所在目录`() {
        assertEquals(
            "https://raw.githubusercontent.com/o/r/HEAD/demo-univ/manifest.json",
            JwLibraryUrl.resolve(
                "https://raw.githubusercontent.com/o/r/HEAD/index.json",
                "demo-univ/manifest.json",
            ),
        )
    }

    @Test
    fun `索引解析与校验`() {
        val index = JwLibraryIndexCodec.decode(
            """{"specVersion":1,"name":"某人的库","adapters":[{"key":"a-univ","name":"A 大学","path":"a-univ"}]}""",
        )
        assertEquals("某人的库", index.name)
        assertEquals("a-univ", index.adapters.single().path)

        assertFailsWith<JwPackageException> {
            JwLibraryIndexCodec.decode("""{"specVersion":1,"adapters":[{"key":"a","name":"A","path":"../x"}]}""")
        }
        assertFailsWith<JwPackageException> {
            JwLibraryIndexCodec.decode("""{"specVersion":2,"adapters":[]}""")
        }
    }
}
