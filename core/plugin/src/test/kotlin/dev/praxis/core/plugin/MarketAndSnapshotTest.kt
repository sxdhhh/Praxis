package dev.praxis.core.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 市场 JSON 解析的容错护栏。
 *
 * ## 为什么这批断言比「解析成功」更重要
 *
 * 市场 JSON 是**别人的**服务端产物，字段类型随时可能变。每一条断言都在锁
 * 一种「服务端一改，用户就看到空白页」的场景。
 */
class MarketJsonParserTest {

    private fun parse(text: String): ManifestValue =
        ManifestParser.parse(text).getOrThrow()

    @Test
    fun `标准列表结构`() {
        val page = MarketJsonParser.parseList(
            parse(
                """
                {
                  "items": [
                    {
                      "id": "com.a.tool",
                      "title": "工具A",
                      "description": "说明",
                      "type": "toolpkg",
                      "latestVersion": { "version": "1.2.0" },
                      "authors": ["alice"],
                      "downloads": 42
                    }
                  ],
                  "total": 1
                }
                """,
            ),
            page = 1,
        )
        assertEquals(1, page.entries.size)
        val entry = page.entries.single()
        assertEquals("com.a.tool", entry.marketId)
        assertEquals("1.2.0", entry.version)
        assertEquals(listOf("alice"), entry.authors)
        assertEquals(42, entry.downloads)
        assertFalse("total=1 时第 1 页不该有更多", page.hasMore)
    }

    @Test
    fun `没有 id 的条目被跳过而不是整页失败`() {
        val page = MarketJsonParser.parseList(
            parse(
                """
                {"items": [
                  {"title": "没有 id"},
                  {"id": "ok.id", "title": "有 id"}
                ]}
                """,
            ),
            1,
        )
        assertEquals(1, page.entries.size)
        assertEquals("ok.id", page.entries.single().marketId)
    }

    @Test
    fun `authors 写成逗号分隔字符串也能读`() {
        val page = MarketJsonParser.parseList(
            parse("""{"items":[{"id":"x","authors":"alice, bob"}]}"""),
            1,
        )
        assertEquals(listOf("alice", "bob"), page.entries.single().authors)
    }

    @Test
    fun `authors 写成对象数组也能读`() {
        val page = MarketJsonParser.parseList(
            parse("""{"items":[{"id":"x","authors":[{"login":"alice"}]}]}"""),
            1,
        )
        assertEquals(listOf("alice"), page.entries.single().authors)
    }

    @Test
    fun `downloads 写成字符串也能读`() {
        val page = MarketJsonParser.parseList(
            parse("""{"items":[{"id":"x","downloads":"128"}]}"""),
            1,
        )
        assertEquals(128, page.entries.single().downloads)
    }

    @Test
    fun `downloads 是垃圾值时不崩，回落 0`() {
        val page = MarketJsonParser.parseList(
            parse("""{"items":[{"id":"x","downloads":"many"}]}"""),
            1,
        )
        assertEquals(0, page.entries.single().downloads)
    }

    @Test
    fun `根就是数组也接受`() {
        val page = MarketJsonParser.parseList(parse("""[{"id":"a"},{"id":"b"}]"""), 1)
        assertEquals(listOf("a", "b"), page.entries.map { it.marketId })
    }

    @Test
    fun `版本信息三级回落`() {
        // latestVersion 优先
        val withLatest = MarketJsonParser.parseList(
            parse("""{"items":[{"id":"x","latestVersion":{"version":"2.0.0"},"version":"1.0.0"}]}"""),
            1,
        )
        assertEquals("2.0.0", withLatest.entries.single().version)

        // 其次 versions[0]
        val withVersions = MarketJsonParser.parseList(
            parse("""{"items":[{"id":"x","versions":[{"version":"3.0.0"}],"version":"1.0.0"}]}"""),
            1,
        )
        assertEquals("3.0.0", withVersions.entries.single().version)

        // 最后才是顶层 version
        val plain = MarketJsonParser.parseList(
            parse("""{"items":[{"id":"x","version":"4.0.0"}]}"""),
            1,
        )
        assertEquals("4.0.0", plain.entries.single().version)
    }

