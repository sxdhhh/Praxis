package dev.praxis.core.plugin

import dev.praxis.core.tool.PermissionTier
import dev.praxis.core.tool.ToolContext
import dev.praxis.core.tool.ToolRegistry
import dev.praxis.core.tool.ToolResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 插件工具的生命周期：装载 → 注册 → 调用 → 卸载。
 *
 * ## 这些断言锁的是「关掉插件后工具必须消失」
 *
 * 工具残留在注册表里是最隐蔽的一类 bug：UI 上插件已关闭、设置也保存成功，
 * 但模型仍能看到并使用那些工具。用户会觉得「关了没用」，
 * 而我们完全无法从现象反推原因。
 */
class PluginRuntimeHostTest {

    /** 假运行时：按预设返回注册的工具，并记录调用。 */
    private class FakeRuntime(
        var tools: List<PluginToolDeclaration> = emptyList(),
        var errors: List<String> = emptyList(),
    ) : PluginScriptRuntime {

        val loaded = mutableListOf<String>()
        val unloaded = mutableListOf<String>()
        val invocations = mutableListOf<Triple<String, String, String>>()

        override suspend fun load(
            packageId: String,
            mainScript: String,
            subpackageScripts: Map<String, String>,
            resourceResolver: (String) -> ByteArray?,
        ): PluginLoadOutcome {
            loaded += packageId
            lastSubpackageScripts = subpackageScripts
            return PluginLoadOutcome(tools, errors)
        }

        var lastSubpackageScripts: Map<String, String> = emptyMap()

        override suspend fun invoke(
            packageId: String,
            toolName: String,
            entryFunction: String,
            argumentsJson: String,
        ): ToolResult {
            invocations += Triple(packageId, toolName, argumentsJson)
            return ToolResult(content = "ok:$toolName")
        }

        override suspend fun unload(packageId: String) {
            unloaded += packageId
        }
    }

    private fun plugin(
        id: String = "com.a.pkg",
        enabled: Boolean = true,
        subpackages: List<PluginSubpackage> = emptyList(),
        disabledSubs: Set<String> = emptySet(),
    ): InstalledPlugin = InstalledPlugin(
        manifest = PluginManifest(
            id = id,
            version = "1.0.0",
            main = "index.js",
            displayName = LocalizedText.ofZh(id),
            subpackages = subpackages,
        ),
        installPath = "/tmp/$id",
        enabled = enabled,
        disabledSubpackageIds = disabledSubs,
    )

    private fun content(
        main: String? = "// main",
        subpackages: Map<String, String?> = emptyMap(),
    ): PluginArchiveContent = PluginArchiveContent(
        manifest = PluginManifest(id = "com.a.pkg", main = "index.js"),
        problems = emptyList(),
        mainScript = main,
        subpackageScripts = subpackages,
        resourceBytes = emptyMap(),
        manifestEntryName = "manifest.hjson",
    )

    private val declaration = PluginToolDeclaration(
        name = "plugin_do_thing",
        description = "做点事",
        parametersJson = """{"type":"object","properties":{"x":{"type":"string"}}}""",
    )

    @Test
    fun `装载后工具出现在注册表里`() = runTest {
        val registry = ToolRegistry()
        val runtime = FakeRuntime(tools = listOf(declaration))
        val host = PluginRuntimeHost(registry, runtime)

        val outcome = host.load(plugin(), content())
        assertTrue(outcome.isUsable)
        assertNotNull("工具应已注册", registry.find("plugin_do_thing"))
        assertEquals(listOf("plugin_do_thing"), host.toolsOf("com.a.pkg"))
        assertEquals(1, host.loadedCount)
    }

    @Test
    fun `插件工具的档位是 STANDARD 但风险标为危险`() = runTest {
        val registry = ToolRegistry()
        val host = PluginRuntimeHost(registry, FakeRuntime(tools = listOf(declaration)))
        host.load(plugin(), content())

        val spec = registry.find("plugin_do_thing")!!.spec
        assertEquals(
            "插件本身不该要求高权限，它调用的宿主工具才判定",
            PermissionTier.STANDARD,
            spec.tier,
        )
        assertEquals(dev.praxis.core.tool.ToolRisk.Dangerous, spec.risk)
    }

    @Test
    fun `卸载后工具从注册表消失`() = runTest {
        val registry = ToolRegistry()
        val runtime = FakeRuntime(tools = listOf(declaration))
        val host = PluginRuntimeHost(registry, runtime)
        host.load(plugin(), content())
        assertNotNull(registry.find("plugin_do_thing"))

        host.unload("com.a.pkg")
        assertNull("关掉插件后模型不能再看到这个工具", registry.find("plugin_do_thing"))
        assertTrue(host.toolsOf("com.a.pkg").isEmpty())
        assertEquals(0, host.loadedCount)
        assertEquals(listOf("com.a.pkg"), runtime.unloaded)
    }

    @Test
    fun `卸载未装载的包不报错也不调 runtime`() = runTest {
        val registry = ToolRegistry()
        val runtime = FakeRuntime()
        val host = PluginRuntimeHost(registry, runtime)
        host.unload("never.loaded")
        assertTrue(runtime.unloaded.isEmpty())
    }

    @Test
    fun `更新插件时旧工具被替换而不是重名崩溃`() = runTest {
        val registry = ToolRegistry()
        val runtime = FakeRuntime(tools = listOf(declaration))
        val host = PluginRuntimeHost(registry, runtime)

        host.load(plugin(), content())
        // 第二次装载（模拟更新）：工具名相同。
        val outcome = host.load(plugin(), content())
        assertTrue("重装不能因重名崩", outcome.isUsable)
        assertEquals(1, host.toolsOf("com.a.pkg").size)
        assertNotNull(registry.find("plugin_do_thing"))
    }

