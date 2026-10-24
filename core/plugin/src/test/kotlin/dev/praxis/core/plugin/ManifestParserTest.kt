package dev.praxis.core.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HJSON / 清单解析与映射的护栏。
 *
 * ## 每一条断言对应一类「真实会让用户装不上包」的情况
 *
 * 这些用例不是为覆盖率写的，而是把踩过的坑固定住：
 * URL 被当注释截断、版本号被当数字格式化、author 写成字符串、
 * 单个字段写错导致整包失败。
 */
class ManifestParserTest {

    // ── HJSON 语法 ───────────────────────────────────────────

    @Test
    fun `标准 JSON 也能解析`() {
        val text = """{"toolpkg_id":"com.a.b","main":"index.js","version":"1.0.0"}"""
        val root = ManifestParser.parse(text).getOrThrow()
        val fields = root.asMapping()
        assertEquals("com.a.b", fields["toolpkg_id"]?.asText())
        assertEquals("index.js", fields["main"]?.asText())
    }

    @Test
    fun `无引号键与无引号值`() {
        val text = """
            toolpkg_id: com.example.demo
            main: packages/main.js
            version: 1.2.3
        """
        val fields = ManifestParser.parse(text).getOrThrow().asMapping()
        assertEquals("com.example.demo", fields["toolpkg_id"]?.asText())
        assertEquals("packages/main.js", fields["main"]?.asText())
        // 版本号必须原样保留，不能变成 1.2 或 1。
        assertEquals("1.2.3", fields["version"]?.asText())
    }

    @Test
    fun `三种注释都能跳过`() {
        val text = """
            # 井号注释
            toolpkg_id: a.b   # 行尾注释
            // 双斜杠注释
            main: index.js
            /* 块注释
               跨行 */
            version: 1.0.0
        """
        val fields = ManifestParser.parse(text).getOrThrow().asMapping()
        assertEquals("a.b", fields["toolpkg_id"]?.asText())
        assertEquals("index.js", fields["main"]?.asText())
        assertEquals("1.0.0", fields["version"]?.asText())
    }

    @Test
    fun `URL 里的双斜杠不能当注释（真实踩过的坑）`() {
        // 旧实现在见到 `//` 就断，于是 `https://x` 被读成 `https:`。
        val text = """
            toolpkg_id: a.b
            main: index.js
            endpoint: https://api.example.com/mcp
            fallback: http://backup.example.com/mcp
        """
        val fields = ManifestParser.parse(text).getOrThrow().asMapping()
        assertEquals("https://api.example.com/mcp", fields["endpoint"]?.asText())
        assertEquals("http://backup.example.com/mcp", fields["fallback"]?.asText())
    }

    @Test
    fun `注释前必须有空白才算注释`() {
        // `a//b` 是值的一部分；`a //b` 才是带注释。
        val text = """
            toolpkg_id: a.b
            main: index.js
            weird: foo//bar
            spaced: foo //bar
        """
        val fields = ManifestParser.parse(text).getOrThrow().asMapping()
        assertEquals("foo//bar", fields["weird"]?.asText())
        assertEquals("foo", fields["spaced"]?.asText())
    }

    @Test
    fun `逗号可省略`() {
        val text = """
            {
              toolpkg_id: a.b
              main: index.js
              version: 1.0.0
            }
        """
        val fields = ManifestParser.parse(text).getOrThrow().asMapping()
        assertEquals("1.0.0", fields["version"]?.asText())
    }

    @Test
    fun `多语言对象`() {
        val text = """
            toolpkg_id: a.b
            main: index.js
            display_name: {
              zh: 我的工具
              en: My Tool
            }
        """
        val fields = ManifestParser.parse(text).getOrThrow().asMapping()
        val name = LocalizedTextCodec.parse(fields["display_name"], "display_name")
        assertEquals("我的工具", name.resolveZh())
        assertEquals("My Tool", name.resolve("en"))
    }

    @Test
    fun `布尔与 null`() {
        val text = """
            toolpkg_id: a.b
            main: index.js
            enabled_by_default: false
            optional: null
            yes_flag: yes
        """
        val fields = ManifestParser.parse(text).getOrThrow().asMapping()
        assertEquals(false, fields["enabled_by_default"]?.asBooleanOrNull())
        assertTrue(fields["optional"] is ManifestValue.Null)
        assertEquals(true, fields["yes_flag"]?.asBooleanOrNull())
    }

    @Test
    fun `括号不配对会报错并给行号`() {
        val text = """
            toolpkg_id: a.b
            main: index.js
            subpackages: [
              { id: x
        """
        val error = ManifestParser.parse(text).exceptionOrNull()
        assertNotNull(error)
        assertTrue(
            "错误信息里要能看到行号，用户才知道去哪修",
            error!!.message.orEmpty().contains("行"),
        )
    }

