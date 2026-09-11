package com.nullclass.importer.jw

import kotlinx.serialization.json.Json
import org.mozilla.javascript.Context
import org.mozilla.javascript.ScriptableObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 适配器库的**回归门**：CI 里对 `jw-adapters/` 下每个适配器实跑它的 `parse.js`，
 * 与 fixture 里声明的期望载荷逐字段比对。贡献者不用装模拟器就能验证自己的适配器。
 */
class JwLibraryHarnessTest {

    private val libraryDir: File = File(
        System.getProperty("jwLibraryDir") ?: error("缺少 jwLibraryDir 系统属性（见 importer/build.gradle.kts）"),
    )

    private fun adapterDirs(): List<File> =
        libraryDir.listFiles { file -> file.isDirectory && !file.name.startsWith(".") }
            ?.sortedBy { it.name }
            .orEmpty()

    @Test
    fun `库目录存在且索引与目录一致`() {
        assertTrue(libraryDir.isDirectory, "适配器库目录不存在：$libraryDir")
        val index = JwLibraryIndexCodec.decode(
            File(libraryDir, JwPackageReader.INDEX).readText(Charsets.UTF_8),
        )
        assertEquals(
            adapterDirs().map { it.name }.toSet(),
            index.adapters.map { it.path }.toSet(),
            "index.json 与 jw-adapters/ 下的目录不一致（新增适配器记得加索引条目）",
        )
    }

    @Test
    fun `每个适配器的 manifest 合法且 key 唯一`() {
        val keys = adapterDirs().map { dir ->
            val pkg = JwPackageReader.readDirectory(dir, JwAdapterSource.BUILTIN, appVersionCode = Int.MAX_VALUE)
            val adapter = pkg.adapters.single()
            assertEquals(dir.name, adapter.key, "目录名应与 manifest 的 key 一致")
            adapter.key
        }
        assertEquals(keys.size, keys.toSet().size, "适配器 key 重复：$keys")
    }

    @Test
    fun `每个适配器的 parse 脚本能跑通 fixture`() {
        var cases = 0
        adapterDirs().forEach { dir ->
            val adapter = JwPackageReader.readDirectory(dir, JwAdapterSource.BUILTIN, appVersionCode = Int.MAX_VALUE)
                .adapters.single()
            assertTrue(adapter.manifest.fixtures.isNotEmpty(), "适配器 ${adapter.key} 没有 fixture 用例")
            adapter.manifest.fixtures.forEach { fixture ->
                val extracted = JwPackageReader.readFixture(dir, fixture.extracted).toString(Charsets.UTF_8)
                val expected = Json.parseToJsonElement(
                    JwPackageReader.readFixture(dir, fixture.expected).toString(Charsets.UTF_8),
                )
                val actualRaw = adapter.parseScript?.let { evalParseScript(it, extracted) } ?: extracted
                val actual = Json.parseToJsonElement(actualRaw)
                assertEquals(
                    expected,
                    actual,
                    "适配器 ${adapter.key} 的用例「${fixture.name}」输出与期望不符\n实际：$actualRaw",
                )
                // 期望载荷本身必须能被应用校验并通过归一化。
                // boxes（页面文本块）与 image（交给 OCR）载荷里没有课表数据，归一化对它们不适用。
                val decoded = JwPayloadCodec.decode(actualRaw)
                if (decoded.kind == JwSchedulePayload.KIND_SCHEDULE) {
                    JwScheduleNormalizer.normalize(decoded, adapter.key, now = 0L)
                }
                cases++
            }
        }
        assertTrue(cases > 0, "没有跑任何 fixture 用例")
    }

    @Test
    fun `脚本保持 ES5 写法且不含网络外发`() {
        adapterDirs().forEach { dir ->
            listOf("extract.js", "parse.js").forEach { name ->
                val file = File(dir, name)
                if (!file.isFile) return@forEach
                val source = file.readText(Charsets.UTF_8)
                listOf("=>", "`").forEach { token ->
                    assertTrue(token !in source, "$name 含非 ES5 写法：$token")
                }
                listOf(Regex("\\blet\\s"), Regex("\\bconst\\s")).forEach { pattern ->
                    assertTrue(!pattern.containsMatchIn(source), "$name 含非 ES5 写法：${pattern.pattern}")
                }
            }
        }
    }

    @Test
    fun `内置库能从资源加载且与磁盘一致`() {
        val builtin = JwBuiltinLibrary.load(appVersionCode = Int.MAX_VALUE)
        assertEquals(
            adapterDirs().map { it.name }.toSet(),
            builtin.map { it.key }.toSet(),
            "打包进资源的内置适配器与磁盘目录不一致",
        )
        builtin.forEach { adapter ->
            assertTrue(adapter.extractScript.isNotEmpty(), adapter.key)
            adapter.manifest.fixtures.forEach { fixture ->
                assertTrue(fixture.extracted in adapter.files, "${adapter.key} 缺 ${fixture.extracted}")
                assertTrue(fixture.expected in adapter.files, "${adapter.key} 缺 ${fixture.expected}")
            }
        }
    }

    /** 在 JVM 里跑适配器的 parse.js（Rhino，解释模式）。 */
    private fun evalParseScript(script: String, input: String): String {
        val context = Context.enter()
        try {
            context.optimizationLevel = -1
            context.languageVersion = Context.VERSION_ES6
            val scope = context.initStandardObjects()
            ScriptableObject.putProperty(scope, JwScriptContract.GLOBAL_INPUT, input)
            val result = context.evaluateString(scope, script, "parse.js", 1, null)
            return Context.toString(result)
        } finally {
            Context.exit()
        }
    }
}