    @Test
    fun `更新后新版本删掉的工具也会消失`() = runTest {
        val registry = ToolRegistry()
        val twoTools = listOf(
            declaration,
            PluginToolDeclaration(name = "plugin_removed_later", description = "旧工具"),
        )
        val runtime = FakeRuntime(tools = twoTools)
        val host = PluginRuntimeHost(registry, runtime)
        host.load(plugin(), content())
        assertNotNull(registry.find("plugin_removed_later"))

        // 新版本只剩一个工具
        runtime.tools = listOf(declaration)
        host.load(plugin(), content())
        assertNull("旧版本独有的工具必须消失", registry.find("plugin_removed_later"))
    }

    @Test
    fun `未启用的插件不装载`() = runTest {
        val registry = ToolRegistry()
        val runtime = FakeRuntime(tools = listOf(declaration))
        val host = PluginRuntimeHost(registry, runtime)

        val outcome = host.load(plugin(enabled = false), content())
        assertTrue(outcome.tools.isEmpty())
        assertTrue("不该调 runtime", runtime.loaded.isEmpty())
        assertNull(registry.find("plugin_do_thing"))
    }

    @Test
    fun `清单有致命问题时拒绝装载`() = runTest {
        val registry = ToolRegistry()
        val runtime = FakeRuntime(tools = listOf(declaration))
        val host = PluginRuntimeHost(registry, runtime)
        val broken = plugin().copy(
            problems = listOf(ManifestProblem.Fatal("main", "文件缺失")),
        )
        val outcome = host.load(broken, content())
        assertTrue(outcome.tools.isEmpty())
        assertTrue(outcome.errors.first().contains("文件缺失"))
        assertTrue(runtime.loaded.isEmpty())
    }

    @Test
    fun `主脚本读不到时给出可读错误`() = runTest {
        val registry = ToolRegistry()
        val host = PluginRuntimeHost(registry, FakeRuntime(tools = listOf(declaration)))
        val outcome = host.load(plugin(), content(main = null))
        assertTrue(outcome.tools.isEmpty())
        assertTrue(outcome.errors.first().contains("主入口脚本"))
    }

    @Test
    fun `只装载启用中的子包脚本`() = runTest {
        val registry = ToolRegistry()
        val runtime = FakeRuntime(tools = listOf(declaration))
        val host = PluginRuntimeHost(registry, runtime)
        val pkg = plugin(
            subpackages = listOf(
                PluginSubpackage(id = "one", entry = "one.js"),
                PluginSubpackage(id = "two", entry = "two.js"),
            ),
            disabledSubs = setOf("two"),
        )
        host.load(pkg, content(subpackages = mapOf("one" to "//1", "two" to "//2")))
        assertEquals(setOf("one"), runtime.lastSubpackageScripts.keys)
    }

    @Test
    fun `工具执行转发到脚本并带上原始参数`() = runTest {
        val registry = ToolRegistry()
        val runtime = FakeRuntime(tools = listOf(declaration))
        val host = PluginRuntimeHost(registry, runtime)
        host.load(plugin(), content())

        val tool = registry.find("plugin_do_thing")!!
        val result = tool.execute(
            """{"x":"hello"}""",
            object : ToolContext {
                override val tier: PermissionTier = PermissionTier.ROOT
            },
        )
        assertFalse(result.isError)
        assertEquals("ok:plugin_do_thing", result.content)
        val call = runtime.invocations.single()
        assertEquals("com.a.pkg", call.first)
        assertEquals("""{"x":"hello"}""", call.third)
    }

    @Test
    fun `脚本抛异常时返回错误结果而不是崩`() = runTest {
        val failing = object : PluginScriptRuntime {
            override suspend fun load(
                packageId: String,
                mainScript: String,
                subpackageScripts: Map<String, String>,
                resourceResolver: (String) -> ByteArray?,
            ) = PluginLoadOutcome(listOf(declaration))

            override suspend fun invoke(
                packageId: String,
                toolName: String,
                entryFunction: String,
                argumentsJson: String,
            ): ToolResult = throw IllegalStateException("脚本炸了")

            override suspend fun unload(packageId: String) = Unit
        }
        val registry = ToolRegistry()
        val host = PluginRuntimeHost(registry, failing)
        host.load(plugin(), content())

        val result = registry.find("plugin_do_thing")!!.execute(
            "{}",
            object : ToolContext {
                override val tier: PermissionTier = PermissionTier.STANDARD
            },
        )
        assertTrue("失败要作为结果回传，不抛", result.isError)
        assertTrue(result.content.contains("脚本炸了"))
    }

    @Test
    fun `每个插件各自记账，互不影响`() = runTest {
        val registry = ToolRegistry()
        val runtimeA = FakeRuntime(tools = listOf(PluginToolDeclaration("a_tool", "A")))
        val runtimeB = FakeRuntime(tools = listOf(PluginToolDeclaration("b_tool", "B")))
        // 两个 runtime 分别代表两个包的执行上下文；这里共用一个 host
        // 只是为了验证「记账按包分开」。
        val host = PluginRuntimeHost(registry, runtimeA)
        host.load(plugin(id = "pkg.a"), content())

        val hostB = PluginRuntimeHost(registry, runtimeB)
        hostB.load(plugin(id = "pkg.b"), content())

        assertEquals(listOf("a_tool"), host.toolsOf("pkg.a"))
        assertEquals(listOf("b_tool"), hostB.toolsOf("pkg.b"))

        host.unload("pkg.a")
        assertNull(registry.find("a_tool"))
        assertNotNull("卸载 A 不能影响 B", registry.find("b_tool"))
    }
}