    @Test
    fun `文件大小超过 Int 上限也能读`() {
        // 3GB：用 asIntOrNull 会得到 null，显示成 0 字节。
        val detail = MarketJsonParser.parseDetail(
            parse(
                """
                {"item": {"id":"x","title":"t","assets":[
                  {"id":"a1","fileName":"big.toolpkg","downloadUrl":"https://e/x","size":3000000000}
                ]}}
                """,
            ),
        )
        assertEquals(3_000_000_000L, detail!!.assets.single().sizeBytes)
    }

    @Test
    fun `详情三种外壳都能解析`() {
        val body = """{"id":"x","title":"t","detail":"说明"}"""
        listOf(
            """{"item":$body}""",
            """{"entry":$body}""",
            body,
        ).forEach { text ->
            val detail = MarketJsonParser.parseDetail(parse(text))
            assertEquals("外壳应被透明处理：$text", "x", detail?.summary?.marketId)
            assertEquals("说明", detail?.detail)
        }
    }

    @Test
    fun `主资产优先选 toolpkg`() {
        val detail = MarketJsonParser.parseDetail(
            parse(
                """
                {"item": {"id":"x","assets":[
                  {"id":"a1","fileName":"readme.md","downloadUrl":"u1"},
                  {"id":"a2","fileName":"PKG.ToolPkg","downloadUrl":"u2"}
                ]}}
                """,
            ),
        )
        assertEquals("a2", detail!!.primaryAsset?.assetId)
    }

    @Test
    fun `没有资产时主资产为 null`() {
        val detail = MarketJsonParser.parseDetail(parse("""{"item":{"id":"x"}}"""))
        assertNull(detail!!.primaryAsset)
    }

    @Test
    fun `type 映射到扩展类型`() {
        fun hintOf(type: String) = MarketEntrySummary(
            marketId = "x", title = "", description = "", type = type,
            version = "", authors = emptyList(), downloads = 0,
            logoUrl = null, updatedAt = null,
        ).kindHint

        assertEquals(ExtensionEntry.Kind.Skill, hintOf("Skill"))
        assertEquals(ExtensionEntry.Kind.McpServer, hintOf("mcp"))
        assertEquals(ExtensionEntry.Kind.McpServer, hintOf("mcp_server"))
        assertEquals(ExtensionEntry.Kind.SandboxPackage, hintOf("sandbox"))
        assertEquals(ExtensionEntry.Kind.ToolPackage, hintOf("toolpkg"))
        // 未知类型回落工具包（市场上绝大多数是工具包），不崩。
        assertEquals(ExtensionEntry.Kind.ToolPackage, hintOf("something-new"))
    }

    @Test
    fun `generatedAt 传给界面`() {
        val page = MarketJsonParser.parseList(
            parse("""{"items":[],"generatedAt":"2026-10-22T10:00:00Z"}"""),
            1,
        )
        assertEquals("2026-10-22T10:00:00Z", page.generatedAt)
    }
}

/**
 * 扩展快照合并与排序。
 *
 * 用户看到的是**一张**列表（工具包/沙盒/MCP/技能混排），
 * 所以排序规则必须固定，否则每次刷新顺序都会变。
 */
class ExtensionSnapshotTest {

    private fun plugin(
        id: String,
        title: String,
        enabled: Boolean = true,
        fatal: Boolean = false,
        providesUi: Boolean = false,
    ): InstalledPlugin = InstalledPlugin(
        manifest = PluginManifest(
            id = id,
            version = "1.0.0",
            main = "i.js",
            displayName = LocalizedText.ofZh(title),
            resources = if (providesUi) {
                listOf(PluginResource(key = "ui.main", path = "ui.html", mime = "text/html"))
            } else {
                emptyList()
            },
        ),
        installPath = "/tmp/$id",
        enabled = enabled,
        problems = if (fatal) listOf(ManifestProblem.Fatal("main", "缺")) else emptyList(),
    )