    @Test
    fun `空文本不崩`() {
        val root = ManifestParser.parse("").getOrThrow()
        assertTrue(root.asMapping().isEmpty())
    }

    // ── 映射 ─────────────────────────────────────────────────

    private fun map(text: String, entryExists: (String) -> Boolean = { true }) =
        ManifestMapper.map(ManifestParser.parse(text).getOrThrow(), entryExists)

    @Test
    fun `最小可用清单`() {
        val result = map(
            """
            toolpkg_id: com.example.min
            main: index.js
            version: 1.0.0
            display_name: 最简包
            """,
        )
        assertTrue("不该有致命问题：${result.problems}", result.isUsable)
        assertEquals("com.example.min", result.manifest.id)
        assertEquals("index.js", result.manifest.main)
        assertEquals("最简包", result.manifest.title)
        assertTrue("默认应启用", result.manifest.enabledByDefault)
    }

    @Test
    fun `缺 id 是致命错误`() {
        val result = map("main: index.js")
        assertTrue(!result.isUsable)
        assertEquals("toolpkg_id", result.fatal?.field)
    }

    @Test
    fun `缺 main 是致命错误`() {
        val result = map("toolpkg_id: a.b")
        assertTrue(!result.isUsable)
        assertEquals("main", result.fatal?.field)
    }

    @Test
    fun `main 指向不存在的文件是致命错误`() {
        val result = map(
            """
            toolpkg_id: a.b
            main: missing.js
            """,
        ) { it != "missing.js" }
        assertTrue(!result.isUsable)
        assertEquals("main", result.fatal?.field)
    }

    @Test
    fun `api_version 不在支持范围是致命错误`() {
        val result = map(
            """
            toolpkg_id: a.b
            main: index.js
            api_version: 9.9.9
            """,
        )
        assertTrue(!result.isUsable)
        assertEquals("api_version", result.fatal?.field)
        // 错误信息必须列出支持哪些版本，否则用户不知道该改什么。
        assertTrue(result.fatal!!.message.contains("1.0.0"))
    }

    @Test
    fun `api_version 缺省时按支持的最高版本处理`() {
        val result = map("toolpkg_id: a.b\nmain: index.js")
        assertTrue(result.isUsable)
        assertEquals(PluginManifest.SUPPORTED_API_VERSION, result.manifest.apiVersion)
    }

    @Test
    fun `author 单值写成字符串也能读`() {
        val result = map(
            """
            toolpkg_id: a.b
            main: index.js
            author: sxd91
            """,
        )
        assertEquals(listOf("sxd91"), result.manifest.authors)
    }

    @Test
    fun `author 列表`() {
        val result = map(
            """
            toolpkg_id: a.b
            main: index.js
            author: [alice, bob]
            """,
        )
        assertEquals(listOf("alice", "bob"), result.manifest.authors)
    }

    @Test
    fun `子包入口缺失只警告不致命`() {
        // 整个容器仍可装载，只是这个子包不可用。
        val result = map(
            """
            toolpkg_id: a.b
            main: index.js
            subpackages: [
              { id: good, entry: good.js }
              { id: bad, entry: missing.js }
            ]
            """,
        ) { it != "missing.js" }
        assertTrue("不该致命：${result.problems}", result.isUsable)
        assertEquals(1, result.manifest.subpackages.size)
        assertEquals("good", result.manifest.subpackages.first().id)
        assertTrue(result.warnings.any { it.field.contains("subpackages[1]") })
    }

    @Test
    fun `单子包写成单个对象也接受`() {
        // 手写清单常见：subpackages 直接是一个对象而不是数组。
        val result = map(
            """
            toolpkg_id: a.b
            main: index.js
            subpackages: { id: only, entry: only.js }
            """,
        )
        assertEquals(1, result.manifest.subpackages.size)
        assertEquals("only", result.manifest.subpackages.first().id)
        assertEquals(false, result.manifest.hasMultipleSubpackages)
    }

    @Test
    fun `requires 带版本约束`() {
        val result = map(
            """
            toolpkg_id: a.b
            main: index.js
            requires: [
              { id: base.pkg, min_version: 1.2.0, max_version: 2.0.0 }
            ]
            """,
        )
        val requirement = result.manifest.requires.single()
        assertEquals("base.pkg", requirement.id)
        assertEquals("1.2.0 ~ 2.0.0", requirement.constraintText)
        assertNull(requirement.validate("1.5.0"))
        assertNotNull("低于下限要拒绝", requirement.validate("1.1.0"))
        assertNotNull("高于上限要拒绝", requirement.validate("2.1.0"))
    }

    @Test
    fun `enabled_by_default 为 false 时尊重清单`() {
        val result = map(
            """
            toolpkg_id: a.b
            main: index.js
            enabled_by_default: false
            """,
        )
        assertEquals(false, result.manifest.enabledByDefault)
    }
}