    @Test
    fun `四类都出现在同一份快照里`() {
        val snapshot = buildExtensionSnapshot(
            installedPlugins = listOf(plugin("p1", "工具包")),
            skills = listOf(Skill(name = "sk1", description = "技能", body = "b", folder = "/f")),
            skillEnabled = setOf("sk1"),
            mcpServers = listOf(McpServerConfig(name = "mcp1", endpoint = "https://e")),
            mcpToolCounts = mapOf("mcp1" to 5),
            loadOrder = null,
        )
        assertEquals(3, snapshot.entries.size)
        assertEquals(
            listOf(
                ExtensionEntry.Kind.ToolPackage,
                ExtensionEntry.Kind.McpServer,
                ExtensionEntry.Kind.Skill,
            ),
            snapshot.entries.map { it.kind },
        )
    }

    @Test
    fun `坏包排在可用包之后`() {
        val snapshot = buildExtensionSnapshot(
            installedPlugins = listOf(
                plugin("bad", "A坏包", fatal = true),
                plugin("good", "Z好包"),
            ),
            skills = emptyList(),
            skillEnabled = emptySet(),
            mcpServers = emptyList(),
            mcpToolCounts = emptyMap(),
            loadOrder = null,
        )
        // 尽管坏包的标题 A 排在 Z 前，但它不可用，必须沉底。
        assertEquals(listOf("good", "bad"), snapshot.entries.map { it.id })
    }

    @Test
    fun `提供 UI 的包额外出现在沙盒分组`() {
        val snapshot = buildExtensionSnapshot(
            installedPlugins = listOf(plugin("withUi", "带界面")),
            skills = emptyList(),
            skillEnabled = emptySet(),
            mcpServers = emptyList(),
            mcpToolCounts = emptyMap(),
            loadOrder = null,
        )
        val kinds = snapshot.entries.map { it.kind }
        assertTrue(ExtensionEntry.Kind.ToolPackage in kinds)
        assertTrue("有 UI 资源时应在沙盒分组出现", ExtensionEntry.Kind.SandboxPackage in kinds)

        // 同一个包出现两次是**有意**的：工具视角 + 界面视角。
        // 但必须能让用户看出它们是一个包，否则会误以为装了两份。
        assertEquals("会同时出现两条", 2, snapshot.entries.size)
        val sandbox = snapshot.entries.first { it.kind == ExtensionEntry.Kind.SandboxPackage }
        assertTrue(
            "沙盒条目必须说明它与工具包是同一个包：${sandbox.description}",
            sandbox.description.contains("同一个包"),
        )
        // key 必须不同，否则 Compose LazyColumn 会因为重复 key 崩。
        assertEquals(2, snapshot.entries.map { it.key }.toSet().size)
    }

    @Test
    fun `没有 UI 资源的包不进沙盒分组`() {
        val snapshot = buildExtensionSnapshot(
            installedPlugins = listOf(plugin("plain", "纯工具")),
            skills = emptyList(),
            skillEnabled = emptySet(),
            mcpServers = emptyList(),
            mcpToolCounts = emptyMap(),
            loadOrder = null,
        )
        assertFalse(ExtensionEntry.Kind.SandboxPackage in snapshot.entries.map { it.kind })
    }

    @Test
    fun `技能的启停状态来自集合`() {
        val snapshot = buildExtensionSnapshot(
            installedPlugins = emptyList(),
            skills = listOf(
                Skill(name = "on", description = "", body = "b", folder = "/f"),
                Skill(name = "off", description = "", body = "b", folder = "/f"),
            ),
            skillEnabled = setOf("on"),
            mcpServers = emptyList(),
            mcpToolCounts = emptyMap(),
            loadOrder = null,
        )
        val byId = snapshot.entries.associateBy { it.id }
        assertTrue(byId.getValue("on").enabled)
        assertFalse(byId.getValue("off").enabled)
    }

    @Test
    fun `MCP 缺少 endpoint 时带失败原因`() {
        val snapshot = buildExtensionSnapshot(
            installedPlugins = emptyList(),
            skills = emptyList(),
            skillEnabled = emptySet(),
            mcpServers = listOf(McpServerConfig(name = "broken", endpoint = "")),
            mcpToolCounts = emptyMap(),
            loadOrder = null,
        )
        val entry = snapshot.entries.single()
        assertFalse(entry.isUsable)
        assertTrue(entry.failure.orEmpty().contains("endpoint"))
    }

    @Test
    fun `stdio 传输被标为不可用并说明原因`() {
        val config = McpServerConfig(
            name = "local",
            endpoint = "some-binary",
            transport = McpTransport.Stdio,
        )
        val reason = config.usability
        assertTrue("必须说清 Android 上不可用", reason.orEmpty().contains("stdio"))
    }

    @Test
    fun `已安装版本查询`() {
        val snapshot = buildExtensionSnapshot(
            installedPlugins = listOf(plugin("com.a", "A")),
            skills = emptyList(),
            skillEnabled = emptySet(),
            mcpServers = emptyList(),
            mcpToolCounts = emptyMap(),
            loadOrder = null,
        )
        assertEquals("1.0.0", snapshot.installedVersionOf("com.a"))
        assertNull(snapshot.installedVersionOf("com.not.installed"))
    }

    @Test
    fun `加载顺序问题被原样带出`() {
        val ordering = PluginLoadOrder.resolve(listOf(plugin("solo", "单独")))
        val snapshot = buildExtensionSnapshot(
            installedPlugins = listOf(plugin("solo", "单独")),
            skills = emptyList(),
            skillEnabled = emptySet(),
            mcpServers = emptyList(),
            mcpToolCounts = emptyMap(),
            loadOrder = ordering,
        )
        assertEquals(ordering, snapshot.loadOrder)
    }
}

/**
 * 归档路径归一化（zip slip 防护）。
 *
 * 这条如果不测，一个恶意包可以用 `../../../data/data/...` 覆盖宿主文件 ——
 * 是「安装 zip」类功能最经典的漏洞。
 */
class PluginArchivePathTest {

    @Test
    fun `普通路径原样`() {
        assertEquals("packages/main.js", PluginArchive.normalizePath("packages/main.js"))
    }

    @Test
    fun `去掉点斜杠`() {
        assertEquals("a.js", PluginArchive.normalizePath("./a.js"))
        assertEquals("a/b.js", PluginArchive.normalizePath("./a/./b.js"))
    }

    @Test
    fun `反斜杠转正斜杠（Windows 打包）`() {
        assertEquals("packages/main.js", PluginArchive.normalizePath("packages\\main.js"))
    }

    @Test
    fun `折叠重复斜杠`() {
        assertEquals("a/b.js", PluginArchive.normalizePath("a//b.js"))
    }

    @Test
    fun `允许在包内向上回退`() {
        assertEquals("b.js", PluginArchive.normalizePath("a/../b.js"))
    }

    @Test
    fun `拒绝跳出包根（zip slip）`() {
        assertNull(PluginArchive.normalizePath("../evil.js"))
        assertNull(PluginArchive.normalizePath("a/../../evil.js"))
        assertNull(PluginArchive.normalizePath("../../../../data/data/host/file"))
    }

    @Test
    fun `拒绝绝对路径`() {
        assertNull(PluginArchive.normalizePath("/etc/passwd"))
        assertNull(PluginArchive.normalizePath("\\windows\\system32"))
    }

    @Test
    fun `拒绝空路径`() {
        assertNull(PluginArchive.normalizePath(""))
        assertNull(PluginArchive.normalizePath("   "))
        assertNull(PluginArchive.normalizePath("./"))
    }
}